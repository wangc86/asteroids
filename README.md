# Asteroids

A remake of Atari's *Asteroids* (1979), written in ClojureScript and rendered
with an HTML5 canvas.

The original ran on a vector display, so the whole game is line art: a triangular
ship, jagged rocks, and single-pixel shots on a black field. This remake keeps
that, along with the parts of the original that people remember — three tiers of
asteroid that split when you shoot them, momentum that never quite lets go, and
a screen that wraps around on every edge.

It follows the original closely on rules and handling, but it is not a
reproduction of it. Where it differs, it says so below.

## Play

**<https://wangc86.github.io/asteroids/>**

Or run it locally:

```bash
npm install
npx shadow-cljs watch app
```

Then open <http://localhost:8080>.

The first time you visit, the page asks whether you are on a PC or a phone, and
remembers the answer. The button in the bottom-right corner changes it later.
You can also link straight into a mode with `?mode=desktop` or `?mode=touch`.

Keyboard:

| Key | Action |
|---|---|
| `←` `→` or `A` `D` | Turn |
| `↑` or `W` | Thrust |
| `↓` or `S` | Hyperspace |
| `Space` | Fire, and restart after game over |
| `M` | Mute |

Touch, holding the device sideways: touch anywhere in the **left** strip to
steer. Direction is read from the middle of the ring drawn there, so pushing
towards the top of the strip means up — you can reach outside the ring, it marks
the centre rather than being a target to hit. The ship turns to face where you
push and thrusts once its nose comes round. Tap anywhere in the **right** strip,
marked with a crosshair, to fire and to start again after a game over. The
strips sit in the black margins either side of the playfield, so they never
cover the game. `HYPER` at the top of the left strip is hyperspace, and there is
a mute button in the top-right corner.

Scoring follows the original: 20 points for a large asteroid, 50 for a medium,
100 for a small, and an extra life every 10,000 points. You start with three
lives. Each level adds two more asteroids, up to eleven.

Flying saucers come through periodically. The large one (200 points) sprays
shots in random directions; the small one (1,000 points) aims at you, and gets
more accurate the higher your score. Past 40,000 points the large one stops
showing up altogether. Their shots break asteroids too, though you get no credit
for the rocks they clear.

Saucers fly the original's zig-zag — straight legs, sharp turns.

When there is nowhere left to turn, hyperspace throws the ship to a random spot
on the screen and stops it dead. There is no safety net: you might come back in
open space, or inside a rock. That gamble is the point — it is the move you make
when the alternative is certain.

Every sound is synthesised in code — there are no audio files. Browsers will not
start audio without a user gesture, so the first key you press is what switches
it on. On touch the game starts muted, and the button in the top-right corner
turns the sound on.

## Where this differs from the original

**Saucers avoid asteroids.** In the arcade game they fly on regardless and get
smashed by rocks like anything else. Here they look ahead, predict collisions
from relative motion, and pick whichever of climb, level or dive leaves the most
room — so they usually thread a crowded field instead of blundering into it.
They still only turn three ways, so sometimes there is nowhere good to go, and
once in a while a saucer turns out not to be a pilot at all. This is the largest
deliberate change, and it makes saucers meaningfully harder to be rid of.

**Asteroid outlines are generated, not drawn.** The original had a handful of
hand-drawn rock shapes reused at three scales; here each rock is a fresh
12-vertex polygon with jittered radii, so no two are alike.

**The score uses a normal monospace font**, where the original drew its digits
as vector strokes like everything else.

**Touch mode aims for you, and accelerates more gently.** The stick names a
heading and the ship turns to face it, so you never line the nose up by hand,
and thrust is dialled down because with the aiming taken care of the ship
otherwise builds speed faster than a thumb can answer. Momentum, drag and turn
rate are unchanged, and keyboard play is exactly as it was — but the cabinet had
buttons, not a stick.

Two details are kept the way the original had them, rather than modernised.
Shots travel at a fixed speed and do **not** inherit the ship's velocity, so at
full throttle you can very nearly catch up with your own bullets. And firing is
one shot per press — holding the key down does not auto-fire.

## Status

Complete and deployed: ship handling, asteroids, shooting and splitting,
scoring, lives, level progression, invulnerable respawns, both saucers,
hyperspace, and the synthesised sound including the heartbeat that speeds up as
a level wears on. Everything on the original's feature list is in.

## How it is built

The game state is a single immutable map. Every frame, a pure function advances
it; drawing is the only side effect.

```clojure
(defn tick  [state dt inputs] ...)  ; pure → new state
(defn draw! [ctx state]      ...)   ; the only side effect
```

That split is enforced by the namespace layout, not just by convention:

```
src/asteroids/game.cljs       pure logic — constants, RNG, spawning, tick, collisions
src/asteroids/mode.cljs       pure logic — which control scheme to run
src/asteroids/control.cljs    pure logic — virtual stick to action keywords
src/asteroids/core.cljs       side effects — canvas drawing, keyboard, the rAF loop
src/asteroids/touch.cljs      side effects — pointer events for the touch strips
src/asteroids/sound.cljs      side effects — Web Audio synthesis
test/asteroids/               unit tests for the pure namespaces
```

`asteroids.game` touches no browser API at all: no `document`, no canvas, not
even an atom. The dependency direction is one-way, `core → game` and
`core → sound`.

Sound works the same way. `tick` cannot make a noise, so it appends event
keywords to `:events` — `:fire`, `:bang-large`, `:beat-a` — and `core` hands
them to `asteroids.sound` after drawing. The upshot is that "shooting a large
asteroid makes the large explosion sound" is an ordinary unit test, with no
audio hardware involved.

The touch controls follow the same shape. `asteroids.control` turns the virtual
stick into the same `#{:left :right :thrust}` the keyboard produces, so
`asteroids.game` has no idea touch exists and the touch version provably plays
by identical rules. That also means the feel of the stick — when it turns, when
it decides you are aligned enough to thrust — is tested under node rather than
judged by waggling a thumb.

The one design decision worth calling out is that **the random number generator's
state lives inside the game state** (`:seed`, a small xorshift32). Anything that
needs randomness — spawning a wave, splitting a rock — is written as
`(f seed ...) → [result new-seed]`. Nothing under `tick` ever calls
`js/Math.random`, so `tick` stays genuinely pure, and `(initial-state 12345)`
replays exactly the same game every time.

That reproducibility is what makes the tests worth having. A fixed seed plus a
fixed input sequence gives a fixed result, so a handling requirement can be
written as a plain assertion:

```clojure
(deftest ship-turns-at-the-tuned-rate
  (let [s0 (game/initial-state 1)
        s1 (run s0 1 #{:right})            ; hold right for one second at 60 fps
        turned (degrees (- (get-in s1 [:ship :angle])
                           (get-in s0 [:ship :angle])))]
    (is (close? turned game/rotate-speed 1e-6)
        "holding right for a second turns exactly rotate-speed degrees")))
```

## Development

```bash
npm install                    # install dependencies
npx shadow-cljs watch app      # dev server with hot reload → localhost:8080
npm test                       # unit tests under node, no browser needed
npx shadow-cljs release app    # production build into public/js
```

Hot reload preserves the running game state, so you can change the code without
losing your current run. The shadow-cljs dashboard is at
<http://localhost:9630>.

Requirements: a JVM for the ClojureScript compiler, plus Node.js. Developed
against Temurin JDK 21.0.11 and Node.js v24.

Pushing to `main` builds and publishes to GitHub Pages via
`.github/workflows/deploy.yml`. The tests run first, so a red suite never
reaches the site. Build output is not committed — CI produces it.

`CLAUDE.md` holds the longer engineering notes — why each parameter has the
value it does, which behaviours are deliberate departures from the original, and
what not to change without playtesting.

## License

GPL-3.0. See [LICENSE](LICENSE).

*Asteroids* is a registered trademark of Atari. This is an independent
reimplementation written for learning; it uses none of the original artwork and
is not affiliated with or endorsed by Atari.
