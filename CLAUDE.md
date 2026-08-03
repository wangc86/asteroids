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
| Scope | **faithful remake of the original** | three asteroid tiers, large and small UFOs, hyperspace, heartbeat audio, level progression |

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
- Build output `public/js/` and `out/` are not version controlled; deployment
  (milestone 7) will handle the release build separately
- **Restart the server after changing `:source-paths` in `shadow-cljs.edn`**
  (`npx shadow-cljs stop`). A long-running server does not re-read that setting,
  and the symptom is tests that exist but report `Ran 0 tests`

## Project layout

```
public/index.html             page shell and canvas CSS (4:3, fills the window)
src/asteroids/game.cljs       pure logic: constants, RNG, spawning, tick, collisions
src/asteroids/core.cljs       side effects: draw!, keyboard, canvas, rAF loop
src/asteroids/sound.cljs      side effects: Web Audio synthesis
test/asteroids/game_test.cljs unit tests for game
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

`npm test` runs `test/asteroids/game_test.cljs` under node — no browser needed.

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

The heartbeat interval shrinks with the level and with time spent on it
(`beat-interval`), bottoming out at `beat-interval-min`. `:level-t` resets each
level. It plays during `:playing` and `:dead` but stops at `:game-over`.

> The UFO numbers (speeds, fire rates, spawn delays, aim spread) were chosen by
> me, not from measurements of the original, and have not been playtested. They
> are the most likely thing in this milestone to need adjusting.

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
- [ ] 7. Deployment: `shadow-cljs release` + GitHub Pages

## Working agreement

- One commit per milestone, which keeps `git diff` and rollbacks easy
- The milestone 2 handling parameters (thrust, rotation speed, drag) are decided
  by the user after playing; do not settle them unilaterally

## Notes

- Asteroids is a registered Atari trademark. A self-written clone on GitHub for
  learning is fine, but use none of the original artwork and imply no
  affiliation with Atari.
