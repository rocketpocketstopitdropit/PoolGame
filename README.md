# Pool — 8-Ball, 9-Ball & Trick Shots for Android

A landscape pool game with real cue-ball spin, 8-ball and 9-ball rules, a trick-shot mode on a
table with a gap cut out of it, a computer opponent with ten skill levels, and pass-and-play for two people. Pure Kotlin, no third-party libraries,
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

## How to play

| Control | What it does |
|---|---|
| Drag on the table | Aim the cue toward your finger |
| **Fine aim** wheel (right, below spin) | Wide roller: drag up/down to nudge the aim. It accelerates with finger speed — slow drags are ~3× finer than the base speed, fast swipes up to 25× coarser, so a flick swings the cue a long way. Base speed is adjustable 1–10 in the menu (5 ≈ 0.01° per dp at ~150 dp/s, each step 1.6×) |
| **JUMP** slider (vertical, left of the spin ball) | Slide up to raise the cue for a jump shot (0–60°). Around 45–55° with a firm stroke hops the cue ball over a blocker |
| **Spin** cue ball (right) | Touch where the tip strikes: top = follow, bottom = draw, sides = english. Tap the centre to reset |
| **Power** bar (left) | Pull down to set power, release to shoot. Slide back to the top to cancel. While holding it, you can use another finger on the fine-aim wheel, the table, spin or cue angle |
| Ball in hand | Drag the cue ball (shaded area only before the break) |
| Ball tray (left, next to the power bar) | Pocketed balls roll down it and line up bottom-to-top in the order they were made. Respotted balls leave it |
| ☰ / Back | Pause menu: resume, restart rack, quit |

The aim guide is computed by running the real physics for the first part of the shot, so it shows
exactly where the balls will go — including throw, follow/draw and jumps (yellow = airborne). A red
ghost ball with an X means that ball is not a legal first contact; a red ring means a scratch.
There is no random error on your shots. **Shot prediction** can be turned off (menu or pause menu); then only a
straight aim line from the cue ball to the cushion is shown. Pocket holes are drawn exactly the size of the
drop zone with a bright ring: once a ball's centre crosses the ring it falls.

**Who breaks**: every new game, rematch and restart starts with rock, paper, scissors (fist, flat hand,
sideways peace sign). Against the CPU you pick and it picks at random; in pass & play each player taps their own
side (the pick stays hidden until both are in). Ties go again; the winner breaks. The two hands point at each
other: the left one is the mirror image of the right one. Your rock, paper, scissors wins and losses, the longest
game (most throws, ties included) and the average number of throws to a winner are kept with your record.

**Crowd**: scratching the cue ball or knocking any ball off the table gets a synthesized crowd "awww".
A table foul — hitting nothing, hitting the wrong ball first, or no rail and nothing potted — plays your
**foul voice**: record it in the menu (FOUL VOICE → Record, up to 3 seconds; Play to check, Delete to remove).
It's trimmed and levelled automatically and kept on that device. Until you record one, a synthesized cough plays.

**Pause & resume**: ☰ → *Main menu* keeps the game; the menu shows *Resume*. The game is also saved if you switch
apps or close it. A shot that was still rolling is replayed from the start of that turn.

**Shot clock** (menu): optional 10–120 s per shot for human players; running out is a foul. With
ball in hand the clock waits until you aim (table or fine-aim wheel) or touch the power bar.

**Practice** (menu → Game → Practice): just you, a cue ball and red/yellow balls (plus the black), no
numbers, no rules. Tap a ball to aim at it; hold a ball for a moment to pick it up and drag it
anywhere — including the cue ball — to set up trick shots. *Retry shot* puts every ball back where
it was before your last shot, *Re-rack* starts over, *Clear table* removes the object balls, and
tapping a ball in the ball tray (left) brings it back.
**Record**: wins and losses are saved per game type and CPU level (and for pass & play), shown on
the menu and after each game.

## Rules (simplified WPA)

- **8-ball:** open table after the break; first legal pot decides solids/stripes. Clear your group,
  then sink the 8. Sinking the 8 early, or scratching on it, loses. 8 on the break is respotted.
- **9-ball:** hit the lowest-numbered ball first; any legal pot keeps the turn. Pot the 9 legally
  (combos and breaks count) to win. Three fouls in a row loses.
- **Call pocket** (menu: Off / 8 & 9 only / Every shot): tap a pocket to call it (tap again to cancel).
  *8 & 9 only*: the 8 (8-ball) or the 9 (9-ball) must go in the called pocket — an 8 in the wrong pocket
  loses, an uncalled 9 is respotted. *Every shot*: only one of your balls in the called pocket keeps your
  turn; anything else is slop (no foul, the opponent shoots, slopped balls stay down). The CPU calls too.
- **Fouls** (scratch, no ball hit, wrong ball first, no rail after contact, a ball jumped off the
  table) give the opponent ball in hand anywhere. Jumped object balls are respotted; jumping the 8
  off in 8-ball loses (except on the break).
- Not modelled: push-outs, break rail-count requirement.

## Trick shots (menu → Game → Trick)

The table's second quarter — from the head string to the side pockets — is cut away. The cue ball starts in
the break area (the near section; drag it anywhere there) and has to be jumped across the 25″ gap to sink the
1-ball on the far section. A ball that rolls off either edge falls to the floor. The far section has only its two
corner pockets at the far end (one level adds a hole cut into the cloth).

| Level | Rule (besides sinking the 1) | Bonus |
|---|---|---|
| Leap of faith | — | — |
| Off the rail | The cue ball must hit a cushion before the 1 | each extra cushion before the 1 |
| Picket fence | The cue ball can't touch the fence until it has landed on the far section | each fence ball sunk too |
| Bullseye | The cue ball may touch only the 1, not the ring around it | each ring ball sunk too |
| Traffic jam | The 1 must be cut (at least 12°) and may not touch any other ball; the cue ball must hit the 1 first | none |
| Bank job | The 1 sits under a row of four guard balls; sink it in the hole in the cloth or a corner | each guard ball sunk too |

Play the computer or pass & play. Players take turns until each has made it (8 attempts each at most).
Your score on a level is the attempt you made it on minus your bonus — so making it on your 4th shot with an
extra ball counts as 3. Lower score wins the level; an equal score goes to whoever earned more bonus (the tie
goes to the runner). A player who can no longer catch up stops early. Most levels won takes the match; a tie
goes to sudden death. The balls reset after every attempt. Every level was checked with the physics engine
(`core/Trick.kt` holds known working shots, and a unit test makes sure each level still has one).

## Physics

`core/World.kt` integrates each ball's linear velocity and full 3D spin:

- Cloth: sliding friction at the contact point until natural roll, then rolling resistance — this is
  what produces stun, draw and follow. Side spin decays separately.
- Cue strike: tip offset → angular impulse (striking 0.4R above centre gives exact natural roll).
- Ball–ball: restitution plus surface friction, so cut-induced and spin-induced throw happen naturally.
- Cushions: friction at the nose, so running/reverse english changes rebound angles.
- Exact time-of-impact rewind on every collision, so cut angles match the aim guide.
- Jump shots: an elevated cue drives the ball into the slate, which bounces it up; airborne balls
  fly ballistically, collide in 3D, and pass over pockets and balls. A ball higher than the cushions
  flies over them and off the table.
- Regulation 9-ft table (100″ × 50″), 2¼″ balls, 4.5″ corner and 5″ side pocket mouths.

Tunable constants are in the `P` object in `core/Table.kt`.

## Computer opponent (`core/Ai.kt`)

For each legal ball × pocket it builds the ghost-ball shot, rejects blocked paths and jaw-clipping
angles, and estimates make-probability from cut angle, distances and its own aim error. The best
candidates are run through the real physics engine (several speeds and spins at higher levels) and
scored on whether they pot and how good the next shot is. With nothing makeable it plays a safety
(level 5+) or sweeps the rails for a kick. Ball in hand is placed for the easiest shot.

Trick shots: it starts from the known working shots for the level, searches for more with the physics engine,
and picks the one that still goes in most often with its own aim and speed error. Headless, a level-1 CPU made
2–10% of attempts, level 5 10–45%, and level 10 about 85% or better on every level.

| Level | Aim error (σ) | Plays position | Spin | Safeties |
|---|---|---|---|---|
| 1 | 1.8° | – | – | – |
| 5 | 0.48° | yes | – | yes |
| 7 | 0.25° | yes | follow/draw | yes |
| 10 | 0.09° | strongly | follow/draw/english | yes |

Head-to-head (headless, 10 games each): L10 beat L1 20/20; L7 beat L4 16/20.

## Layout

```
app/src/main/java/com/poolgame/app/
  core/   Table.kt (constants, rails, pockets)  World.kt (physics)
          Match.kt (racks + rules)              Ai.kt (computer player)
          Crowd.kt (synthesized crowd "aww", shared with the web version)
          Trick.kt (trick-shot levels, match state, known shots)
          Cough.kt (synthesized cough, the foul sound until you record one)
          Voice.kt (cleans up your recorded foul voice)
  ui/     MenuActivity  GameActivity  GameView (render + input + turn flow)
          BallRenderer (3D ball shading)        Sfx (synthesized sounds)
          VoiceRecorder (records your foul voice)
app/src/test/  EngineTest.kt
```
