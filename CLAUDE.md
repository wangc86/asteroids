# Asteroids — ClojureScript, in the browser

A remake of the classic Atari Asteroids (1979), written in ClojureScript and
running on an HTML5 canvas.

- Repo: https://github.com/wangc86/asteroids
- License: GPL-3.0
- Local path: `C:\Users\chaot\code\asteroids`

## Technical decisions (agreed 2026-07-29)

| Topic | Decision | Why |
|---|---|---|
| Language | ClojureScript | chosen by the user |
| Build tool | shadow-cljs | hot reload preserves game state, npm integration, friendly error messages |
| Rendering | Canvas 2D (`moveTo`/`lineTo`/`stroke`) | the original is vector-display line art, a natural fit |
| **No** Reagent / re-frame | — | a canvas game needs no React DOM diffing; the HUD is drawn straight onto the canvas |
| Game loop | `requestAnimationFrame` + delta time | |
| Input | `keydown`/`keyup` into a set | avoids the OS key-repeat delay |
| Audio | Web Audio API, synthesised in code | the original's heartbeat and thruster noise need no audio files |
| Scope | **follow the original closely** | three asteroid tiers, large and small UFOs, hyperspace, heartbeat audio, level progression |

Follow the original by default, and **write down every departure from it** (see
"Departures from the original" below). "It plays better this way" is a fine
reason to deviate; leaving the deviation undocumented is not, because the next
person cannot tell a deliberate change from a bug.

## Core architecture

The game state is one immutable map, advanced each frame by pure functions.
The only side effect is drawing:

```clojure
(defn tick  [state dt inputs] ...)  ; pure → new state, unit testable
(defn draw! [ctx state]      ...)   ; the only side effect
```

All-pairs O(n²) collision detection is fine to start with (at n≈40 that is only
~800 comparisons per frame) — **do not optimise prematurely**. Avoid string work
such as `str` / `pr-str` inside the render loop.

**The RNG state lives in the state map** (`:seed`, xorshift32). Anything that
needs randomness — spawning asteroids, splitting them, UFO entry timing — is
written as `(f seed ...) → [result new-seed]`, which is what lets `tick` stay
pure without ever touching `js/Math.random`. `(initial-state seed)` replays the
same game exactly for a given seed, and the tests depend on that.
**Never call `js/Math.random` anywhere under `tick`.**

## Development environment

- Temurin JDK 21.0.11 (the ClojureScript compiler needs a JVM)
- Node.js v24.18.0 / npm 11.16.0

## Build commands

```bash
npm install                    # install dependencies
npx shadow-cljs watch app      # dev mode + hot reload → http://localhost:8080
npm test                       # unit tests under node (shadow-cljs compile test)
npx shadow-cljs release app    # production build into public/js
```

- The shadow-cljs dashboard runs at http://localhost:9630 in dev mode
- Build output `public/js/` and `out/` are not version controlled; CI builds
  them (see Deployment below)
- **Restart the server after changing `:source-paths` in `shadow-cljs.edn`**
  (`npx shadow-cljs stop`). A long-running server does not re-read that setting,
  and the symptom is tests that exist but report `Ran 0 tests`
- To check a release build locally, stop the watcher first — it would overwrite
  `public/js` with a dev build. `npx shadow-cljs stop`, then
  `npx shadow-cljs release app`, then `npx shadow-cljs server` to serve
  `public/` as static files

## Deployment (milestone 7)

`.github/workflows/deploy.yml` builds and publishes to GitHub Pages on every
push to `main`. **`npm test` runs first and a red suite blocks the deploy.**

Because build output is not committed, Pages is built in CI rather than served
from a branch, so the repository's Pages source must be set to **GitHub Actions**
(Settings → Pages). Serving from a branch would not work without committing
`public/js`.

- The site is served from a sub-path (`/asteroids/`), so every asset reference
  must stay relative. `index.html` uses `js/main.js` and `shadow-cljs.edn` uses
  `:asset-path "js"` — **do not make either absolute**
- `public/.nojekyll` is there so Pages never runs the artifact through Jekyll
- The release build is one self-contained `main.js` under advanced optimisation.
  Namespaces are munged, so `asteroids.game` and friends are not reachable from
  the console the way they are in dev

## Project layout

```
public/index.html             page shell, chooser/rotate overlays, canvas CSS (4:3)
src/asteroids/game.cljs       pure logic: constants, RNG, spawning, tick, collisions
src/asteroids/mode.cljs       pure logic: which control scheme to run
src/asteroids/control.cljs    pure logic: virtual stick to action keywords
src/asteroids/core.cljs       side effects: draw!, keyboard, canvas, rAF loop, page shell
src/asteroids/touch.cljs      side effects: pointer events for the touch strips
src/asteroids/sound.cljs      side effects: Web Audio synthesis
test/asteroids/               unit tests for the pure namespaces
shadow-cljs.edn               build config (:app → public, :test → node)
```

**Layering rule: `game` may not touch any browser API** — no `document`, no
canvas, no atoms, only pure functions. That is what lets it be tested directly
under node. The dependency direction is one-way, `core → game`; Clojure forbids
circular requires, so keep that direction when adding features.

- `game/tick`: `(tick state dt inputs)`, where inputs is a set of action keywords
- `core/draw!`: the only drawing side effect
- `core/frame!`: the rAF loop, feeding `@keys-down` to `tick` and the result to `draw!`
- Game coordinates are fixed at 1024×768 logical units; `core/ensure-size!`
  matches the canvas buffer to the displayed size each frame and scales the
  context, so window size and devicePixelRatio never affect gameplay numbers
- `core`'s `state` / `started?` are `defonce`, so neither the game state nor the
  loop restarts on hot reload

`asteroids.sound` is a leaf like `game`: `core` calls it, it calls nothing back.
If the drawing code passes ~150 lines, split out `asteroids.render`.

## Tests

`npm test` runs everything under `test/` on node — no browser needed. Any
namespace matching `-test$` is picked up automatically.

The tests lean on the reproducibility of `(initial-state seed)`: a fixed seed
plus a fixed input sequence gives a fixed result. Their `step` / `run` helpers
advance a fixed number of seconds at 60 fps, so a handling spec like "holding
right for one second turns exactly 200 degrees" can be written directly as an
assertion. **When changing behaviour in `game`, update or add tests with it.**

## Handling parameters (settled by the user's playtest in milestone 2, 2026-07-29)

```clojure
rotate-speed 200   ; degrees/second
thrust       340   ; px/second²
drag         0.25  ; per-second exponential velocity decay
max-speed    540   ; px/second
```

**Do not change these four values without a playtest by the user.** Drag uses
`v *= exp(-drag·dt)` rather than a fixed per-frame multiplier, which is what
decouples the handling from the frame rate.

Touch play overrides `thrust` alone, with `control/thrust` (260, settled by the
user's playtest on a phone — **treat it like the four above**). Everything else
is shared. The feel parameters of the stick itself — `stick-max`, `dead-zone`,
`thrust-threshold`, `thrust-align` — are the user's call in the same way.

Controls: `←` `→` turn, `↑` thrust (or `A` / `D` / `W`), `space` to fire and to
restart after game over, `M` to mute.

## Asteroids (milestone 3)

Three tiers with radii 42 / 22 / 11; drift speed rises as the tier gets smaller.
The outline is a 12-vertex polygon whose vertex radii are each jittered between
0.72 and 1.12 of the base radius. **The vertex coordinates are computed at spawn
time and stored in `:points`**, so the render loop does no trigonometry.

**Asteroids do not rotate** — the original only translates them. Changing that
would be a deliberate departure from the original.

## Shooting and collisions (milestone 4)

- Four bullets on screen at most, and **one shot per press** (`:fire-held?`
  remembers last frame); holding the key does not auto-fire
- Bullet speed 620 px/s, life 1.15 s, and **the ship's velocity is not added**:
  at the top speed of 540 you can very nearly catch your own shots. That is the
  original's quirk; if it plays badly, adding `(:vx ship)` / `(:vy ship)` inside
  `fire-bullet` gives the other variant
- Collisions use a **circle approximation** (the asteroid's base radius), not a
  point-in-polygon test
- Distances go through `wrap-delta` for the shortest path in a wrapping world,
  so something hugging the left edge correctly hits something hugging the right
- Large → two medium → two small each → gone; bullet and asteroid destroy each other

## Game rules (milestone 5)

Scoring follows the original: large 20, medium 50, small 100, and an extra life
every 10000 points. Lives start at 3. Asteroid counts per level are 4, 6, 8, 10,
capped at 11 (`asteroids-for-level`).

The state machine lives in `:phase`, and every phase shares one `:timer`
countdown:

| phase | meaning | leaves when |
|---|---|---|
| `:playing` | normal play | hit → `:dead` / `:game-over`; field cleared → `:next-level` |
| `:dead` | the blank after an explosion | `:timer` reaches zero **and** the respawn point is clear |
| `:next-level` | between-wave pause | `:timer` reaches zero |
| `:game-over` | waiting for a restart | space is pressed |

- During `:dead` and `:game-over` the ship is uncontrollable, undrawn and unable
  to fire, while asteroids and bullets carry on as normal
- **A respawn waits for a clear centre** (`respawn-clear-radius`, 90px),
  otherwise the ship dies the moment it appears
- Every appearance (new game, respawn, new level) grants `invuln-time` seconds
  of invulnerability, shown as the ship blinking 4 times a second
- Colliding with an asteroid **leaves the asteroid untouched**; only the ship
  loses a life
- `:fire-held?` is updated at the very end of `tick`, so firing and restarting
  both see the same key edge

The HUD is drawn straight onto the canvas: score top left, lives as little ship
icons. The score's `(str n)` is cached and **only recomputed when the score
changes**, so the render loop does no string work.

**`core/measure-hud!` adapts the HUD to the device**, from the measured layout
rather than a guessed constant, and only when the canvas is resized:

- `:inset` — how far the left touch strip reaches over the playfield, in world
  units. The strips have a 120px floor, so on a 16:9 phone they overlap by ~37px
  and on a 4:3 tablet, which has no letterbox at all, by the full 120px. Without
  this the score sits underneath one and is invisible
- `:scale` — the world is a fixed 1024×768 however small the canvas is, so a
  30px score renders at 15 CSS px on a phone. This grows the HUD until it is at
  least `hud-min-css-font` on screen, and is 1.0 on any desktop-sized canvas, so
  keyboard play looks exactly as it did
- Both font strings are built there too, keeping `str` out of the render loop

> **Do not test a strip's visibility with `offsetParent`.** The strips are
> `position: fixed`, and `offsetParent` is null for fixed elements whether they
> are shown or not; this silently reported "no strip" and left the inset at zero.
> Measure `getBoundingClientRect` and check for a zero width instead.

> The score currently uses `fillText` with a monospace font. The original used a
> vector font; hand-drawing the digit strokes would get closer, but that is a
> separate piece of work and does not affect the rules.

## UFOs and audio (milestone 6)

**Sound is driven by events, not by calls from `game`.** `tick` appends keywords
to `:events` (`:fire`, `:bang-large`, `:beat-a`, `:ship-explode`, …) and `core`
hands them to `asteroids.sound` after drawing. `:events` is cleared at the top of
every `tick`, so it only ever holds the current frame. That keeps `tick` pure and
makes "firing makes a sound" an ordinary assertion. **Never call `sound` from
`game`.** Note that tests spanning several frames must accumulate `:events`
themselves — the helper `events-during` does this.

Two sounds are continuous rather than events, so `core` drives them from state
each frame: `sound/thruster!` and `sound/saucer!`.

**Web Audio needs a user gesture.** `sound/init!` is called from the `keydown`
handler, not on page load; before the first key press there is no AudioContext
at all. `M` toggles mute.

UFO behaviour:

- Enters from the left or right edge, crosses horizontally, and **leaves** at the
  far side — it wraps vertically but not horizontally, so `move-ufo` returning
  `nil` is a normal result
- Zig-zags: every `ufo-turn-interval` it picks up, down or level again
- The large saucer fires in random directions; the small one aims at the ship
  with an error that shrinks from `ufo-aim-spread-max` to `ufo-aim-spread-min`
  as the score approaches `ufo-aim-tighten-by`
- Small saucers get commoner with score and are the only kind at and above
  `small-ufo-only-score`
- Saucer shots break asteroids too, but **only the player's shots score**
  (bullets carry `:from`)
- 200 points for the large saucer, 1000 for the small one
- A saucer dies on contact with an asteroid or the ship, and a level will not end
  while one is still on screen
- **The flight path is a Z, and that outranks the avoidance success rate.**
  Evasion may only choose climb, level or dive (`ufo-evade-options`) at the same
  `ufo-vertical-ratio` the aimless zig-zag uses, and holds that heading for
  `ufo-evade-hold` seconds before rethinking. A saucer free to pick any angle
  every frame flies a smooth curve, which is not what the original looks like.
  This costs roughly 5–7% of crossings and is meant to

Avoidance works off one number, `gap-on-course`: the tightest hull-to-hull gap
the saucer would face anywhere within `ufo-evade-horizon` on a given heading,
computed from *relative* motion via `closest-approach`. A lane-shaped check
against present positions is not enough — it misses the rock drifting into the
path. That one number both detects trouble (gap below `ufo-clearance`) and picks
the way out (`evade` scores each of the three headings against the whole field
and takes the roomiest).

Two things that mattered more than they look:

- **Scoring every option against the whole field**, rather than steering away
  from the nearest rock. Steering blindly away from one rock is how you fly into
  the next; this was worth several percent on its own
- **`spawn-ufo` chooses its entry height.** Asteroids enter from the edges too,
  which is exactly where a saucer appears, so a random entry height was landing
  saucers inside rocks — that was the single largest cause of losses, bigger
  than every in-flight failure combined. It now tries `ufo-entry-tries` heights
  and comes in where the sky is clearest

Measured over full crossings of drifting fields, a competent saucer gets across
about 93% of the time at level 5 and 95% at level 8. Without the Z constraint it
was 100%; with no avoidance at all it was around 30%.

`ufo-dodge-chance` no longer governs how well a saucer flies, only whether it
bothers at all: it is rolled once, the first time that saucer meets a rock, and
kept for life. Rolling per encounter would compound — five brushes at 0.97 each
is only 0.86 overall — which is why the decision is cached on the saucer

The heartbeat interval shrinks with the level and with time spent on it
(`beat-interval`), bottoming out at `beat-interval-min`. `:level-t` resets each
level. It plays during `:playing` and `:dead` but stops at `:game-over`.

> The UFO numbers (speeds, fire rates, spawn delays, aim spread) were chosen by
> me, not from measurements of the original, and have not been playtested. They
> are the most likely thing in this milestone to need adjusting.

## Control modes (milestone 8)

The page asks once whether you are on a PC or a touch device, then remembers the
answer. `asteroids.mode` is pure and holds the precedence rules; `core` does the
IO around it.

**Precedence: `?mode=` in the URL > `localStorage` > ask.** The URL wins so a
link can force a mode for testing or sharing, and it deliberately does **not**
overwrite the remembered choice — a shared link must not silently change
someone's setting. `mode/parse` accepts only `"desktop"` and `"touch"`, so a
stale or hand-edited value falls through to the chooser rather than wedging the
game.

- The chooser button is a real user gesture, which is the natural place to call
  `sound/init!`. The keydown path still calls it too, for players who arrive
  with a remembered mode and never see the chooser
- **The mode switch drops `?mode=` from the URL before reloading.** Without that,
  switching while an override is active reloads straight back into the mode you
  just asked to leave
- Switching reloads the page rather than tearing the input layer down by hand.
  It is confirmed first, since a stray tap should not end a run
- `body` carries `mode-desktop` / `mode-touch`, and CSS keys off that
- Touch mode plus portrait sets `body.portrait`, which shows the rotate prompt,
  and sets `paused?` so the game is not quietly killing you behind it. `frame!`
  still draws while paused, but does not tick and silences the continuous sounds

## Touch controls (milestone 9)

Left strip steers, right strip fires. `asteroids.control` is pure and holds the
mapping; `asteroids.touch` owns the pointer events.

**The whole point is that `game` never learns touch exists.** `control` emits
the same `#{:left :right :thrust}` the keyboard does, and `core` merges the two
sources, so the touch version provably plays by identical rules. **Do not add a
touch-shaped argument to `tick`** — anything new belongs in `control` instead.

- The stick names a **heading, not a velocity**: the ship still turns at
  `rotate-speed` and still only accelerates under thrust, so inertia and drag
  are untouched
- **Thrust also needs the nose roughly aligned** (`thrust-align`). Without it, a
  full push away from the ship's heading accelerates it backwards while it
  turns, which reads as broken controls
- `turn-epsilon` must stay above one frame of rotation (200°/s ÷ 60 = 3.33°) or
  the ship oscillates around the heading it is trying to hold. There is a test
  asserting exactly this
- **The ring is a fixed landmark, not a stick that appears under the thumb**, so
  direction is absolute: the offset is measured from the centre of the strip,
  and touching near the top means up wherever the last touch was. Anywhere in
  the strip steers — past the rim simply reads as full deflection, so the ring
  shows where centre is rather than being a target you have to hit
- `touch/size-ring!` sizes the ring from `control/stick-max`, so the drawn rim
  really is full deflection and the picture cannot drift from the number
- **Firing latches.** A tap that begins and ends between two frames would
  otherwise never be seen, since `game` fires on the rising edge of `:fire`
- The crosshair drawn in the fire strip is decoration; the whole strip fires
- Touch gets an on-screen **mute button**, since `M` is not available. It shares
  one `body.muted` class with the key, so the two never disagree, and tapping it
  calls `sound/init!` — on a phone it may well be the first thing pressed, and a
  tap is the gesture the browser wants before audio can exist. Both corner
  buttons sit above the strips, so a tap on one never reaches the pad and fires
- Pointer events and pointer ids throughout, never touch events: steering and
  firing must work with two thumbs at once. `setPointerCapture` is wrapped in a
  `try` — it is an enhancement, and a failure must not abort the handler
- `blur` clears the stick and every held pointer, or a finger that leaves the
  window leaves the ship thrusting forever

The strips live in the letterbox margin left by the 4:3 playfield, so they never
cover the game. They have a 120px floor, so on a 16:9 device they overlap the
very edge of the field rather than becoming too narrow to use.

**Touch play runs at `control/thrust`, not `game/thrust`.** Handed over by
`game/with-thrust`, which puts the figure in the state; `update-ship` takes it
as an argument rather than reading the var. Keyboard play is untouched. Two
things follow: a restart after game over must carry the current `:thrust`
across (`advance-phase` does, and there is a test), and nothing else about the
physics is allowed to fork this way — one number, in the state, is the whole
mechanism.

## Departures from the original

Keep this list current. Anything here is a decision, not a defect.

| Departure | Why |
|---|---|
| **Saucers avoid asteroids** | Requested. The arcade saucer flies on regardless and gets smashed by rocks. This is the biggest behavioural change: it makes saucers harder to be rid of, since you now have to shoot them yourself |
| **Asteroid outlines are generated** | The original reused a few hand-drawn shapes at three scales; `make-asteroid` builds a fresh 12-vertex polygon per rock |
| **Objects are mirrored across screen edges** | The original popped an object to the far side when its centre crossed. Mirroring makes a radius-42 rock look like it slides across rather than teleporting. Physics is unchanged — still centre-based wrap |
| **The score uses `fillText`** | The original drew digits as vector strokes. Hand-drawn digit strokes would match, but that is separate work |
| **No hyperspace** | Listed in the scope above but never built, and no milestone covered it. Still open |
| **Touch mode aims for you, and accelerates more gently** | The virtual stick names a heading and the ship turns to it, so touch play never asks you to line the nose up by hand, and it runs at `control/thrust` rather than `game/thrust`. Inertia, drag and rotation speed are unchanged. The arcade cabinet had buttons, not a stick |

## Line endings

`core.autocrlf = false`. An external tool once rewrote LICENSE's line endings
and made the whole file look changed. `.gitattributes` now fixes this with
`* text=auto eol=lf`: LF both in version control and in the working tree, and
anything a tool writes as CRLF is normalised back on commit.

## Milestones

One commit per milestone, and each one must show a visible result in the browser.

- [x] 1. Scaffolding: shadow-cljs project + blank canvas + a moving white dot (proves the toolchain)
- [x] 2. Ship: rotation, thrust, inertia, screen wrap ← the heart of the handling; needs the user to playtest and pick the values
- [x] 3. Asteroids: polygon generation, drift, wrap
- [x] 4. Shooting and collisions: bullet lifecycle, three-tier splitting
- [x] 5. Game rules: score, lives, level progression, invulnerable respawn
- [x] 6. UFOs and audio: large and small saucer AI, heartbeat that speeds up with the level
- [x] 7. Deployment: `shadow-cljs release` + GitHub Pages
- [x] 8. Control modes: device chooser, remembered choice, portrait handling
- [x] 9. Touch controls: virtual stick on the left, tap to fire on the right
- [ ] 10. Hyperspace (still missing from the original's feature list)

## Working agreement

- One commit per milestone, which keeps `git diff` and rollbacks easy
- The milestone 2 handling parameters (thrust, rotation speed, drag) are decided
  by the user after playing; do not settle them unilaterally

## Notes

- Asteroids is a registered Atari trademark. A self-written clone on GitHub for
  learning is fine, but use none of the original artwork and imply no
  affiliation with Atari.
