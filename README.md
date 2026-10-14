# Pool — 8-Ball & 9-Ball for Android

A landscape pool game with real cue-ball spin, 8-ball and 9-ball rules, a computer opponent
with ten skill levels, and pass-and-play for two people. Pure Kotlin, no third-party libraries,
no image or sound assets (balls are rendered per-pixel and sounds are synthesized).

## Build & run

1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync (AGP 8.9.1, Kotlin 2.1.21, Gradle 8.14.3 via the wrapper; compileSdk 35, minSdk 26).
3. Run the `app` configuration on a phone or emulator.

Command line: `./gradlew installDebug` (APK at `app/build/outputs/apk/debug/`).
Engine unit tests: `./gradlew test`.

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
| **Fine aim** wheel (right) | Nudge the aim ~0.01° per dp of finger travel |
| **Cue angle** slider (right) | Raise the cue for a jump shot (0–60°). Around 45–55° with a firm stroke hops the cue ball over a blocker |
| **Spin** cue ball (right) | Touch where the tip strikes: top = follow, bottom = draw, sides = english. Tap the centre to reset |
| **Power** bar (left) | Pull down to set power, release to shoot. Slide back to the top to cancel. While holding it, you can use another finger on the fine-aim wheel, the table, spin or cue angle |
| Ball in hand | Drag the cue ball (shaded area only before the break) |
| ☰ / Back | Pause menu: resume, restart rack, quit |

The aim guide is computed by running the real physics for the first part of the shot, so it shows
exactly where the balls will go — including throw, follow/draw and jumps (yellow = airborne). A red
ghost ball with an X means that ball is not a legal first contact; a red ring means a scratch.
There is no random error on your shots.

**Shot clock** (menu): optional 10–120 s per shot for human players; running out is a foul.
**Record**: wins and losses are saved per game type and CPU level (and for pass & play), shown on
the menu and after each game.

## Rules (simplified WPA)

- **8-ball:** open table after the break; first legal pot decides solids/stripes. Clear your group,
  then sink the 8. Sinking the 8 early, or scratching on it, loses. 8 on the break is respotted.
- **9-ball:** hit the lowest-numbered ball first; any legal pot keeps the turn. Pot the 9 legally
  (combos and breaks count) to win. Three fouls in a row loses.
- **Fouls** (scratch, no ball hit, wrong ball first, no rail after contact) give the opponent ball in hand anywhere.
- Not modelled: called pockets, push-outs, jumped balls, break rail-count requirement.

## Physics

`core/World.kt` integrates each ball's linear velocity and full 3D spin:

- Cloth: sliding friction at the contact point until natural roll, then rolling resistance — this is
  what produces stun, draw and follow. Side spin decays separately.
- Cue strike: tip offset → angular impulse (striking 0.4R above centre gives exact natural roll).
- Ball–ball: restitution plus surface friction, so cut-induced and spin-induced throw happen naturally.
- Cushions: friction at the nose, so running/reverse english changes rebound angles.
- Exact time-of-impact rewind on every collision, so cut angles match the aim guide.
- Jump shots: an elevated cue drives the ball into the slate, which bounces it up; airborne balls
  fly ballistically, collide in 3D, and pass over pockets and balls.
- Regulation 9-ft table (100″ × 50″), 2¼″ balls, 4.5″ corner and 5″ side pocket mouths.

Tunable constants are in the `P` object in `core/Table.kt`.

## Computer opponent (`core/Ai.kt`)

For each legal ball × pocket it builds the ghost-ball shot, rejects blocked paths and jaw-clipping
angles, and estimates make-probability from cut angle, distances and its own aim error. The best
candidates are run through the real physics engine (several speeds and spins at higher levels) and
scored on whether they pot and how good the next shot is. With nothing makeable it plays a safety
(level 5+) or sweeps the rails for a kick. Ball in hand is placed for the easiest shot.

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
  ui/     MenuActivity  GameActivity  GameView (render + input + turn flow)
          BallRenderer (3D ball shading)        Sfx (synthesized sounds)
app/src/test/  EngineTest.kt
```
