(ns asteroids.game-test
  "asteroids.game is entirely pure, so these tests run under node without a
   browser.

   Run them with: npm test"
  (:require [cljs.test :refer [deftest is testing]]
            [asteroids.game :as game]))

;; --- Helpers ----------------------------------------------------------------

(def no-input #{})

(defn- close?
  ([a b] (close? a b 1e-9))
  ([a b eps] (< (js/Math.abs (- a b)) eps)))

(defn- step [state inputs]
  (game/tick state (/ 1 60) inputs))

(defn- run
  "Advance secs seconds at 60 fps."
  [state secs inputs]
  (reduce (fn [st _] (step st inputs)) state (range (js/Math.round (* 60 secs)))))

(defn- speed-of [{:keys [vx vy]}]
  (js/Math.sqrt (+ (* vx vx) (* vy vy))))

(defn- degrees [rad] (* rad (/ 180 js/Math.PI)))

(defn- sizes [state]
  (sort (map :size (:asteroids state))))

(defn- still-asteroid
  "An asteroid parked in place, for collision tests."
  [size x y]
  (let [[a _] (game/make-asteroid 42 size x y)]
    (assoc a :vx 0.0 :vy 0.0)))

(defn- world-with
  "A test field with the given asteroids, no bullets, and no starting
   invulnerability."
  [asteroids]
  (assoc (game/initial-state 777)
         :asteroids (vec asteroids)
         :bullets   []
         :invuln    0.0))

(defn- open-space
  "Leave a single asteroid far away in a corner: the space ahead of the ship is
   effectively empty, but the field is not clear so the level does not end.
   Use this for bullet tests — a genuinely empty field enters :next-level,
   during which firing is disabled."
  [state]
  (assoc state :asteroids [(still-asteroid :small 60 700)]))

;; --- Screen wrap ------------------------------------------------------------

(deftest wrap-test
  (is (= 5 (game/wrap 5 100)) "inside the bounds, unchanged")
  (is (= 95 (game/wrap -5 100)) "off the left edge, back on the right")
  (is (= 5 (game/wrap 105 100)) "off the right edge, back on the left"))

;; --- Randomness -------------------------------------------------------------

(deftest rand-n-is-deterministic
  (let [[a s1] (game/rand-n 12345 8)
        [b s2] (game/rand-n 12345 8)
        [c _]  (game/rand-n 999 8)]
    (is (= a b) "the same seed draws the same sequence")
    (is (= s1 s2))
    (is (not= a c) "a different seed draws a different sequence")
    (is (= 8 (count a)))
    (is (every? #(and (<= 0 %) (< % 1)) a) "the range is [0,1)")))

;; --- Asteroid spawning ------------------------------------------------------

(deftest asteroid-shape-and-speed-are-in-range
  (doseq [size [:large :medium :small]]
    (testing (name size)
      (let [[a _]        (game/make-asteroid 42 size 100 100)
            r            (game/asteroid-radius size)
            [s-min s-max] (game/asteroid-speed size)
            radii        (map (fn [[x y]] (js/Math.sqrt (+ (* x x) (* y y))))
                              (:points a))]
        (is (= game/asteroid-verts (count (:points a))))
        (is (every? #(<= (* r game/jitter-min) % (* r game/jitter-max)) radii)
            "vertex radii stay inside the jitter range")
        (is (<= s-min (speed-of a) s-max) "drift speed stays inside its tier")))))

(deftest initial-state-is-reproducible
  (is (= (game/initial-state 12345) (game/initial-state 12345))
      "one seed must produce exactly the same opening")
  (is (not= (game/initial-state 12345) (game/initial-state 999))))

(deftest first-wave-spawns-four-large-asteroids-on-the-edge
  (let [{:keys [asteroids]} (game/initial-state 12345)]
    (is (= game/level-1-asteroids (count asteroids)))
    (is (every? #(= :large (:size %)) asteroids))
    (is (every? (fn [{:keys [x y]}]
                  (or (zero? x) (zero? y)
                      (= x game/world-w) (= y game/world-h)))
                asteroids)
        "they enter from the border, never on top of the ship")))

;; --- Ship handling (the values settled in milestone 2) ----------------------

(deftest ship-turns-at-the-tuned-rate
  (let [s0 (game/initial-state 1)
        s1 (run s0 1 #{:right})
        turned (degrees (- (get-in s1 [:ship :angle])
                           (get-in s0 [:ship :angle])))]
    (is (close? turned game/rotate-speed 1e-6)
        "holding right for a second turns exactly rotate-speed degrees")))

(deftest left-and-right-cancel-out
  (let [s0 (game/initial-state 1)
        s1 (run s0 1 #{:left :right})]
    (is (close? (get-in s1 [:ship :angle]) (get-in s0 [:ship :angle])))))

(deftest thrust-accelerates-along-the-nose
  (let [s (run (game/initial-state 1) 1 #{:thrust})
        {:keys [vx vy]} (:ship s)
        ;; Closed form: v(t) = (a/k)(1 - e^{-kt}); discrete integration differs
        ;; slightly.
        expected (* (/ game/thrust game/drag)
                    (- 1 (js/Math.exp (- game/drag))))]
    (is (close? vx 0 1e-9) "starting straight up, there is no x velocity")
    (is (close? (- vy) expected 2.0) "y velocity tracks the closed form")
    (is (:thrusting? (:ship s)))))

(deftest inertia-decays-but-does-not-stop
  (let [thrusted (run (game/initial-state 1) 1 #{:thrust})
        v0       (speed-of (:ship thrusted))
        coasted  (run thrusted 3 no-input)
        v1       (speed-of (:ship coasted))]
    (is (close? v1 (* v0 (js/Math.exp (* -3 game/drag))) 0.5)
        "after releasing thrust the speed decays exponentially")
    (is (pos? v1) "but never reaches zero — that is the inertia")
    (is (not (:thrusting? (:ship coasted))))))

(deftest speed-is-clamped
  (let [s (run (game/initial-state 1) 30 #{:thrust})]
    (is (close? (speed-of (:ship s)) game/max-speed 1e-6))))

;; --- Drift and wrap ---------------------------------------------------------

(deftest asteroids-drift-and-stay-inside-the-world
  (let [s0 (game/initial-state 12345)
        s1 (reduce (fn [st _]
                     (let [st (step st no-input)]
                       (doseq [{:keys [x y]} (:asteroids st)]
                         (is (<= 0 x game/world-w))
                         (is (<= 0 y game/world-h)))
                       st))
                   s0
                   (range 600))]
    (is (= (map speed-of (:asteroids s0)) (map speed-of (:asteroids s1)))
        "drifting does not change speed")
    (is (= (map :points (:asteroids s0)) (map :points (:asteroids s1)))
        "nor the outline")))

;; --- Bullet lifecycle -------------------------------------------------------

(deftest firing-is-edge-triggered
  (let [s      (open-space (game/initial-state 1))
        held   (run s 1 #{:fire})
        tapped (-> s (step #{:fire}) (step no-input) (step #{:fire}))]
    (is (= 1 (count (:bullets held))) "holding the key fires only once")
    (is (= 2 (count (:bullets tapped))) "releasing and pressing again fires a second")))

(deftest at-most-four-bullets-on-screen
  (let [s (reduce (fn [st _] (-> st (step #{:fire}) (step no-input)))
                  (open-space (game/initial-state 1))
                  (range 10))]
    (is (= game/max-bullets (count (:bullets s))))))

(deftest bullet-leaves-the-nose-at-fixed-speed
  (let [s (step (open-space (game/initial-state 1)) #{:fire})
        b (first (:bullets s))]
    (is (close? (:x b) (/ game/world-w 2) 1e-6))
    (is (close? (:y b) (- (/ game/world-h 2) game/ship-nose) 1e-6)
        "it leaves from the nose")
    (is (close? (:vx b) 0 1e-6))
    (is (close? (:vy b) (- game/bullet-speed) 1e-6)
        "fixed muzzle speed, the ship's velocity is not added (the original's quirk)")))

(deftest bullets-expire
  (let [fired (step (open-space (game/initial-state 1)) #{:fire})]
    (is (= 1 (count (:bullets (run fired 1.13 no-input)))) "still alive within its life")
    (is (= 0 (count (:bullets (run fired 1.2 no-input)))) "gone once its life runs out")))

;; --- Collisions and splitting -----------------------------------------------

(deftest large-splits-into-two-medium
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= [:medium :medium] (sizes s)))
    (is (empty? (:bullets s)) "bullet and asteroid destroy each other")))

(deftest medium-splits-into-two-small
  (let [s (-> (world-with [(still-asteroid :medium 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= [:small :small] (sizes s)))))

(deftest small-is-destroyed-outright
  (let [s (-> (world-with [(still-asteroid :small 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (empty? (:asteroids s)))))

(deftest children-fly-apart
  (let [s     (-> (world-with [(still-asteroid :large 512 184)])
                  (step #{:fire})
                  (run 0.4 no-input))
        [c1 c2] (:asteroids s)
        [s-min s-max] (game/asteroid-speed :medium)]
    (is (not= [(:vx c1) (:vy c1)] [(:vx c2) (:vy c2)]) "the two head different ways")
    (is (<= s-min (speed-of c1) s-max))
    (is (<= s-min (speed-of c2) s-max))))

(deftest collision-works-across-the-wrap-seam
  (let [s (-> (world-with [(still-asteroid :large 1020 384)])
              (assoc :bullets [{:x 6.0 :y 384.0 :vx 0.0 :vy 0.0 :life 1.0}])
              (step no-input))]
    (is (= [:medium :medium] (sizes s))
        "an asteroid on the right edge and a bullet on the left are close in a wrapping world")
    (is (empty? (:bullets s)))))

(deftest a-miss-destroys-nothing
  (let [s (-> (world-with [(still-asteroid :large 100 100)])
              (step #{:fire})
              (run 0.3 no-input))]
    (is (= [:large] (sizes s)))
    (is (= 1 (count (:bullets s))))))

;; --- Score and lives --------------------------------------------------------

(deftest destroying-asteroids-scores-by-size
  (doseq [[size expected] [[:large (:large game/score-for)]
                           [:medium (:medium game/score-for)]
                           [:small (:small game/score-for)]]]
    (testing (name size)
      (let [s (-> (world-with [(still-asteroid size 512 184)])
                  (step #{:fire})
                  (run 0.4 no-input))]
        (is (= expected (:score s)))))))

(deftest score-starts-at-zero-with-three-lives
  (let [s (game/initial-state 1)]
    (is (= 0 (:score s)))
    (is (= game/start-lives (:lives s)))
    (is (= 1 (:level s)))
    (is (= :playing (:phase s)))))

(deftest extra-life-at-the-threshold
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              ;; 20 points short of the threshold, so one large asteroid crosses it
              (assoc :score (- game/extra-life-every 20))
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= game/extra-life-every (:score s)))
    (is (= (inc game/start-lives) (:lives s)) "crossing the threshold grants a life")
    (is (= (* 2 game/extra-life-every) (:next-extra-life s)) "the threshold moves up"))
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              (assoc :score (- game/extra-life-every 100))
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= game/start-lives (:lives s)) "no life without crossing the threshold")))

;; --- Death and respawn ------------------------------------------------------

(deftest ship-dies-when-it-hits-an-asteroid
  (let [s (-> (world-with [(still-asteroid :large 512 384)])   ; right on top of the ship
              (step no-input))]
    (is (= (dec game/start-lives) (:lives s)))
    (is (= :dead (:phase s)))
    (is (close? (:timer s) game/respawn-delay 0.02))))

(deftest invulnerable-ship-survives
  (let [s (-> (world-with [(still-asteroid :large 512 384)])
              (assoc :invuln game/invuln-time)
              (run 1 no-input))]
    (is (= game/start-lives (:lives s)) "invulnerability means no damage")
    (is (= :playing (:phase s)))))

(deftest new-game-starts-invulnerable
  (let [s (game/initial-state 1)]
    (is (pos? (:invuln s)))))

(deftest respawn-waits-for-a-clear-centre
  ;; An asteroid parked dead centre: even after the countdown, no respawn.
  (let [blocked (-> (world-with [(still-asteroid :large 512 384)])
                    (step no-input)                 ; die
                    (run 5 no-input))]              ; well past respawn-delay
    (is (= :dead (:phase blocked)) "keep waiting while the centre is blocked")
    (is (zero? (:timer blocked))))
  ;; Asteroid far away: the countdown alone releases the ship.
  (let [freed (-> (world-with [(still-asteroid :large 512 384)])
                  (step no-input)
                  (assoc :asteroids [(still-asteroid :large 60 60)])
                  (run 3 no-input))]
    (is (= :playing (:phase freed)))
    (is (pos? (:invuln freed)) "a respawn comes with invulnerability")
    (is (= (game/initial-ship) (dissoc (:ship freed) :thrusting?))
        "back at the centre with zero velocity")))

(deftest last-life-ends-the-game
  (let [s (-> (world-with [(still-asteroid :large 512 384)])
              (assoc :lives 1)
              (step no-input))]
    (is (= 0 (:lives s)))
    (is (= :game-over (:phase s)))))

(deftest game-over-ignores-controls-until-space
  (let [over    (-> (world-with [(still-asteroid :large 512 384)])
                    (assoc :lives 1)
                    (step no-input)
                    (run 3 #{:thrust :left}))
        pressed (step over #{:fire})]
    (is (= :game-over (:phase over)) "thrust and turn do not restart the game")
    (is (= :playing (:phase pressed)))
    (is (= 0 (:score pressed)))
    (is (= game/start-lives (:lives pressed)))
    (is (= 1 (:level pressed)) "a restart returns to level one")))

;; --- Level progression ------------------------------------------------------

(deftest asteroid-count-grows-then-caps
  (is (= 4 (game/asteroids-for-level 1)))
  (is (= 6 (game/asteroids-for-level 2)))
  (is (= 8 (game/asteroids-for-level 3)))
  (is (= game/max-level-asteroids (game/asteroids-for-level 5)))
  (is (= game/max-level-asteroids (game/asteroids-for-level 20)) "no growth past the cap"))

(deftest clearing-the-field-advances-the-level
  (let [cleared (-> (world-with []) (step no-input))]
    (is (= :next-level (:phase cleared)))
    (is (= 1 (:level cleared)) "the level has not changed during the pause"))
  (let [next-wave (-> (world-with []) (run (+ game/level-pause 0.2) no-input))]
    (is (= :playing (:phase next-wave)))
    (is (= 2 (:level next-wave)))
    (is (= (game/asteroids-for-level 2) (count (:asteroids next-wave))))
    (is (every? #(= :large (:size %)) (:asteroids next-wave)))))

(deftest ship-is-frozen-while-dead
  (let [dead   (-> (world-with [(still-asteroid :large 512 384)]) (step no-input))
        before (:ship dead)
        after  (:ship (run dead 1 #{:thrust :right}))]
    (is (= :dead (:phase dead)))
    (is (= before after) "input does not move the ship while dead")))

(deftest cannot-fire-while-dead
  (let [dead (-> (world-with [(still-asteroid :large 512 384)])
                 (step no-input)
                 (assoc :bullets []))
        shot (-> dead (step #{:fire}) (step no-input) (step #{:fire}))]
    (is (empty? (:bullets shot)))))

;; --- End to end -------------------------------------------------------------

(deftest the-whole-game-is-deterministic
  (let [play #(run (game/initial-state 4242) 3 #{:thrust :fire})]
    (is (= (play) (play))
        "one seed plus one input sequence always produces an identical result")))
