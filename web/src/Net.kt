package com.poolgame.web

import kotlin.js.JSON
import kotlin.random.Random

/** What a game in progress hears from the room. */
interface NetGameListener {
    fun onNetAct(a: dynamic)
    fun onNetAim(a: dynamic)
    fun onNetRps(a: dynamic)
    fun onNetPresence()
}

/** What the menu hears from the room. */
interface NetHost {
    /** The lobby changed: who's in, the settings, the connection. */
    fun onLobby()
    /** A game started (or we joined one in progress): build it from the room's settings and this act. */
    fun onNetStart(act: dynamic)
    /** The room is gone (the host closed it) or couldn't be found. */
    fun onNetClosed(why: String)
}

/**
 * Online play: a room on a public MQTT broker, which only relays messages between the players' browsers.
 *
 * Topics, under `rpsdi-pool/1/<CODE>/`:
 *  - `cfg` (retained, from the host): the game settings and who sits where.
 *  - `p/<id>` (retained, one per player; the broker posts the "gone" version if they drop): who's here.
 *  - `act` (retained): the latest thing that happened — a game starting, a shot (with its recorded
 *    animation), a group picked… Each carries the full game state afterwards and a version number, so a
 *    player who missed something (or rejoins) just takes the latest one.
 *  - `aim` and `rps`: the current player's aim as they line up, and rock-paper-scissors picks.
 *
 * Only the player whose turn it is acts; their browser runs the physics and everyone plays back its result.
 */
class Online(private val host: NetHost) {
    val netId: String = Store.get("netId", "").ifEmpty { randomId(10).also { Store.set("netId", it) } }
    var name: String = Store.get("netName", "")
        set(v) { field = v.trim().take(14); Store.set("netName", field) }

    var code = ""; private set
    var isHost = false; private set
    /** The room's settings and seats (from `cfg`), or null before they arrive. */
    var cfg: dynamic = null; private set
    var mySeat = -1; private set
    /** "connecting", "online", "reconnecting", "failed" */
    var status = "connecting"; private set
    var game: NetGameListener? = null
    /** The newest act seen (ours or anyone's). */
    var lastAct: dynamic = null; private set
    var started = false; private set

    private val presence = LinkedHashMap<String, dynamic>()
    private var mq: Mqtt? = null
    private var brokerIdx = 0
    private var hostAttempt = 0
    private var retryMs = 1000
    private var active = false
    private var myLastAct: dynamic = null
    private var cfgTimer: dynamic = null
    private val base get() = "rpsdi-pool/1/$code/"

    init {
        document.addEventListener("visibilitychange", {
            if (document.hidden != true && active) {
                val m = mq
                if (m == null || !m.connected || m.quietFor() > 4000) { m?.drop(); if (m == null) reconnectSoon(100) }
            }
        })
    }

    // ------------------------------------------------------------------ rooms

    /** Host a new room with these settings ([need] players). */
    fun hostRoom(settings: dynamic, need: Int) {
        leave()
        isHost = true; started = false; mySeat = 0
        hostAttempt = 0
        val c: dynamic = js("({})")
        c.host = netId; c.s = settings; c.need = need
        c.seats = arrayOf(arrayOf(netId, displayName()))
        cfg = c
        openRoom(newCode(0))
    }

    /** Join the room with this code. */
    fun joinRoom(roomCode: String) {
        leave()
        isHost = false; started = false; mySeat = -1; cfg = null
        val c = roomCode.uppercase().filter { it in ALPHABET }
        if (c.length != CODE_LEN) { host.onNetClosed("That code isn't right — it's $CODE_LEN letters"); return }
        openRoom(c)
        cfgTimer = window.setTimeout({ if (cfg == null && active) host.onNetClosed("No game found with code $c") }, 9000)
    }

    /** Leave the room (and close it, if it's ours and hasn't started). */
    fun leave() {
        if (!active) return
        active = false
        val m = mq
        if (m != null && m.connected) {
            m.publish(base + "p/" + netId, presenceText(false), retain = true)
            if (isHost && !started) { m.publish(base + "cfg", "", retain = true); m.publish(base + "act", "", retain = true) }
        }
        m?.close()
        mq = null
        if (cfgTimer != null) { window.clearTimeout(cfgTimer); cfgTimer = null }
        presence.clear()
        lastAct = null; myLastAct = null; game = null
        started = false
    }

    /** Host: start the game (the host's own game publishes the opening act). */
    fun markStarted() { started = true }

    fun seats(): List<Pair<String, String>> {
        val c = cfg ?: return emptyList()
        val s = c.seats ?: return emptyList()
        val n = s.length as Int
        return (0 until n).map { Pair(s[it][0] as String, s[it][1] as String) }
    }

    fun need(): Int = (cfg?.need as Int?) ?: 2

    /** Is the player in [seat] connected right now? */
    fun seatOnline(seat: Int): Boolean {
        val s = seats().getOrNull(seat) ?: return false
        if (s.first == netId) return status == "online"
        val p = presence[s.first] ?: return false
        return p.on == 1
    }

    fun inviteLink(): String {
        val loc = window.location
        return "${loc.origin}${loc.pathname}?room=$code" + brokerParam()
    }

    fun displayName() = name.ifEmpty { "Player" }

    // ------------------------------------------------------------------ sending

    /** Publish an act (the room keeps the latest). [a] must have v (version) and s (seat). */
    fun sendAct(a: dynamic) {
        myLastAct = a
        lastAct = a
        mq?.publish(base + "act", JSON.stringify(a), retain = true)
    }

    fun sendAim(a: dynamic) { mq?.publish(base + "aim", JSON.stringify(a)) }
    fun sendRps(a: dynamic) { mq?.publish(base + "rps", JSON.stringify(a)) }

    // ------------------------------------------------------------------ connection

    private fun brokers(): List<String> {
        val q = window.location.search as String
        val m = Regex("[?&]broker=([^&]+)").find(q)
        if (m != null) return listOf(js("decodeURIComponent")(m.groupValues[1]) as String)
        return BROKERS
    }

    private fun brokerParam(): String {
        val q = window.location.search as String
        val m = Regex("[?&](broker=[^&]+)").find(q) ?: return ""
        return "&" + m.groupValues[1]
    }

    /** A room code whose first letter says which broker it lives on. */
    private fun newCode(broker: Int): String {
        val n = brokers().size
        val firsts = ALPHABET.filterIndexed { i, _ -> i % n == broker }
        return firsts[Random.nextInt(firsts.length)] + (1 until CODE_LEN).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
    }

    private fun openRoom(c: String) {
        code = c
        active = true
        brokerIdx = ALPHABET.indexOf(c[0]) % brokers().size
        status = "connecting"
        retryMs = 1000
        host.onLobby()
        connect()
    }

    private fun connect() {
        if (!active) return
        val url = brokers()[brokerIdx]
        val m = Mqtt(url, "pg" + netId + randomId(6))
        mq = m
        m.onConnect = {
            if (mq === m) {
                status = "online"; retryMs = 1000
                m.subscribe(base + "#")
                m.publish(base + "p/" + netId, presenceText(true), retain = true)
                if (isHost && !started && cfg != null) publishCfg()
                // If something we did never reached the room (we dropped as we sent it), send it again.
                window.setTimeout({
                    val mine = myLastAct
                    val seen = lastSeenFromRoom
                    if (mq === m && m.connected && mine != null && (mine.v as Int) > seen) m.publish(base + "act", JSON.stringify(mine), retain = true)
                }, 2000)
                host.onLobby(); game?.onNetPresence()
            }
        }
        m.onClose = {
            if (mq === m) {
                mq = null
                if (active) {
                    // Hosting and the broker can't be reached at all: try the next one with a new code.
                    if (isHost && !started && status == "connecting" && hostAttempt + 1 < brokers().size) {
                        hostAttempt++
                        code = newCode(hostAttempt)
                        brokerIdx = hostAttempt
                        host.onLobby()
                        reconnectSoon(200)
                    } else {
                        status = if (status == "connecting" && !started && !isHost && cfg == null) "failed" else "reconnecting"
                        host.onLobby(); game?.onNetPresence()
                        reconnectSoon(retryMs)
                        retryMs = minOf(retryMs * 2, 8000)
                    }
                }
            }
        }
        m.onMessage = { t, text -> onMessage(t, text) }
        m.connect(base + "p/" + netId, presenceText(false))
    }

    private fun reconnectSoon(ms: Int) { window.setTimeout({ if (active && mq == null) connect() }, ms) }

    private var lastSeenFromRoom = 0

    private fun presenceText(on: Boolean): String {
        val p: dynamic = js("({})")
        p.n = displayName(); p.on = if (on) 1 else 0
        return JSON.stringify(p)
    }

    private fun publishCfg() { mq?.publish(base + "cfg", JSON.stringify(cfg), retain = true) }

    private fun onMessage(topic: String, text: String) {
        if (!active || !topic.startsWith(base)) return
        val key = topic.substring(base.length)
        val msg: dynamic = if (text.isEmpty()) null else try { JSON.parse<dynamic>(text) } catch (e: dynamic) { return }
        when {
            key == "cfg" -> {
                if (msg == null) {
                    if (!isHost && cfg != null) { val was = active; leave(); if (was) host.onNetClosed("The host closed the game") }
                    return
                }
                if (isHost && msg.host != netId) {
                    // Someone else made a room with our code (very unlikely): make a new one.
                    code = newCode(brokerIdx); mq?.drop(); return
                }
                if (!isHost) {
                    cfg = msg
                    mySeat = seats().indexOfFirst { it.first == netId }
                    if (cfgTimer != null) { window.clearTimeout(cfgTimer); cfgTimer = null }
                }
                host.onLobby()
                // The game had already started (the act can arrive before the settings).
                val a = lastAct
                if (!isHost && game == null && a != null && !started) { started = true; host.onNetStart(a) }
            }
            key.startsWith("p/") -> {
                val id = key.substring(2)
                if (msg == null) presence.remove(id) else presence[id] = msg
                if (isHost && !started && id != netId && msg != null) seatGuests()
                host.onLobby(); game?.onNetPresence()
            }
            key == "act" -> {
                if (msg == null) return
                val v = msg.v as Int
                if (v > lastSeenFromRoom) lastSeenFromRoom = v
                val prev = lastAct
                if (prev != null && (prev.v as Int) > v) return
                if (prev != null && (prev.v as Int) == v && prev.s == msg.s && prev.k == msg.k) {
                    // Our own act coming back, or one we already have.
                    if (game != null) return
                }
                lastAct = msg
                val g = game
                if (g != null) g.onNetAct(msg)
                else if (cfg != null && (mySeat >= 0 || !isHost)) { started = true; host.onNetStart(msg) }
            }
            key == "aim" -> if (msg != null && msg.s != mySeat) game?.onNetAim(msg)
            key == "rps" -> if (msg != null && msg.s != mySeat) game?.onNetRps(msg)
        }
    }

    /** Host, before the start: seat everyone who's here (in the order they arrived), up to the number needed. */
    private fun seatGuests() {
        val cur = seats().toMutableList()
        var changed = false
        // Drop guests who've left.
        val it = cur.iterator()
        var first = true
        while (it.hasNext()) {
            val s = it.next()
            if (first) { first = false; continue }
            if (presence[s.first]?.on != 1) { it.remove(); changed = true }
        }
        for ((id, p) in presence) {
            if (id == netId || p.on != 1 || cur.any { it.first == id }) continue
            if (cur.size >= need()) break
            cur += Pair(id, (p.n as String?) ?: "Player")
            changed = true
        }
        // Names can change (a guest typed theirs after joining).
        for (i in 1 until cur.size) {
            val nm = presence[cur[i].first]?.n as String?
            if (nm != null && nm != cur[i].second) { cur[i] = Pair(cur[i].first, nm); changed = true }
        }
        if (changed) {
            cfg.seats = cur.map { arrayOf(it.first, it.second) }.toTypedArray()
            publishCfg()
        }
    }

    /** Host: the menu's settings changed before the start. */
    fun updateSettings(settings: dynamic, need: Int) {
        if (!isHost || started || cfg == null) return
        cfg.s = settings; cfg.need = need
        val cur = seats()
        if (cur.size > need) cfg.seats = cur.take(need).map { arrayOf(it.first, it.second) }.toTypedArray()
        publishCfg()
        seatGuests()
        host.onLobby()
    }

    /** Our name changed: tell the room. */
    fun announceName() {
        val m = mq ?: return
        if (!m.connected) return
        m.publish(base + "p/" + netId, presenceText(true), retain = true)
        if (isHost && !started && cfg != null) {
            cfg.seats[0][1] = displayName(); publishCfg()
        }
    }

    companion object {
        /** Free public MQTT brokers that take secure WebSocket connections from a browser. */
        val BROKERS = listOf("wss://broker.emqx.io:8084/mqtt", "wss://broker.hivemq.com:8884/mqtt", "wss://test.mosquitto.org:8081/mqtt")
        /** Letters that can't be mixed up (no I, O, 0 or 1). */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val CODE_LEN = 5

        fun randomId(n: Int): String {
            val a = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (0 until n).map { a[Random.nextInt(a.length)] }.joinToString("")
        }
    }
}
