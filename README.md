# Pool — 8-Ball, 9-Ball & Trick Shots for Android

A landscape pool game on a 7, 8 or 9 ft table, with real cue-ball spin, a 3D view down the cue, physically real jump shots (and
illegal scoops), 8-ball and 9-ball rules, Circuit Pool (three secret target balls each), Pocket Wars (a fight over the six pockets), Cutthroat (three players, three groups of five), bumper pool on its own table, octagon bumper pool for up to four players (or two teams) on an eight-sided table with ramps for cushions, trick shots (jumps across a table with a gap cut out of it, and eight classic trick shots), a computer opponent with ten skill levels, and pass-and-play for two people (three in Cutthroat). Pure Kotlin, no third-party libraries,
no image or sound assets (balls are rendered per-pixel and sounds are synthesized).

## Build & run

1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync (AGP 8.9.1, Kotlin 2.1.21, Gradle 8.14.3 via the wrapper; compileSdk 35, minSdk 26).
3. Run the `app` configuration on a phone or emulator.

Command line: `./gradlew installDebug` (APK at `app/build/outputs/apk/debug/`).
Engine unit tests: `./gradlew test`.

### Web version (iPhone, any browser)

`web/` is a browser version built from the **same game-engine code** (`app/src/main/java/com/poolgame/app/core`)
compiled to JavaScript, plus a canvas screen in `web/src`. `.github/workflows/pages.yml` builds it on every push
and publishes it with GitHub Pages at **https://rocketpocketstopitdropit.github.io/PoolGame/**.
On iPhone: open the link in Safari → Share → *Add to Home Screen* for a full-screen, offline-capable app icon.
If the phone is in portrait (e.g. rotation lock), the game draws sideways so it fills the screen held horizontally.
Local build: `KOTLIN_HOME=/path/to/kotlinc web/build.sh` → `web/build/site`.

### Building on GitHub (no Android Studio)

Push this folder to a GitHub repository. `.github/workflows/build.yml` runs on every push: it runs
the tests, builds a debug APK and attaches it to the run. Open the repo's **Actions** tab → latest
**Build APK** run → download **pool-debug-apk** (a zip containing `app-debug.apk`). Copy the APK to
your phone and open it (allow "install unknown apps" for your browser or file manager when asked).
You can also start a build manually from the Actions tab with **Run workflow**.

## Learning the controls

**Tutorial**: every time the app opens it asks *Want a quick tutorial?* (Show me / Not now, with a *Don't ask again*
box; the menu's *Offer the tutorial when the app opens* switch turns the question back on). The tutorial is a
one-minute, hands-on tour on a real table: each step dims the screen except one control, a moving finger shows the
gesture, and a card says what to do. It moves on only once you've actually used that control: aim on the table,
the fine-aim roller, spin, the 3D view (tap the 1-ball, raise the cue, back to Top view), then pull the power bar
to pot a lined-up ball, the ball tray, dragging the cue ball with ball in hand, and the ☰ pause menu. *Skip tutorial* (top right)
leaves at any time. It never touches your paused game or your record. Replay it from **CONTROLS → ▶ Tutorial**.

**Screen guide** (menu → CONTROLS → *Screen guide*): a labelled map of the game screen, drawn from the game's own
layout, with numbered badges on every control, and below it what each one does and how to operate it — plus how to
read the lines on the table, two-finger use, the rock-paper-scissors screen and the end-of-game buttons. Tap a
number on the map (or an entry in the list) to outline that control.

The steps and the guide text live in shared engine code (`core/Tutorial.kt`, `core/ControlsGuide.kt`), so the
Android app and the web version say exactly the same thing.

## How to play

| Control | What it does |
|---|---|
| Drag on the table | Aim the cue toward your finger |
| **Fine aim** wheel (right, below spin) | Wide roller: drag up/down to nudge the aim. It accelerates with finger speed — slow drags are ~3× finer than the base speed, fast swipes up to 25× coarser, so a flick swings the cue a long way. Base speed is adjustable 1–10 in the menu (5 ≈ 0.01° per dp at ~150 dp/s, each step 1.6×) |
| Tap a ball | Aims straight at it and opens the **3D view** down the cue (see below) |
| **Spin** cue ball (right) | The cue ball as you see it down the cue: touch where the tip strikes (top = follow, bottom = draw, sides = english). Inside the dashed ring the tip grips; outside it slips (miscue), and low enough with a level cue it scoops the ball (a foul). The label under it warns you, and shows the cue's angle when it's raised. Tap the centre to reset |
| **Power** bar (left) | Pull down to set power, release to shoot. Slide back to the top to cancel. While holding it, you can use another finger on the fine-aim wheel, the table (or 3D view) or spin |
| Ball in hand | Drag the cue ball (shaded area only before the break) |
| Ball tray (left, next to the power bar) | Pocketed balls roll down it and line up bottom-to-top in the order they were made. Respotted balls leave it |
| ☰ / Back | Pause menu: resume, restart rack, quit |

The aim guide is computed by running the real physics for the first part of the shot, so it shows
exactly where the balls will go — including throw, follow/draw and jumps (yellow = airborne). A red
ghost ball with an X means that ball is not a legal first contact; a red ring means a scratch.
There is no random error on your shots. **Shot prediction** can be turned off (menu or pause menu); then only a
straight aim line from the cue ball to the cushion is shown. Pocket holes are drawn exactly the size of the
drop zone with a bright ring: once a ball's centre crosses the ring it falls.

**3D view**: tap **3D view ▶** (over the top rail; your aim stays as it is) or tap a ball (aims straight at it) to see
the table in 3D — cushions, pockets, lit rolling balls, the aim guide on the cloth and your cue. Play in either view or
swap whenever you like; **◀ Top view** goes back.
- One finger: drag sideways to aim (faster drags turn further), drag up to raise the back of the cue (0–60°), down to
  level it. Tap a ball to line up on it, or a pocket to call it.
- Two fingers: spread to zoom in, pinch to zoom out, move them up or down together to tilt the camera, sideways to
  swing it round the cue ball. ⟲ puts it back behind the cue. (In a browser on a computer, the mouse wheel zooms.)
- Tap the empty table to move your point of view there: the camera looks along your aim line from that spot (for
  example behind the object ball, to line it up with the pocket), and aiming, the guide and the shot work exactly as
  from behind the cue ball. Tap the cue ball or ⟲ to go back; each new turn starts behind the cue ball.
- The camera follows the cue ball, and holds still while the balls roll so you can watch the shot. CPU turns play out
  in 3D too.
- Ball in hand: drag the cue ball where you want it. Practice: hold a ball for a moment, then drag it.
- The cue angle you set stays until the end of the turn and shows under the spin ball.

**Jump shots** have no button and no slider: raise the cue in the 3D view, pick the tip spot on the spin ball and pull
the power. The physics decides whether it jumps — an elevated cue drives the ball down into the slate and it bounces
up; steeper and harder jumps higher. Follow, draw and english ride along in the air and take hold when it lands (with
the cue raised, english also curves the ball, like a masse). A level cue struck low enough to dig under the ball
**scoops** it into the air instead: the shot plays out, but it's a foul (opponent gets ball in hand), as in WPA rules.

**Who breaks**: every new 8-ball or 9-ball game, rematch and restart starts with rock, paper, scissors (fist, flat hand,
sideways peace sign). Against the CPU you pick and it picks at random; in pass & play each player taps their own
side (the pick stays hidden until both are in). Ties go again; the winner breaks. The two hands point at each
other: the left one is the mirror image of the right one. Your rock, paper, scissors wins and losses, the longest
game (most throws, ties included) and the average number of throws to a winner are kept with your record.
Cutthroat has three players, so no rock, paper, scissors: the first breaker is drawn at random and each rematch
passes the break to the next player.

**Crowd**: scratching the cue ball or knocking any ball off the table gets a synthesized crowd "awww".
**Shot guide**: the cue ball's path is shown up to the first ball it hits (or just past its first cushion), then
only a short stretch of where each ball goes — enough to see the angle, draw, follow and curve, not the whole
result. On a jump shot a yellow ring marks where the cue ball first comes down; it slides smoothly as you add
power. A ball in the air is drawn lifted and bigger
(height exaggerated on screen; the distance is real) with its shadow left on the cloth.

A table foul — hitting nothing, hitting the wrong ball first, or no rail and nothing potted — plays your
**foul voice**: record it in the menu (FOUL VOICE → Record, up to 3 seconds; Play to check, Delete to remove).
It's trimmed and levelled automatically and kept on that device. Until you record one, a synthesized cough plays.

**Table size** (menu → TABLE SIZE): 7 ft (bar box, 78″ × 39″, the default), 8 ft (88″ × 44″) or 9 ft (tournament,
100″ × 50″) for 8-ball, 9-ball, Circuit Pool, Pocket Wars, Cutthroat and their practice. Balls and pockets are the same size on all three, so a smaller
table means shorter shots and bigger balls on screen. Trick shots and the tutorial always use the 9 ft table. A paused
game keeps the table it was started on.

**Pause & resume**: ☰ → *Main menu* keeps the game; the menu shows *Resume*. The game is also saved if you switch
apps or close it. A shot that was still rolling is replayed from the start of that turn.

**Shot clock** (menu): optional 10–120 s per shot for human players; running out is a foul. With
ball in hand the clock waits until you aim (table or fine-aim wheel) or touch the power bar.

**Every game three ways**: pick the game (8-Ball, 9-Ball, Circuit, Pocket Wars, Cutthroat, Bumper, Octagon, Trick), then the
**Opponent**: *Computer* (ten levels), *Pass & Play*, or *Practice* on your own. In Cutthroat, *Computer* means you
against two CPUs, both at the level on the skill slider, and *Pass & Play* is three people sharing the phone. On the
octagon table, *Computer* is you against one to three CPUs (all at that level), or you and a CPU partner against two CPUs.

**Practice** (Opponent → Practice). For 8-ball, 9-ball, Circuit Pool, Pocket Wars and Cutthroat:
- *With the rules* (the default): the game's own rack and rules, but you take every shot. Each shot is judged as in a
  match — fouls give you ball in hand, groups are assigned, Circuit Pool deals you three targets and hides three for an
  absent opponent (sink theirs and they count against you), Pocket Wars claims pockets for you, Cutthroat lets you pick
  your group and gives the other two to two absent opponents (clear both to win the rack; lose yours and it's lost). The card on the right
  shows where the rack stands and your shots and fouls; the rack ends as it would in a game (*Rack won!* or *Rack lost*,
  with your shot count), and *Re-rack* starts again. *Retry shot* undoes your last shot, rules and all, and you can hold
  any ball to move it. (Clear table and the tray are off, since they'd change the score.)
- *Free table*: just you, a cue ball and red/yellow balls (plus the black), no numbers, no rules. Tap a ball to aim at
  it; hold a ball for a moment to pick it up and drag it anywhere — including the cue ball — to set up trick shots.
  *Retry shot* puts every ball back where it was before your last shot, *Re-rack* starts over, *Clear table* removes
  the object balls, and tapping a ball in the ball tray (left) brings it back.

Bumper, Octagon and Trick have their own practice, below.
**Record**: wins and losses are saved per game type and CPU level (and for pass & play), shown on
the menu and after each game.

## Rules (simplified WPA)

- **8-ball:** open table after the break; first legal pot decides solids/stripes. Clear your group,
  then sink the 8. Sinking the 8 early, or scratching on it, loses. 8 on the break is respotted.
- **9-ball:** hit the lowest-numbered ball first; any legal pot keeps the turn. Pot the 9 legally
  (combos and breaks count) to win. Three fouls in a row loses.
- **Call pocket** (menu: Off / 8 & 9 only / Every shot), in every game on the regulation table (8-ball, 9-ball,
  Circuit Pool, Pocket Wars, Cutthroat): tap a pocket to call it (tap again to cancel). Circuit, Pocket Wars and
  Cutthroat have no money ball, so only *Every shot* applies there: a ball in the called pocket keeps your turn, anything
  else is slop (in Pocket Wars a slopped ball is spotted and scores nothing; one in the opponent's pocket still scores
  for them).
  *8 & 9 only*: the 8 (8-ball) or the 9 (9-ball) must go in the called pocket — an 8 in the wrong pocket
  loses, an uncalled 9 is respotted. *Every shot*: only one of your balls in the called pocket keeps your
  turn; anything else is slop (no foul, the opponent shoots, slopped balls stay down). The CPU calls too.
- **Fouls** (scratch, no ball hit, wrong ball first, no rail after contact, a ball jumped off the
  table, a scoop jump) give the opponent ball in hand anywhere.
- **After a scratch** (menu): *Ball in hand anywhere*, or *In the kitchen (bar rules)*: after a scratch (the cue ball
  in a pocket or off the table) the next player places it behind the head string and has to shoot out of the kitchen:
  a ball behind the line can't be hit first unless the cue ball crosses the line before touching it (the aim guide
  marks such a ball with a red X). Other fouls still give ball in hand anywhere. Applies to every regulation-table game. Legal jumps come off an elevated cue; digging under
  the cue ball to lift it is a foul. A miscue on its own isn't. Jumped object balls are respotted; jumping the 8
  off in 8-ball loses (except on the break).
- Not modelled: push-outs, break rail-count requirement.

## Bumper pool (menu → Game → Bumper)

Its own small table (47″ × 31½″) with a cup cut into the cloth at each end and twelve rubber bumpers: two
flanking each cup and eight in a cross in the middle, one arm running between the cups, with a small open space at
its centre. Each player has five balls — **red** (you / Player 1, shooting into the right-hand cup) or **white**
(shooting into the left one) — and there is **no cue ball**: you shoot your own balls. One of each set is the
**marked** ball, spotted in the other colour; it has to go first. Each cup is ringed in the colour of the player
who shoots into it, and the player cards show who has what left.

- **Picking a ball**: your turn starts with a ball picked for you (the marked one while it's up, then the one nearest
  your cup). Tap any of your others (dashed yellow rings) to shoot that one instead. Aim, spin, power and the 3D view
  work as usual; the cue is shorter.
- **The opening**: both marked balls go at the same moment, each banked off the cushion on that player's right
  (red the bottom one, white the top). You set your shot, then the CPU (or Player 2) sets theirs without seeing
  yours, and both are played together. One in: that player shoots first. Neither: whoever finished nearer their
  cup. Both: each puts another ball on their spot and you go again.
- **Your turn** carries on while you sink one of your own in the right cup.
- **Fouls** — one of your balls in your own cup, another of yours going in before the marked ball, a ball knocked
  off the table (it goes back in the middle of the bumpers) or a jump shot: your opponent drops two of their balls in
  by hand (their marked one first) and it's their turn.
- A ball of your opponent's that you knock in, in either cup, counts for them, no penalty.
- **Winning**: first to get all five down. Sinking your last ball in your own cup loses.
- The shot clock works as in the other games (running out is a foul; in the opening it just means no shot).
- The aim guide rings a ball dropping into your cup in gold and into your own cup in red.

**Bumper practice**: Bumper, Opponent → Practice. The bumper table on your own, no rules and no opening. Tap any
ball (red or white) to shoot it; the guide then shows the cup that ball's colour shoots for. Hold a ball to pick it
up and drag it anywhere. *Retry shot*, *Re-rack* and *Clear table* work as on the free table (Clear keeps the ball
you're shooting), and tapping a ball in the tray puts it back. When the ball you shot goes in, the nearest one left
is picked for you.

## Octagon bumper (menu → Game → Octagon)

Bumper pool on a big eight-sided table (70″ × 63″ of cloth, about 90″ × 83″ overall) with a cup on every side — one at
each end and one on each long side, each 5.1″ from the foot of its ramp, like the small table's from its cushion — and
**no cushions**: every side is a 45° felt ramp, 6″ high, rising straight off the cloth with a hard corner at its foot
(and hard creases where two ramps meet). A ball thumps into a ramp, loses the part of its speed going into it, hops a
little and rolls back down (so a ramp works like a dead cushion you can still bank off, and you can't roll a ball round
the outside of the table); straight in at more than about 2 m/s it goes over the top, onto a 4″ wooden ledge that tilts
outward, and off the table. The bumpers are the small
table's bumpers (same size, look and bounce); each cup has one either side of it, as far away as on the small table.

- **Players** (menu → PLAYERS): 2, 3 or 4, or **2 v 2**. Five balls each: **red** (left end), **white** (right end),
  **blue** (top), **yellow** (bottom). You shoot into the cup **across from you**; each cup is ringed in the colour of
  whoever shoots into it (grey if nobody does). Turns go round the table: red, blue, white, yellow.
- **Bumper layout** (menu → BUMPER LAYOUT, with a picture): *Scatter* (the small table's cross with bumpers scattered
  all round, 32 bumpers), *Chevrons* (arrow shapes that push balls out to the corner ramps, 28) or *Goal gates* (a gate
  in front of every cup — thread it, or curl in off the ramp behind it, 28). `core/Table.kt`, `octPosts`.
- **The opening**: every marked ball goes at the same moment. Each player sets their shot in turn (unseen), in any
  direction — off a bumper, up a ramp, anything but a jump shot — then they all go. A legal ball in: whoever's dropped
  first shoots first. Otherwise whoever finished nearest their cup. Then turns go round the table.
- **Your turn** carries on while you sink one of your own in your cup. Your marked ball has to go first.
- **Fouls** — one of your balls in a cup that isn't yours (it comes back out onto your starting spot: a wrong cup never
  counts as a ball down, so there's nothing to gain by dumping balls in the nearest cup), another of yours going in
  before the marked one, a ball off the table (it goes back in the middle of the cross) or a jump shot: **every other
  player** drops one of their balls in by hand (their marked one first) — in teams, each player on the other team —
  and play moves on round the table. A ball flying off a ramp is just physics, not a jump; only a raised cue's is.
- A ball of someone else's that you knock in, in any cup, counts for them, no penalty.
- **Winning**: the first player to get all five down. **2 v 2**: red & white against blue & yellow, partners across
  the table (so turns alternate between the teams). A player whose five are down shoots their partner's balls (into the
  partner's cup), and the team wins when all ten are down.
- **Practice** (Octagon, Opponent → Practice): the octagon table on your own with all four colours and the chosen
  layout, no rules, as in bumper practice.

## Circuit Pool (menu → Game → Circuit)

A hidden-objective game on the regulation table with all fifteen balls, racked in any order. At the start each player
is secretly dealt three of the fifteen numbers; the other nine are neutral. You see your own targets on your player
card (and dashed gold rings round them on the table); for your opponent you only see how many are down.

- **Any ball, any order**: you may hit any ball first. Sinking one of your targets or a neutral ball keeps you
  shooting. Sinking one of your opponent's counts for them and ends your turn (they shoot from where the cue ball
  stopped). Pocketed balls stay down.
- **Break**: from behind the head string. Legal if a ball goes in or four numbered balls reach a cushion. On a
  legal break any ball going in keeps you shooting; nothing in passes the turn. A soft (illegal) break is re-racked
  and the other player breaks. A scratch on the break gives ball in hand behind the line.
- **Fouls** — scratch, cue ball off the table, no ball hit, an object ball off the table (it's spotted on the foot
  spot) or a scoop jump: ball in hand anywhere for your opponent. Balls sunk on a foul still count for their owner.
- **Winning**: the moment your third target goes down — whoever sinks it. If one shot finishes both players, the
  shooter wins, unless it was a foul. The end-of-game panel shows everyone's targets.
- **Pass & play** keeps the targets secret: at every change of turn the table goes dark and says whose turn it is;
  that player taps to see their targets. Against the CPU yours are always on show.
- No called pockets (the menu's Call pocket setting doesn't apply). Rules from a sheet written for this app by
  Gemini; where it was silent (an illegal break, a time foul on the break) the app re-racks for the other player.

## Cutthroat (menu → Game → Cutthroat)

Three players on the regulation table with all fifteen balls (the 1 on the foot spot, the 6 and 11 on the back
corners). The balls come in three groups — **1–5, 6–10 and 11–15** — and your group is the one you want to keep on the
table: you're trying to sink the other two players' balls. Turns go Player 1 → 2 → 3 (You → CPU 1 → CPU 2).

- **Picking groups**: the table is open after the break. The first player to sink a ball on a legal shot (not the
  break) picks any group they like — a panel shows the three groups and how many of each are left; the CPU takes the
  one with most balls up. The next player to sink a ball picks one of the other two, and the third player gets the
  last. Each player's card then shows their five balls, dimmed as they go down.
- **Any ball, any order**: any ball may be hit first. Any ball down on a legal shot keeps you shooting — your own too
  ("cutting your own throat"). The break is from behind the head string; anything in keeps the breaker shooting.
- **Fouls** — scratch, cue ball off the table, no ball hit, an object ball off the table (it's spotted), no rail after
  contact or a scoop jump: the next player gets ball in hand anywhere, and each of the fouler's opponents gets their
  lowest pocketed ball back on the foot spot.
- **Out**: a player with none of their group left is out and their turns are skipped — until a foul by someone else
  brings one of their balls back. A player who never got a group is out as soon as every group nobody has is off the
  table (so sinking everything but your own five wins straight away); a group with nothing left on the table can't be
  picked.
- **Winning**: the last player with a ball on the table. If one shot clears everyone, the player whose last ball
  dropped last wins.
- No called pockets. Rules after the common open-table version of Cutthroat; fouls follow the rest of the app (ball in
  hand anywhere) with the usual Cutthroat penalty of a ball back for each opponent.

## Pocket Wars (menu → Game → Pocket Wars)

A fight over the six pockets, on the regulation table with all fifteen balls racked in any order. Every ball is worth
its number (120 points in all). Player 1 (you) is blue, player 2 (the CPU) orange; claimed pockets are ringed in their
owner's colour, and each player's card shows points, pockets and how many to go.

- **Claiming**: all six pockets start open, and any ball may be hit first. Sink a ball in an open pocket and you claim
  it; you score the ball and keep shooting. Sinking into one of your own pockets scores and keeps you shooting too.
- **Their pockets**: a ball you sink into your opponent's pocket scores for them, and your turn ends.
- **Bank to steal**: if that ball hit a rail before it dropped (a pocket's jaws don't count), you steal the pocket
  instead: it's yours, you score the ball and keep shooting.
- **Break**: balls sunk on the break score for the breaker but claim nothing; anything in keeps the breaker shooting.
- **Fouls** (scratch, cue ball off the table, no ball hit, an object ball off the table, no rail after contact, a scoop
  jump): the balls sunk on that shot are spotted back and your opponent gets ball in hand anywhere. Scratching into one
  of your own pockets also gives it up (it's open again).
- **Winning**: first to 61 points. If the table empties at 60–60, whoever owns more pockets wins; still level, whoever
  sank the last ball.

## Trick shots (menu → Game → Trick)

Two sets, picked under TRICK SHOTS in the menu: **Gap jumps** and **Classics**. Practice lists every level from both.

### Gap jumps

The table's second quarter — from the head string to the side pockets — is cut away. The cue ball starts in
the break area (the near section; drag it anywhere there) and has to be jumped across the 25″ gap to sink the
1-ball on the far section. A ball that rolls off either edge falls to the floor; one that comes down just short of
the far edge can catch the lip and bounce or roll onto the table, like a real cut edge. The far section has only its
two corner pockets at the far end (Bank job fills those in and has a hole cut in the cloth instead). There's no rock,
paper, scissors for trick shots: who goes first doesn't matter.

| Level | Rule (besides sinking the 1) | Bonus |
|---|---|---|
| Leap of faith | — | — |
| Off the rail | The cue ball must hit a cushion before the 1 | each extra cushion before the 1 |
| Picket fence | The cue ball can't touch the fence until it has landed on the far section | each fence ball sunk too |
| Bullseye | The 1 sits inside a ring whose side facing you is a solid wall of nine balls; the cue ball may touch only the 1 | each ring ball sunk too |
| Traffic jam | The 1 must be cut (at least 12°) and may not touch any other ball; the cue ball must hit the 1 first | none |
| Bank job | Start from either pen of balls (drag the cue ball into the top or bottom one; the pen moves with it) and jump out. A row of four runs along each long cushion with a ball behind it (the 1 at the top, the 10 at the bottom); the only pocket is a hole in the middle of the far section | each other ball sunk too |

Play the computer or pass & play. Players take turns until each has made it (8 attempts each at most).
Your score on a level is the attempt you made it on minus your bonus — so making it on your 4th shot with an
extra ball counts as 3. Lower score wins the level; an equal score goes to whoever earned more bonus (the tie
goes to the runner). A player who can no longer catch up stops early. Most levels won takes the match; a tie
goes to sudden death. The balls reset after every attempt.

### Classics

Famous trick shots on an ordinary 9 ft table (`core/Classics.kt`), easiest first. The cue ball goes in a small marked
area; most are about getting the 1 in round, over or between other balls.

| Level | What it asks |
|---|---|
| Line of four | Four frozen balls in a row, pointing just off the corner: hit the 4 at the back and sink the 1 at the front |
| Frozen combo | The 2 and the 1 frozen, lined up 7° off the corner: hit the 2 first and throw the 1 in |
| Railroad | The 2 and the 1 frozen to the top cushion: sink both |
| Bank it | The 1 has to hit a cushion before it drops |
| Eye of the needle | Send the 1 between two balls with barely a ball's width between them, touching neither |
| Dodgeball | A wall of three balls stands square across the line to the 1: the cue ball may touch only the 1 (kick, massé or jump) |
| Over the wall | A solid row of seven: over it or round it, touching only the 1 |
| Snookered | The cue ball sits in a horseshoe of balls: it has to hit a cushion before the 1 (extra cushions take a shot off) |

Scoring and turns are the same as the gap jumps. Every classic has known working shots found with the physics engine
(a search harness fired 30,000 random shots at each; the unit test checks they still go in).

**Practice a trick shot**: Trick, Opponent → Practice, then pick the level. Unlimited attempts on your
own, with a made/attempts count; ◀ Level / Level ▶ switch levels and Reset puts the balls back. Every level was checked with the physics engine
(`core/Trick.kt` holds known working shots, found by a search with the physics engine and redone when the strike
model changed, and a unit test makes sure each level still has one).

## Physics

`core/World.kt` integrates each ball's linear velocity and full 3D spin:

- Cloth: sliding friction at the contact point until natural roll, then rolling resistance — this is
  what produces stun, draw and follow. Side spin decays separately.
- Cue strike: the cue is a straight stick at the chosen elevation and the tip meets the ball at the spot set on the
  spin ball (seen down the cue). Inside the tip's friction limit (half a radius off centre) the push is along the cue;
  beyond it the tip slips and only the normal push plus what friction holds gets through (a miscue). Spin is r × J,
  so striking 0.4R above centre gives exact natural roll, and side spin with a raised cue tilts the spin axis (masse).
  A push with a downward part is bounced off the slate (a legal jump, with slate friction trading speed for roll); one
  with an upward part — only possible from under the ball — lifts it directly: a scoop.
- Ball–ball: restitution plus surface friction, so cut-induced and spin-induced throw happen naturally.
- Cushions: friction at the nose, so running/reverse english changes rebound angles.
- Exact time-of-impact rewind on every collision, so cut angles match the aim guide.
- Jump shots: an elevated cue drives the ball into the slate, which bounces it up; airborne balls
  fly ballistically, collide in 3D, and pass over pockets and balls. Spin is kept in the air and the cloth grips the
  ball on every landing, so draw, follow and english act when it comes down. A ball higher than the cushions flies
  over them and off the table.
- Octagon ramps (`Table.useOctagon`, `World.octMove`): the ramps are one profile (flat, 45° slope, ledge) measured
  outward from the octagon's nearest side, and the ball's resting height is worked out from it once
  (the highest point it would touch within its radius). On the flat the cloth model above runs as usual. On a ramp the
  ball rolls in the slope's plane: gravity pulls it back at 5/7 g sin θ (the rolling ball's value) and its spin is the
  natural roll for that surface. A hard corner (the ramp's foot, the creases between ramps: the surface turning more
  than 10° in one step) is an impact: the speed going into the new face is lost and the ball comes off it at 0.3 of
  that, a little hop. Over a crest it's too fast for it leaves the surface and flies, and it lands (bouncing if it comes down hard) wherever it meets it again. Past the
  ledge there's nothing under it: off the table. Rolling up a ramp and back counts like a cushion for the aim guide.
- Bumpers (bumper pool, `Table.useBumper`): round rubber posts with the same contact model as the cushions
  (exact time-of-impact, friction at the contact point) but livelier. A ball in the air higher than a bumper
  passes over it. `World.cue` is the ball the cue strikes: always the cue ball except in bumper pool.
- Regulation tables (`TableSize`): 7 ft 78″ × 39″, 8 ft 88″ × 44″, 9 ft 100″ × 50″ playing surface; 2¼″ balls, 4.5″
  corner and 5″ side pocket mouths on all of them. `Table.useStandard(size)` switches; `P.L` / `P.W` follow it.

Tunable constants are in the `P` object in `core/Table.kt`.

## Computer opponent (`core/Ai.kt`)

For each legal ball × pocket it builds the ghost-ball shot, rejects blocked paths and jaw-clipping
angles, and estimates make-probability from cut angle, distances and its own aim error. The best
candidates are run through the real physics engine (several speeds and spins at higher levels) and
scored on whether they pot and how good the next shot is. With nothing makeable it plays a safety
(level 5+) or sweeps the rails for a kick. Ball in hand is placed for the easiest shot.

Bumper pool: for each ball it may shoot it tries straight at its cup, banked off each cushion, and fanned out
either side (the bumpers deflect a lot) at a few speeds, all through the physics engine, scored on what goes in,
fouls and how the balls are left (its own near its cup, the opponent's far from theirs). From level 4 it checks its
best few against its own aim and speed error; from level 6 it keeps refining the best while there's time. In the
opening it banks off its right-hand cushion. Headless, L10 beat L1 10/10 and L7 beat L3 10/10.

Trick shots: it starts from the known working shots for the level, searches for more with the physics engine,
and picks the one that still goes in most often with its own aim and speed error. Headless, a level-1 CPU made
2–10% of attempts, level 5 10–45%, and level 10 about 85% or better on every level.

Levels 8–10 play defence: what they leave the next player counts for more (×1.5 to ×2.5), a pot is judged on where the
balls end up if it misses (two deliberately missed versions are run through the physics), they look for a safety
whenever the best pot isn't a good one, and a safety has to hold up with their own aim and speed error. Headless, 24
games each, a defensive L10 against the same level without it: 18–6 at 9-ball, 13–11 at 8-ball, playing about 1.6 times as many safeties.

| Level | Aim error (σ) | Plays position | Spin | Safeties |
|---|---|---|---|---|
| 1 | 1.8° | – | – | – |
| 5 | 0.48° | yes | – | yes |
| 7 | 0.25° | yes | follow/draw | yes |
| 10 | 0.09° | strongly | follow/draw/english | yes |

Head-to-head (headless, 10 games each): L10 beat L1 20/20; L7 beat L4 16/20.

Pocket Wars: it scores each shot it simulates on the points it gains and gives away and the pockets that change hands
(points count for more once either player is near 61), and only goes for balls into its own or open pockets. From
level 5 it also looks at banks into your pockets to steal them. Headless: L10 beat L1 10/10, L7 beat L4 9/10.

Octagon bumper: for each ball it may shoot it tries straight at its cup and at the cup's mirror image across each of
the eight ramps (a ball rolling up a ramp and back comes off it roughly like a cushion), fanned out either side, at a
few speeds, through the physics engine; it scores each on its own (and its partner's) balls down, everyone else's (an
opponent close to finishing counts more; a foul hands each opponent a ball), and how the balls are left for whoever
shoots next. From level 4 it checks its best few against its own aim and speed error, and from level 6 it keeps
refining the best. Headless, two players: L10 beat L1 6/6, L7 beat L4 7/8.

Circuit Pool: it never looks at your targets. From the counts both players can see after every shot it works out
every set of three balls you could have (`core/Circuit.kt`, `CircuitBelief`), and scores each shot it simulates
averaged over all of them: its own targets are worth the most, a probably-neutral ball keeps its turn, and a ball
that may be yours is avoided (strongly once you're on your last one). Its copy of the table holds a guess drawn from
those sets in place of your real targets. Games are short and luck plays a part (neutral balls keep a good player
running): headless, L10 beat L1 9/10 and L7 beat L4 5/10.

Cutthroat: it goes for the other players' balls (the one with most left a little more), stays off its own, and scores
each shot it simulates on the balls each group lost, counted against whoever owns the group after the shot (so sinking
a ball on an open table and then picking that group counts against it), then on who shoots next. On an open table it
picks the group with the most balls still up. Against you there are two of them, at the same level.
Headless, one strong CPU against two weaker ones (a third each by chance): L10 beat two L1s 10/10, L7 beat two L4s 8/10.

## Layout

```
app/src/main/java/com/poolgame/app/
  core/   Table.kt (constants, sizes, rails)    World.kt (physics)
          Match.kt (racks + rules, bumper pool and octagon bumper, Circuit Pool, Pocket Wars, Cutthroat)  Ai.kt (computer player)
          Circuit.kt (Circuit Pool's secret targets, and what a player can work out about the opponent's)
          PocketWars.kt (Pocket Wars: who owns each pocket, and the points)
          Cutthroat.kt (Cutthroat: each player's group, and the open-table pick)
          BumperColors.kt (bumper pool's ball, spot and cup-ring colours, for both tables)
          Crowd.kt (synthesized crowd "aww", shared with the web version)
          Trick.kt (trick-shot levels, match state, known shots)   Classics.kt (the classic trick shots)
          Cough.kt (synthesized cough, the foul sound until you record one)
          Voice.kt (cleans up your recorded foul voice)
          Tutorial.kt (tutorial steps, table setup, demo finger)
          Pov.kt (3D view: orbit camera and its gestures, the table and balls as projected, depth-sorted polygons)
          ControlsGuide.kt (screen-guide text and the screen map's layout)
  ui/     MenuActivity  GameActivity  GameView (render + input + turn flow, tutorial overlay)
          ControlsActivity (the screen guide and its map)
          OctagonPainter (the octagon table from above, and the menu's preview of it)
          BallRenderer (3D ball shading)        Sfx (synthesized sounds)
          VoiceRecorder (records your foul voice)
app/src/test/  EngineTest.kt  OctagonTest.kt (the octagon table: ramps, rules, teams, the CPU)
```
