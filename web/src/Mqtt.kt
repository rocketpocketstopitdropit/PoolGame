package com.poolgame.web

/**
 * A minimal MQTT 3.1.1 client over a WebSocket: connect (with a last will), subscribe, publish and
 * receive, all at QoS 0. That's all online play needs, and it keeps the web build free of libraries.
 * Messages are UTF-8 text.
 */
class Mqtt(private val url: String, private val clientId: String, private val keepAlive: Int = 20) {
    var onConnect: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onMessage: ((topic: String, text: String) -> Unit)? = null

    var connected = false; private set
    private var ws: dynamic = null
    private var closed = false
    private var inBuf: dynamic = u8(0)
    private var timer: dynamic = null
    private var lastRx = 0.0
    private var packetId = 1
    private val enc: dynamic = js("new TextEncoder()")
    private val dec: dynamic = js("new TextDecoder()")

    /** Open the connection. [willTopic]/[willText]: published (retained) by the broker if we drop without saying goodbye. */
    fun connect(willTopic: String? = null, willText: String? = null) {
        closed = false
        val u = url
        val sock: dynamic = try { js("new WebSocket(u, ['mqtt'])") } catch (e: dynamic) { null }
        if (sock == null) { window.setTimeout({ fail() }, 0); return }
        ws = sock
        sock.binaryType = "arraybuffer"
        sock.onopen = { _: dynamic ->
            var flags = 0x02   // clean session
            val parts = ArrayList<dynamic>()
            parts.add(str("MQTT"))
            if (willTopic != null && willText != null) flags = flags or 0x04 or 0x20   // will, retained, QoS 0
            parts.add(bytes(4, flags, keepAlive shr 8, keepAlive and 255))
            parts.add(str(clientId))
            if (willTopic != null && willText != null) { parts.add(str(willTopic)); parts.add(str(willText)) }
            send(packet(0x10, concat(parts)))
            lastRx = now()
        }
        sock.onmessage = { e: dynamic -> lastRx = now(); receive(e.data) }
        sock.onclose = { _: dynamic -> fail() }
        sock.onerror = { _: dynamic -> try { sock.close() } catch (_: dynamic) { } ; fail() }
        timer = window.setInterval({ tick() }, 1000)
    }

    fun subscribe(vararg topics: String) {
        if (!connected) return
        val id = nextId()
        val parts = ArrayList<dynamic>()
        parts.add(bytes(id shr 8, id and 255))
        for (t in topics) { parts.add(str(t)); parts.add(bytes(0)) }
        send(packet(0x82, concat(parts)))
    }

    /** Publish [text] on [topic]. An empty retained message clears what the broker keeps there. */
    fun publish(topic: String, text: String, retain: Boolean = false): Boolean {
        if (!connected) return false
        val payload = enc.encode(text)
        send(packet(0x30 or (if (retain) 1 else 0), concat(listOf(str(topic), payload))))
        return true
    }

    /** Say goodbye (the broker then doesn't send the will) and close. */
    fun close() {
        if (closed) return
        closed = true
        try { if (connected) send(bytes(0xE0, 0)) } catch (_: dynamic) { }
        connected = false
        stopTimer()
        try { ws?.close() } catch (_: dynamic) { }
        ws = null
    }

    /** Close without the goodbye, e.g. a socket that's gone quiet after the phone slept. */
    fun drop() { if (!closed) { try { ws?.close() } catch (_: dynamic) { }; fail() } }

    /** Milliseconds since anything came in from the broker. */
    fun quietFor() = now() - lastRx

    // ------------------------------------------------------------------ internals

    private fun fail() {
        if (closed) return
        closed = true
        connected = false
        stopTimer()
        ws = null
        onClose?.invoke()
    }

    private fun stopTimer() { if (timer != null) { window.clearInterval(timer); timer = null } }

    private fun tick() {
        if (closed) return
        val quiet = now() - lastRx
        if (quiet > keepAlive * 1500.0) { drop(); return }   // nothing back (not even a ping reply): the link is dead
        if (connected && quiet > keepAlive * 500.0) send(bytes(0xC0, 0))
    }

    private fun nextId(): Int { packetId = packetId % 65535 + 1; return packetId }

    private fun send(data: dynamic) {
        val s = ws ?: return
        try { s.send(data) } catch (_: dynamic) { drop() }
    }

    private fun receive(data: dynamic) {
        val add: dynamic = js("new Uint8Array(data)")
        val old = inBuf
        val merged = u8((old.length as Int) + (add.length as Int))
        merged.set(old, 0); merged.set(add, old.length)
        inBuf = merged
        while (true) {
            val b = inBuf
            val n = b.length as Int
            if (n < 2) return
            // Remaining length: 1 to 4 bytes, 7 bits each.
            var len = 0; var mul = 1; var i = 1
            while (true) {
                if (i >= n) return
                val d = b[i] as Int
                len += (d and 127) * mul
                mul *= 128
                i++
                if (d and 128 == 0) break
                if (i > 4) { drop(); return }
            }
            if (n < i + len) return
            val type = b[0] as Int
            val body = b.subarray(i, i + len)
            inBuf = b.subarray(i + len)
            handle(type, body)
        }
    }

    private fun handle(type: Int, body: dynamic) {
        when (type shr 4) {
            2 -> {   // CONNACK
                val rc = if ((body.length as Int) >= 2) body[1] as Int else 255
                if (rc == 0) { connected = true; onConnect?.invoke() } else drop()
            }
            3 -> {   // PUBLISH
                val qos = (type shr 1) and 3
                val tl = ((body[0] as Int) shl 8) or (body[1] as Int)
                val topic = dec.decode(body.subarray(2, 2 + tl)) as String
                var p = 2 + tl
                if (qos > 0) {
                    val hi = body[p] as Int; val lo = body[p + 1] as Int
                    p += 2
                    if (qos == 1) send(bytes(0x40, 2, hi, lo))   // PUBACK (we only ask for QoS 0, but be polite)
                }
                val text = dec.decode(body.subarray(p)) as String
                try { onMessage?.invoke(topic, text) } catch (e: dynamic) { console.error(e) }
            }
            else -> {}   // SUBACK, PINGRESP
        }
    }

    private fun u8(n: Int): dynamic = js("new Uint8Array(n)")

    private fun bytes(vararg v: Int): dynamic {
        val out = u8(v.size)
        for (i in v.indices) out[i] = v[i]
        return out
    }

    /** An MQTT string: two length bytes, then UTF-8. */
    private fun str(s: String): dynamic {
        val b = enc.encode(s)
        val n = b.length as Int
        val out = u8(2 + n)
        out[0] = n shr 8; out[1] = n and 255
        out.set(b, 2)
        return out
    }

    private fun concat(parts: List<dynamic>): dynamic {
        var total = 0
        for (p in parts) total += p.length as Int
        val out = u8(total)
        var at = 0
        for (p in parts) { out.set(p, at); at += p.length as Int }
        return out
    }

    private fun packet(type: Int, body: dynamic): dynamic {
        val len = body.length as Int
        val hdr = ArrayList<Int>()
        hdr += type
        var x = len
        do {
            var d = x % 128
            x /= 128
            if (x > 0) d = d or 128
            hdr += d
        } while (x > 0)
        val out = u8(hdr.size + len)
        for (i in hdr.indices) out[i] = hdr[i]
        out.set(body, hdr.size)
        return out
    }
}
