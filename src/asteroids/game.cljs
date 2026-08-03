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
      ;; One shot per press rather than auto-fire, so we remember whether the
      ;; key was already down last frame.
      :fire-held?      false
      :score           0
      :lives           start-lives
      :level           1
      :next-extra-life extra-life-every
      ;; :playing normal play / :dead awaiting respawn /
      ;; :next-level between-wave pause / :game-over awaiting restart
      :phase           :playing
      :timer           0.0        ; countdown for the current phase; all phases share it
      :invuln          invuln-time
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

(defn- fire-bullet [{:keys [ship] :as state}]
  (let [angle (:angle ship)
        cos   (js/Math.cos angle)
        sin   (js/Math.sin angle)]
    (update state :bullets conj
            {:x    (wrap (+ (:x ship) (* ship-nose cos)) world-w)
             :y    (wrap (+ (:y ship) (* ship-nose sin)) world-h)
             :vx   (* bullet-speed cos)
             :vy   (* bullet-speed sin)
             :life bullet-life})))

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

(defn- hit?
  "A hit is the bullet's centre falling within the asteroid's base radius. The
   outline is an irregular polygon, but a circle reads fine at this size and
   saves a point-in-polygon test."
  [b a]
  (let [r  (asteroid-radius (:size a))
        dx (wrap-delta (- (:x a) (:x b)) world-w)
        dy (wrap-delta (- (:y a) (:y b)) world-h)]
    (< (+ (* dx dx) (* dy dy)) (* r r))))

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
        (update :next-extra-life + extra-life-every))
    state))

(defn- resolve-hits [{:keys [asteroids bullets] :as state}]
  (let [[survivors-b hit]
        (reduce (fn [[bs hit] b]
                  (if-let [i (first-hit-index b asteroids hit)]
                    [bs (conj hit i)]          ; bullet and asteroid destroy each other
                    [(conj bs b) hit]))
                [[] #{}]
                bullets)]
    (if (empty? hit)
      state
      (let [[children seed]
            (reduce (fn [[acc seed] i]
                      (let [[cs seed] (split seed (nth asteroids i))]
                        [(into acc cs) seed]))
                    [[] (:seed state)]
                    hit)
            gained (reduce + 0 (map #(score-for (:size (nth asteroids %))) hit))]
        (-> state
            (assoc :bullets   survivors-b
                   :asteroids (into (into [] (keep-indexed #(when-not (hit %1) %2)) asteroids)
                                    children)
                   :seed      seed)
            (update :score + gained)
            (award-extra-life))))))

;; --- Lives, levels and phases -----------------------------------------------

(defn- within?
  "Are the two points closer than r in the wrapping world?"
  [x1 y1 x2 y2 r]
  (let [dx (wrap-delta (- x2 x1) world-w)
        dy (wrap-delta (- y2 y1) world-h)]
    (< (+ (* dx dx) (* dy dy)) (* r r))))

(defn- ship-hit? [ship asteroids]
  (boolean
   (some (fn [a]
           (within? (:x ship) (:y ship) (:x a) (:y a)
                    (+ ship-radius (asteroid-radius (:size a)))))
         asteroids)))

(defn- ship-collision
  "Hitting an asteroid costs a life. The asteroid is left alone — the
   clear-respawn-point check is what prevents dying again on the spot."
  [state]
  (if (and (playing? state)
           (zero? (:invuln state))
           (ship-hit? (:ship state) (:asteroids state)))
    (let [lives (dec (:lives state))]
      (assoc state
             :lives lives
             :phase (if (pos? lives) :dead :game-over)
             :timer respawn-delay))
    state))

(defn- check-level-clear [state]
  (if (and (playing? state) (empty? (:asteroids state)))
    (assoc state :phase :next-level :timer level-pause)
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
      (let [level     (inc (:level state))
            [as seed] (spawn-wave (:seed state) (asteroids-for-level level))]
        (assoc state
               :level     level
               :asteroids as
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
      (update :t + dt)
      (update :invuln #(max 0.0 (- % dt)))
      (update :timer #(max 0.0 (- % dt)))
      (cond-> (playing? state) (update :ship update-ship dt inputs))
      (update :asteroids (fn [as] (mapv #(drift % dt) as)))
      (update :bullets advance-bullets dt)
      (maybe-fire inputs)
      (resolve-hits)
      (ship-collision)
      (check-level-clear)
      (advance-phase inputs)
      ;; Record the key state last, so every edge test above saw the same frame.
      (assoc :fire-held? (boolean (inputs :fire)))))
