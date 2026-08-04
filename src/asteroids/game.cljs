(ns asteroids.game
  "Pure game logic: world constants, RNG, spawning, per-frame stepping,
   collisions and splitting.

   This namespace touches no browser API — no document, no canvas, no atoms.
   Every function's output is determined by its input, so it runs under node
   in tests. Everything with a side effect (drawing, keyboard, the rAF loop)
   lives in asteroids.core.")

;; The game always uses these logical coordinates; core/ensure-size! maps them
;; onto the canvas's real pixels, so resizing the window never changes any
;; gameplay number.
(def ^:const world-w 1024)
(def ^:const world-h 768)

(def ^:const tau (* 2 js/Math.PI))
(def ^:const deg->rad (/ js/Math.PI 180))

;; Feel parameters, settled by the user's playtest in milestone 2. Do not tweak.
(def ^:const rotate-speed 200)   ; degrees/second
(def ^:const thrust 340)         ; px/second²
(def ^:const drag 0.25)          ; per-second exponential velocity decay (0 = frictionless)
(def ^:const max-speed 540)      ; px/second

;; In its own coordinate space the ship points along +x: nose in front, tail a
;; V notched forward (the original's outline). ship-nose doubles as the bullet
;; muzzle position, and the drawing side uses the same numbers.
(def ^:const ship-nose 14)
(def ^:const ship-tail -10)
(def ^:const ship-half-width 9)
(def ^:const ship-notch -5)

;; The three asteroid tiers: base radius and drift speed range (px/second).
(def asteroid-radius {:large 42 :medium 22 :small 11})
(def asteroid-speed  {:large [18 46] :medium [30 78] :small [50 118]})
(def next-size {:large :medium, :medium :small, :small nil})
(def ^:const asteroid-verts 12)
(def ^:const jitter-min 0.72)    ; vertex radius jitter around the base radius,
(def ^:const jitter-max 1.12)    ; i.e. how jagged the outline gets
(def ^:const level-1-asteroids 4)

;; Bullets. The original allows 4 on screen at once, and their speed is fixed
;; rather than added to the ship's — so at top speed you can very nearly catch
;; up with your own shots. That quirk is part of how the original feels.
(def ^:const bullet-speed 620)   ; px/second
(def ^:const bullet-life 1.15)   ; seconds, roughly 70% of the screen width
(def ^:const max-bullets 4)

;; Rules. Scores and the extra-life threshold follow the original.
(def score-for {:large 20, :medium 50, :small 100})
(def ^:const start-lives 3)
(def ^:const extra-life-every 10000)
(def ^:const ship-radius 11)              ; collision circle, a little smaller than
                                          ; the hull so deaths never feel unfair
(def ^:const respawn-delay 2.0)           ; seconds of blank between death and respawn
(def ^:const invuln-time 3.0)             ; seconds of invulnerability after appearing
(def ^:const respawn-clear-radius 90)     ; the respawn point must be clear this far out
(def ^:const level-pause 1.5)             ; seconds between a cleared field and the next wave
(def ^:const level-asteroid-step 2)       ; two more asteroids per level
(def ^:const max-level-asteroids 11)      ; the original's cap

(defn asteroids-for-level [level]
  (min max-level-asteroids
       (+ level-1-asteroids (* level-asteroid-step (dec level)))))

;; UFOs. The large saucer fires blindly; the small one aims at the ship and gets
;; steadily more accurate as the score climbs. Above small-ufo-only-score the
;; large one stops appearing altogether, which is how the original ramps up.
(def ufo-radius {:large 20 :small 11})
(def ufo-speed  {:large 120 :small 165})   ; px/second, horizontal
(def ufo-score  {:large 200 :small 1000})
(def ufo-fire-interval {:large 1.5 :small 1.0})   ; seconds
(def ^:const ufo-turn-interval 1.1)       ; seconds between vertical course changes
(def ^:const ufo-vertical-ratio 0.55)     ; vertical speed as a fraction of horizontal
(def ^:const small-ufo-only-score 40000)  ; at and above this, only small saucers
(def ^:const ufo-aim-spread-max 0.55)     ; radians of aiming error at score 0
(def ^:const ufo-aim-spread-min 0.04)     ; ...and once the player is deep into a game
(def ^:const ufo-aim-tighten-by 60000)    ; score at which the spread reaches its minimum
;; Obstacle avoidance. A saucer predicts collisions from relative motion and
;; steers clear, re-deciding every frame, which makes it very hard to hit a rock
;; by accident. The chance below is only whether a given saucer is a competent
;; pilot at all — rolled once, the first time it meets a rock, and kept for life.
(def ufo-dodge-chance {:large 0.97 :small 0.995})
(def ^:const ufo-evade-horizon 3.5)       ; seconds of lookahead
(def ^:const ufo-clearance 34)            ; px hull-to-hull below which we call it a threat
;; Up, level, down and nothing in between, at the same vertical speed as the
;; aimless zig-zag: every leg of the flight path has one of three slopes, which
;; is what keeps it looking like the original's Z rather than a smooth curve.
(def ufo-evade-options [-1.0 0.0 1.0])
(def ^:const ufo-evade-hold 0.45)         ; seconds a heading is held before rethinking
(def ^:const ufo-entry-tries 8)           ; entry heights considered when arriving
(def ^:const ufo-delay-min 9.0)           ; seconds between saucers at level 1
(def ^:const ufo-delay-max 20.0)
(def ^:const ufo-delay-per-level 1.2)     ; each level shortens the wait
(def ^:const ufo-delay-floor 4.0)

;; The heartbeat: two alternating low thumps that speed up as a level wears on,
;; and start faster on later levels.
(def ^:const beat-interval-max 1.0)       ; seconds between beats at the start of level 1
(def ^:const beat-interval-min 0.28)
(def ^:const beat-speedup-per-level 0.06)
(def ^:const beat-speedup-per-second 0.012)

(defn beat-interval [level level-t]
  (max beat-interval-min
       (- beat-interval-max
          (* beat-speedup-per-level (dec level))
          (* beat-speedup-per-second level-t))))

;; --- Randomness -------------------------------------------------------------
;; The RNG state (seed) travels inside the game state. That keeps even
;; randomness-dependent behaviour — such as splitting an asteroid — inside pure
;; functions, and it means one seed always replays the same game, which is what
;; the tests rely on.

(defn- xorshift32 [s]
  (let [s (bit-xor s (bit-shift-left s 13))
        s (bit-xor s (unsigned-bit-shift-right s 17))
        s (bit-xor s (bit-shift-left s 5))]
    s))

(defn rand-n
  "Draw n numbers in [0,1) from seed. Returns [numbers new-seed]."
  [seed n]
  (loop [s seed, i 0, acc (transient [])]
    (if (< i n)
      (let [s' (xorshift32 s)]
        (recur s' (inc i)
               (conj! acc (/ (unsigned-bit-shift-right s' 0) 4294967296))))
      [(persistent! acc) s])))

(defn- lerp [a b t] (+ a (* t (- b a))))

;; --- Sound events -----------------------------------------------------------
;; tick cannot make a noise, so it appends event keywords to :events instead and
;; core hands them to asteroids.sound. :events is cleared at the top of every
;; tick, which keeps "firing makes a sound" an ordinary, testable assertion.

(defn- emit [state event]
  (update state :events conj event))

(def bang-event {:large :bang-large, :medium :bang-medium, :small :bang-small})

;; --- Spawning ---------------------------------------------------------------

(defn make-asteroid
  "Create one asteroid: random heading, random speed within its tier, and an
   outline whose vertex radii are each jittered around the base radius.
   Asteroids do not rotate (the original's behaviour), so the vertex coordinates
   are computed once here and the render loop never needs trigonometry."
  [seed size x y]
  (let [[rs seed]     (rand-n seed (+ 2 asteroid-verts))
        dir           (* tau (nth rs 0))
        [s-min s-max] (asteroid-speed size)
        speed         (lerp s-min s-max (nth rs 1))
        radius        (asteroid-radius size)
        step          (/ tau asteroid-verts)
        points        (mapv (fn [i]
                              (let [r (* radius (lerp jitter-min jitter-max
                                                      (nth rs (+ 2 i))))
                                    a (* i step)]
                                [(* r (js/Math.cos a)) (* r (js/Math.sin a))]))
                            (range asteroid-verts))]
    [{:x x :y y
      :vx (* speed (js/Math.cos dir))
      :vy (* speed (js/Math.sin dir))
      :size size
      :points points}
     seed]))

(defn- edge-point
  "Pick a point along the four screen edges — a new wave enters from the border
   instead of materialising on top of the ship."
  [r1 r2]
  (if (< r1 0.5)
    [(* r2 world-w) (if (< r1 0.25) 0.0 (double world-h))]
    [(if (< r1 0.75) 0.0 (double world-w)) (* r2 world-h)]))

(defn spawn-wave
  "Place n large asteroids along the edges. Returns [asteroids new-seed]."
  [seed n]
  (loop [i 0, seed seed, acc []]
    (if (< i n)
      (let [[rs seed]  (rand-n seed 2)
            [x y]      (edge-point (nth rs 0) (nth rs 1))
            [a seed]   (make-asteroid seed :large x y)]
        (recur (inc i) seed (conj acc a)))
      [acc seed])))

(defn initial-ship []
  {:x     (/ world-w 2)
   :y     (/ world-h 2)
   :vx    0.0
   :vy    0.0
   ;; Screen y grows downward, so -π/2 points straight up.
   :angle (- (/ js/Math.PI 2))})

(defn initial-state
  "Without a seed, the clock provides one; with a seed the whole game replays
   identically, which is how the tests work."
  ([] (initial-state (bit-or (js/Date.now) 1)))   ; bit-or 1: truncate to 32 bits, never zero
  ([seed]
   (let [[asteroids seed] (spawn-wave seed (asteroids-for-level 1))]
     {:ship            (initial-ship)
      :asteroids       asteroids
      :bullets         []
      :ufo             nil
      :ufo-timer       ufo-delay-max     ; the first saucer never arrives immediately
      ;; One shot per press rather than auto-fire, so we remember whether the
      ;; key was already down last frame.
      :fire-held?      false
      :score           0
      :lives           start-lives
      :level           1
      :level-t         0.0        ; seconds spent on this level; drives the heartbeat
      :beat-timer      0.0
      :beat-flip?      false
      :next-extra-life extra-life-every
      ;; :playing normal play / :dead awaiting respawn /
      ;; :next-level between-wave pause / :game-over awaiting restart
      :phase           :playing
      :timer           0.0        ; countdown for the current phase; all phases share it
      :invuln          invuln-time
      :events          []         ; sound events for this frame, consumed by core
      :seed            seed
      :t               0.0})))

;; --- Stepping one frame -----------------------------------------------------

(defn wrap
  "Screen wrap: leave one edge, come back on the opposite one."
  [v limit]
  (cond
    (< v 0)     (+ v limit)
    (> v limit) (- v limit)
    :else       v))

(defn- turn-dir [inputs]
  (cond
    (and (inputs :left) (inputs :right)) 0.0
    (inputs :left)                       -1.0
    (inputs :right)                      1.0
    :else                                0.0))

(defn update-ship
  "Rotate → thrust → drag → clamp speed → move and wrap."
  [ship dt inputs]
  (let [angle      (+ (:angle ship)
                      (* (turn-dir inputs) rotate-speed deg->rad dt))
        thrusting? (boolean (inputs :thrust))
        ;; Thrust accelerates along the nose; releasing it does not zero the
        ;; velocity, drag merely eats away at it — that is the inertia.
        ax         (if thrusting? (* thrust (js/Math.cos angle)) 0.0)
        ay         (if thrusting? (* thrust (js/Math.sin angle)) 0.0)
        ;; Exponential decay is frame-rate independent, so a jittery dt does not
        ;; make the handling jittery too.
        decay      (js/Math.exp (- (* drag dt)))
        vx         (* (+ (:vx ship) (* ax dt)) decay)
        vy         (* (+ (:vy ship) (* ay dt)) decay)
        speed      (js/Math.sqrt (+ (* vx vx) (* vy vy)))
        clamp      (if (> speed max-speed) (/ max-speed speed) 1.0)
        vx         (* vx clamp)
        vy         (* vy clamp)]
    (assoc ship
           :angle      angle
           :thrusting? thrusting?
           :vx         vx
           :vy         vy
           :x          (wrap (+ (:x ship) (* vx dt)) world-w)
           :y          (wrap (+ (:y ship) (* vy dt)) world-h))))

(defn drift
  "Asteroids only translate and wrap — no acceleration, and no spin."
  [{:keys [x y vx vy] :as a} dt]
  (assoc a
         :x (wrap (+ x (* vx dt)) world-w)
         :y (wrap (+ y (* vy dt)) world-h)))

(defn playing?
  "Only during :playing is the ship controllable, collidable and able to fire."
  [state]
  (= :playing (:phase state)))

;; --- Bullets ----------------------------------------------------------------

(defn- advance-bullets
  "Bullets drift and wrap, and vanish when their life runs out — the only way a
   bullet disappears on its own."
  [bullets dt]
  (into []
        (comp (map (fn [b]
                     (assoc b
                            :life (- (:life b) dt)
                            :x    (wrap (+ (:x b) (* (:vx b) dt)) world-w)
                            :y    (wrap (+ (:y b) (* (:vy b) dt)) world-h))))
              (filter #(pos? (:life %))))
        bullets))

(defn- bullet
  "Bullets carry :from so we can tell whose shot it was: the player's score
   asteroids and can hit a saucer, a saucer's can kill the ship. Both destroy
   asteroids."
  [from x y angle]
  {:x    (wrap x world-w)
   :y    (wrap y world-h)
   :vx   (* bullet-speed (js/Math.cos angle))
   :vy   (* bullet-speed (js/Math.sin angle))
   :life bullet-life
   :from from})

(defn- fire-bullet [{:keys [ship] :as state}]
  (let [angle (:angle ship)]
    (-> state
        (update :bullets conj
                (bullet :player
                        (+ (:x ship) (* ship-nose (js/Math.cos angle)))
                        (+ (:y ship) (* ship-nose (js/Math.sin angle)))
                        angle))
        (emit :fire))))

(defn- fire-edge?
  "Down this frame, up last frame — holding the key does not auto-fire.
   :fire-held? is only updated at the very end of tick, so firing and
   restarting the game both see the same key edge within one frame."
  [state inputs]
  (and (boolean (inputs :fire)) (not (:fire-held? state))))

(defn- maybe-fire [state inputs]
  (if (and (playing? state)
           (fire-edge? state inputs)
           (< (count (:bullets state)) max-bullets))
    (fire-bullet state)
    state))

;; --- Collisions and splitting -----------------------------------------------

(defn- wrap-delta
  "One axis of the shortest distance in a wrapping world: the two sides of the
   screen are adjacent, so hugging the left edge is close to hugging the right."
  [d limit]
  (let [half (/ limit 2)]
    (cond
      (> d half)     (- d limit)
      (< d (- half)) (+ d limit)
      :else          d)))

(defn- within?
  "Are the two points closer than r in the wrapping world?"
  [x1 y1 x2 y2 r]
  (let [dx (wrap-delta (- x2 x1) world-w)
        dy (wrap-delta (- y2 y1) world-h)]
    (< (+ (* dx dx) (* dy dy)) (* r r))))

(defn- hit?
  "A hit is the bullet's centre falling within the asteroid's base radius. The
   outline is an irregular polygon, but a circle reads fine at this size and
   saves a point-in-polygon test."
  [b a]
  (within? (:x b) (:y b) (:x a) (:y a) (asteroid-radius (:size a))))

(defn- first-hit-index [b asteroids already-hit]
  (loop [i 0]
    (when (< i (count asteroids))
      (if (and (not (already-hit i)) (hit? b (nth asteroids i)))
        i
        (recur (inc i))))))

(defn- split
  "A struck asteroid breaks into two of the next size down, each with a freshly
   drawn heading; the smallest tier simply disappears."
  [seed a]
  (if-let [child (next-size (:size a))]
    (let [[c1 seed] (make-asteroid seed child (:x a) (:y a))
          [c2 seed] (make-asteroid seed child (:x a) (:y a))]
      [[c1 c2] seed])
    [[] seed]))

(defn- award-extra-life
  "One extra life per extra-life-every points. A single frame fires at most 4
   bullets and so scores a few hundred points at most — it can never cross two
   thresholds at once, so checking once is enough."
  [state]
  (if (>= (:score state) (:next-extra-life state))
    (-> state
        (update :lives inc)
        (update :next-extra-life + extra-life-every)
        (emit :extra-life))
    state))

(defn- hits-ufo? [b ufo]
  (and (some? ufo)
       (= :player (:from b))                  ; a saucer cannot shoot itself down
       (within? (:x b) (:y b) (:x ufo) (:y ufo) (ufo-radius (:size ufo)))))

(defn- resolve-hits
  "One pass over the bullets, deciding what each one destroyed. A saucer's shots
   break asteroids too, but only the player's shots score."
  [{:keys [asteroids bullets ufo] :as state}]
  (let [{:keys [live hit scored ufo-hit]}
        (reduce (fn [{:keys [hit ufo-hit] :as acc} b]
                  (if-let [i (first-hit-index b asteroids hit)]
                    ;; bullet and asteroid destroy each other
                    (cond-> (assoc acc :hit (conj hit i))
                      (= :player (:from b)) (update :scored conj i))
                    (if (and (not ufo-hit) (hits-ufo? b ufo))
                      (assoc acc :ufo-hit true)
                      (update acc :live conj b))))
                {:live [] :hit #{} :scored #{} :ufo-hit false}
                bullets)]
    (if (and (empty? hit) (not ufo-hit))
      state
      (let [[children seed]
            (reduce (fn [[acc seed] i]
                      (let [[cs seed] (split seed (nth asteroids i))]
                        [(into acc cs) seed]))
                    [[] (:seed state)]
                    hit)
            gained (+ (reduce + 0 (map #(score-for (:size (nth asteroids %))) scored))
                      (if ufo-hit (ufo-score (:size ufo)) 0))]
        (as-> state $
          (assoc $ :bullets   live
                   :asteroids (into (into [] (keep-indexed #(when-not (hit %1) %2)) asteroids)
                                    children)
                   :ufo       (if ufo-hit nil ufo)
                   :seed      seed)
          (reduce (fn [st i] (emit st (bang-event (:size (nth asteroids i))))) $ hit)
          (if ufo-hit (emit $ :bang-ufo) $)
          (update $ :score + gained)
          (award-extra-life $))))))

;; --- UFOs -------------------------------------------------------------------

(defn- ufo-kind
  "Small saucers get commoner as the score rises, and above small-ufo-only-score
   the large one stops showing up at all."
  [score r]
  (if (or (>= score small-ufo-only-score)
          (< r (/ score small-ufo-only-score)))
    :small
    :large))

(declare gap-on-course)

(defn- spawn-ufo
  "Arrive at whichever of several entry heights has the clearest sky ahead.
   Picking one at random would sometimes drop the saucer straight onto a rock —
   asteroids enter from the edges too, which is exactly where a saucer appears."
  [seed score asteroids]
  (let [[rs seed]  (rand-n seed 3)
        kind       (ufo-kind score (nth rs 0))
        from-left? (< (nth rs 1) 0.5)
        base       {:x          (if from-left? 0.0 (double world-w))
                    :vx         (cond-> (ufo-speed kind) (not from-left?) -)
                    :vy         0.0
                    :size       kind
                    :fire-timer (ufo-fire-interval kind)
                    :turn-timer ufo-turn-interval
                    :hold-timer 0.0
                    :dodge?     nil}   ; nil until this saucer first meets a rock
        step       (/ world-h ufo-entry-tries)
        candidates (map #(wrap (+ (* (nth rs 2) world-h) (* % step)) world-h)
                        (range ufo-entry-tries))
        entry      (apply max-key
                          #(gap-on-course (assoc base :y %) (:vx base) 0.0 asteroids)
                          candidates)]
    [(assoc base :y entry) seed]))

(defn- next-ufo-delay
  "Later levels send saucers more often."
  [seed level]
  (let [[rs seed] (rand-n seed 1)
        shift     (* ufo-delay-per-level (dec level))
        lo        (max ufo-delay-floor (- ufo-delay-min shift))
        hi        (max (+ lo 2.0) (- ufo-delay-max shift))]
    [(lerp lo hi (nth rs 0)) seed]))

(defn- maybe-spawn-ufo [state]
  (if (and (playing? state) (nil? (:ufo state)) (zero? (:ufo-timer state)))
    (let [[ufo seed]   (spawn-ufo (:seed state) (:score state) (:asteroids state))
          [delay seed] (next-ufo-delay seed (:level state))]
      (-> state
          (assoc :ufo ufo :ufo-timer delay :seed seed)
          (emit :ufo-appear)))
    state))

(defn- move-ufo
  "A saucer wraps vertically but not horizontally: reaching the far side it
   simply leaves, which is why nil is a normal result here."
  [ufo dt]
  (let [x (+ (:x ufo) (* (:vx ufo) dt))
        r (ufo-radius (:size ufo))]
    (when (and (> x (- r)) (< x (+ world-w r)))
      (assoc ufo
             :x x
             :y (wrap (+ (:y ufo) (* (:vy ufo) dt)) world-h)))))

(defn- steer-ufo
  "Every ufo-turn-interval the saucer picks up, down or level again — that is
   the zig-zag the original flies."
  [state dt]
  (let [ufo (update (:ufo state) :turn-timer - dt)]
    (if (pos? (:turn-timer ufo))
      (assoc state :ufo ufo)
      (let [[rs seed] (rand-n (:seed state) 1)
            dir       (- (js/Math.floor (* 3 (nth rs 0))) 1)]
        (assoc state
               :seed seed
               :ufo  (assoc ufo
                            :vy (* dir (ufo-speed (:size ufo)) ufo-vertical-ratio)
                            :turn-timer ufo-turn-interval))))))

(defn- closest-approach
  "Two objects in straight-line relative motion: when are they nearest within
   [0, horizon], and how near do they get? The relative path is a line, so this
   is just projecting the relative position onto the relative velocity."
  [px py vx vy horizon]
  (let [vv (+ (* vx vx) (* vy vy))
        t  (if (zero? vv)
             0.0
             (min horizon (max 0.0 (/ (- (+ (* px vx) (* py vy))) vv))))
        dx (+ px (* vx t))
        dy (+ py (* vy t))]
    [t (js/Math.sqrt (+ (* dx dx) (* dy dy)))]))

(defn- approach-gap
  "Hull-to-hull gap at the closest approach with this asteroid, if the saucer
   flies at (vx, vy). Negative means they collide. Both are moving, so this has
   to work off relative motion — a lane-shaped check against present positions
   misses the rock that is drifting into the path. Offsets use wrap-delta, so a
   rock about to wrap around counts too."
  [ufo vx vy a]
  (let [px    (wrap-delta (- (:x a) (:x ufo)) world-w)
        py    (wrap-delta (- (:y a) (:y ufo)) world-h)
        [_ d] (closest-approach px py (- (:vx a) vx) (- (:vy a) vy) ufo-evade-horizon)]
    (- d (ufo-radius (:size ufo)) (asteroid-radius (:size a)))))

(defn gap-on-course
  "The tightest squeeze the saucer would face anywhere in the lookahead on this
   heading. One number to both detect trouble and choose the way out."
  [ufo vx vy asteroids]
  (reduce (fn [worst a] (min worst (approach-gap ufo vx vy a)))
          ##Inf
          asteroids))

(defn- evade
  "Pick climb, level or dive — whichever leaves the most room. Scoring each
   option against the whole field is what keeps this useful: steering blindly
   away from one rock is how you fly into the next.

   Only three headings are on offer, and the choice is then held for
   ufo-evade-hold seconds. A saucer allowed to pick a new angle every frame
   flies a smooth curve; the original flies straight legs and turns sharply, so
   we trade some avoidance for keeping the Z."
  [ufo asteroids]
  (let [s       (* (ufo-speed (:size ufo)) ufo-vertical-ratio)
        ;; current heading last: max-key keeps the later of equal scores, so an
        ;; already-good course wins ties and the saucer does not dither
        options (conj (mapv #(* % s) ufo-evade-options) (:vy ufo))
        best    (apply max-key #(gap-on-course ufo (:vx ufo) % asteroids) options)]
    (assoc ufo
           :vy best
           :hold-timer ufo-evade-hold
           :turn-timer ufo-turn-interval)))

(defn- avoid-asteroids
  "Whether a saucer bothers to fly properly is rolled once, the first time it
   meets a rock, and kept for the rest of its life. Rolling again per encounter
   would compound: five brushes with the field at 0.97 each is only 0.86 overall,
   which is not the near-certainty we want."
  [state dt]
  (let [ufo   (update (:ufo state) :hold-timer #(max 0.0 (- % dt)))
        state (assoc state :ufo ufo)]
    (if (>= (gap-on-course ufo (:vx ufo) (:vy ufo) (:asteroids state)) ufo-clearance)
      state                                     ; the way ahead is clear
      (case (:dodge? ufo)
        ;; mid-leg: see the rock, but fly this leg out before turning
        true  (if (pos? (:hold-timer ufo))
                state
                (assoc state :ufo (evade ufo (:asteroids state))))
        false state                             ; this one is not a pilot
        (let [[rs seed] (rand-n (:seed state) 1)
              dodge?    (< (nth rs 0) (ufo-dodge-chance (:size ufo)))]
          (assoc state
                 :seed seed
                 :ufo  (cond-> (assoc ufo :dodge? dodge?)
                         dodge? (evade (:asteroids state)))))))))

(defn- aim-angle
  "The small saucer leads on the ship with an error that shrinks as the score
   climbs; r spreads the shot evenly across that error band."
  [ufo ship score r]
  (let [dx     (wrap-delta (- (:x ship) (:x ufo)) world-w)
        dy     (wrap-delta (- (:y ship) (:y ufo)) world-h)
        spread (max ufo-aim-spread-min
                    (lerp ufo-aim-spread-max ufo-aim-spread-min
                          (min 1.0 (/ score ufo-aim-tighten-by))))]
    (+ (js/Math.atan2 dy dx) (* (- r 0.5) 2 spread))))

(defn- ufo-shoot [state dt]
  (let [ufo (update (:ufo state) :fire-timer - dt)]
    (if (or (pos? (:fire-timer ufo)) (not (playing? state)))
      (assoc state :ufo ufo)
      (let [[rs seed] (rand-n (:seed state) 1)
            angle     (if (= :small (:size ufo))
                        (aim-angle ufo (:ship state) (:score state) (nth rs 0))
                        ;; the large saucer just sprays
                        (* tau (nth rs 0)))]
        (-> state
            (assoc :seed seed
                   :ufo  (assoc ufo :fire-timer (ufo-fire-interval (:size ufo))))
            (update :bullets conj (bullet :ufo (:x ufo) (:y ufo) angle))
            (emit :ufo-fire))))))

(defn- advance-ufo [state dt]
  (if (:ufo state)
    (-> state
        (steer-ufo dt)          ; the aimless zig-zag...
        (avoid-asteroids dt)    ; ...which avoidance is allowed to override
        (ufo-shoot dt)
        (update :ufo move-ufo dt))
    (update state :ufo-timer #(max 0.0 (- % dt)))))

(defn- ufo-struck-by-asteroid
  "A saucer that flies into a rock dies, the same way the ship does."
  [state]
  (let [ufo (:ufo state)]
    (if (and ufo
             (some (fn [a]
                     (within? (:x ufo) (:y ufo) (:x a) (:y a)
                              (+ (ufo-radius (:size ufo)) (asteroid-radius (:size a)))))
                   (:asteroids state)))
      (-> state (assoc :ufo nil) (emit :bang-ufo))
      state)))

;; --- Lives, levels and phases -----------------------------------------------

(defn- ship-hit? [ship asteroids]
  (boolean
   (some (fn [a]
           (within? (:x ship) (:y ship) (:x a) (:y a)
                    (+ ship-radius (asteroid-radius (:size a)))))
         asteroids)))

(defn- ship-threatened?
  "Three ways to die: an asteroid, the saucer's hull, or one of its shots."
  [{:keys [ship asteroids ufo bullets]}]
  (or (ship-hit? ship asteroids)
      (and (some? ufo)
           (within? (:x ship) (:y ship) (:x ufo) (:y ufo)
                    (+ ship-radius (ufo-radius (:size ufo)))))
      (boolean
       (some (fn [b]
               (and (= :ufo (:from b))
                    (within? (:x ship) (:y ship) (:x b) (:y b) ship-radius)))
             bullets))))

(defn- ship-collision
  "Being hit costs a life. Whatever hit us is left alone — the clear-respawn-point
   check is what prevents dying again on the spot."
  [state]
  (if (and (playing? state)
           (zero? (:invuln state))
           (ship-threatened? state))
    (let [lives (dec (:lives state))]
      (-> state
          (assoc :lives lives
                 :phase (if (pos? lives) :dead :game-over)
                 :timer respawn-delay)
          (emit :ship-explode)))
    state))

(defn- check-level-clear
  "The saucer has to be gone too, otherwise it would keep shooting through the
   between-wave pause."
  [state]
  (if (and (playing? state) (empty? (:asteroids state)) (nil? (:ufo state)))
    (assoc state :phase :next-level :timer level-pause)
    state))

(defn- heartbeat
  "Two alternating thumps whose interval shrinks as the level wears on. The
   original uses this as the game's entire soundtrack."
  [state dt]
  (if (#{:playing :dead} (:phase state))
    (let [remaining (- (:beat-timer state) dt)]
      (if (pos? remaining)
        (assoc state :beat-timer remaining)
        (let [flip (not (:beat-flip? state))]
          (-> state
              (assoc :beat-timer (beat-interval (:level state) (:level-t state))
                     :beat-flip? flip)
              (emit (if flip :beat-a :beat-b))))))
    state))

(defn- respawn-clear?
  "Is the respawn point empty enough? If not we keep waiting, so the ship never
   materialises straight into a rock."
  [asteroids]
  (let [cx (/ world-w 2)
        cy (/ world-h 2)]
    (not-any? (fn [a]
                (within? cx cy (:x a) (:y a)
                         (+ respawn-clear-radius (asteroid-radius (:size a)))))
              asteroids)))

(defn- advance-phase [state inputs]
  (case (:phase state)
    :dead
    (if (and (zero? (:timer state)) (respawn-clear? (:asteroids state)))
      (assoc state :ship (initial-ship) :invuln invuln-time :phase :playing)
      state)

    :next-level
    (if (zero? (:timer state))
      (let [level        (inc (:level state))
            [as seed]    (spawn-wave (:seed state) (asteroids-for-level level))
            [delay seed] (next-ufo-delay seed level)]
        (assoc state
               :level     level
               :level-t   0.0     ; the heartbeat starts over each level
               :asteroids as
               :ufo-timer delay
               :seed      seed
               :invuln    invuln-time
               :phase     :playing))
      state)

    :game-over
    (if (fire-edge? state inputs)
      (initial-state (:seed state))
      state)

    state))

(defn tick
  "Advance one frame. inputs is a set of action keywords, e.g. #{:left :thrust :fire}."
  [state dt inputs]
  (-> state
      (assoc :events [])          ; last frame's sounds have already been played
      (update :t + dt)
      (update :invuln #(max 0.0 (- % dt)))
      (update :timer #(max 0.0 (- % dt)))
      (cond-> (playing? state) (-> (update :ship update-ship dt inputs)
                                   (update :level-t + dt)))
      (update :asteroids (fn [as] (mapv #(drift % dt) as)))
      (advance-ufo dt)
      (maybe-spawn-ufo)
      (update :bullets advance-bullets dt)
      (maybe-fire inputs)
      (resolve-hits)
      (ufo-struck-by-asteroid)
      (ship-collision)
      (heartbeat dt)
      (check-level-clear)
      (advance-phase inputs)
      ;; Record the key state last, so every edge test above saw the same frame.
      (assoc :fire-held? (boolean (inputs :fire)))))
