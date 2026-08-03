# Asteroids

A faithful remake of Atari's *Asteroids* (1979), written in ClojureScript and
rendered with an HTML5 canvas.

The original ran on a vector display, so the whole game is line art: a triangular
ship, jagged rocks, and single-pixel shots on a black field. This remake keeps
that, along with the parts of the original that people remember — three tiers of
asteroid that split when you shoot them, momentum that never quite lets go, and
a screen that wraps around on every edge.

## Play

```bash
npm install
npx shadow-cljs watch app
```

Then open <http://localhost:8080>.

| Key | Action |
|---|---|
| `←` `→` or `A` `D` | Turn |
| `↑` or `W` | Thrust |
| `Space` | Fire, and restart after game over |
| `M` | Mute |

Scoring follows the original: 20 points for a large asteroid, 50 for a medium,
100 for a small, and an extra life every 10,000 points. You start with three
lives. Each level adds two more asteroids, up to eleven.

Flying saucers come through periodically. The large one (200 points) sprays
shots in random directions; the small one (1,000 points) aims at you, and gets
more accurate the higher your score. Past 40,000 points the large one stops
showing up altogether. Their shots break asteroids too, though you get no credit
for the rocks they clear.

Every sound is synthesised in code — there are no audio files. Browsers will not
start audio without a user gesture, so the first key you press is what switches
it on.

Two details are deliberately faithful rather than modernised. Shots travel at a
fixed speed and do **not** inherit the ship's velocity, so at full throttle you
can very nearly catch up with your own bullets. And firing is one shot per press
— holding the key down does not auto-fire.

## Status

Milestones 1–6 are done: ship handling, asteroids, shooting and splitting,
scoring, lives, level progression, invulnerable respawns, both saucers, and the
synthesised sound including the heartbeat that speeds up as a level wears on.
A deployed build is what remains.

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
src/asteroids/core.cljs       side effects — canvas drawing, keyboard, the rAF loop
src/asteroids/sound.cljs      side effects — Web Audio synthesis
test/asteroids/game_test.cljs unit tests for game
```

`asteroids.game` touches no browser API at all: no `document`, no canvas, not
even an atom. The dependency direction is one-way, `core → game` and
`core → sound`.

Sound works the same way. `tick` cannot make a noise, so it appends event
keywords to `:events` — `:fire`, `:bang-large`, `:beat-a` — and `core` hands
them to `asteroids.sound` after drawing. The upshot is that "shooting a large
asteroid makes the large explosion sound" is an ordinary unit test, with no
audio hardware involved.

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

`CLAUDE.md` holds the longer engineering notes — why each parameter has the
value it does, which behaviours are deliberate quirks of the original, and what
not to change without playtesting.

## License

GPL-3.0. See [LICENSE](LICENSE).

*Asteroids* is a registered trademark of Atari. This is an independent
reimplementation written for learning; it uses none of the original artwork and
is not affiliated with or endorsed by Atari.
