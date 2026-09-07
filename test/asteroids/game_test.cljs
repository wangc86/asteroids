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

(defn- events-during
  "Collect every sound event emitted while advancing secs seconds. :events only
   ever holds the current frame's sounds, so anything spanning frames has to be
   accumulated."
  [state secs inputs]
  (second
   (reduce (fn [[st acc] _]
             (let [st (step st inputs)]
               [st (into acc (:events st))]))
           [state []]
           (range (js/Math.round (* 60 secs))))))

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

(deftest a-game-can-be-run-at-a-gentler-acceleration
  ;; The touch controls aim for you, so they run at a lower figure. Everything
  ;; else about the physics has to stay identical.
  (let [gentle (/ game/thrust 2)
        stock  (run (game/initial-state 1) 1 #{:thrust})
        slower (run (game/with-thrust (game/initial-state 1) gentle) 1 #{:thrust})]
    (is (< (speed-of (:ship slower)) (speed-of (:ship stock))))
    (is (close? (speed-of (:ship slower))
                (* (/ gentle game/drag) (- 1 (js/Math.exp (- game/drag))))
                2.0)
        "the same closed form, just with a smaller a")
    (is (= (:angle (:ship slower)) (:angle (:ship stock)))
        "turning is untouched")))

(deftest a-restart-keeps-the-acceleration-it-was-played-at
  (let [gentle  (/ game/thrust 2)
        over    (-> (world-with [(still-asteroid :large 512 384)])
                    (game/with-thrust gentle)
                    (assoc :lives 1)
                    (step no-input)
                    (run 1 no-input))
        pressed (step over #{:fire})]
    (is (= :game-over (:phase over)))
    (is (= :playing (:phase pressed)))
    (is (= gentle (:thrust pressed))
        "restarting must not quietly hand a touch player the keyboard figure")))

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

;; --- UFOs -------------------------------------------------------------------

(defn- with-ufo
  "Drop a saucer of the given kind at (x, y), flying right, with its timers
   freshly wound."
  [state kind x y]
  (assoc state :ufo {:x (double x) :y (double y)
                     :vx (game/ufo-speed kind) :vy 0.0
                     :size kind
                     :fire-timer (game/ufo-fire-interval kind)
                     :turn-timer game/ufo-turn-interval
                     :hold-timer 0.0
                     :dodge? nil}))

(deftest a-saucer-eventually-arrives
  (let [s (-> (open-space (game/initial-state 5))
              (assoc :ufo-timer 0.1)
              (run 0.3 no-input))
        {:keys [ufo]} s]
    (is (some? ufo))
    (is (contains? #{:large :small} (:size ufo)))
    (is (or (close? (:x ufo) 0 (* 2 (game/ufo-speed (:size ufo))))
            (close? (:x ufo) game/world-w (* 2 (game/ufo-speed (:size ufo)))))
        "it enters from one of the side edges")
    (is (some #{:ufo-appear} (:events (-> (open-space (game/initial-state 5))
                                          (assoc :ufo-timer 0.0)
                                          (step no-input)))))))

(deftest saucer-choice-follows-the-score
  (is (= :large (game/ufo-kind 0 0.5)) "at zero score the large one always shows up")
  (is (= :small (game/ufo-kind game/small-ufo-only-score 0.99))
      "past the threshold only small ones appear")
  (is (= :small (game/ufo-kind (* 0.9 game/small-ufo-only-score) 0.1))
      "in between, small ones grow commoner"))

(deftest saucer-crosses-and-leaves-without-wrapping
  (let [crossing (-> (open-space (game/initial-state 5))
                     (with-ufo :large 40 300)
                     (run 3 no-input))
        gone     (-> (open-space (game/initial-state 5))
                     (with-ufo :large 40 300)
                     (run 12 no-input)
                     (assoc :ufo-timer 999.0))]
    (is (some? (:ufo crossing)) "still on screen part way across")
    (is (> (:x (:ufo crossing)) 40) "and moving right")
    (is (nil? (:ufo gone)) "it exits the far edge instead of wrapping")))

(deftest saucer-wraps-vertically
  (let [s (-> (open-space (game/initial-state 5))
              (with-ufo :large 500 10)
              (assoc-in [:ufo :vy] -200.0)
              (assoc-in [:ufo :turn-timer] 99.0)
              (run 0.5 no-input))]
    (is (> (:y (:ufo s)) 600) "off the top and back on the bottom")))

(deftest saucer-shoots
  (let [s (-> (open-space (game/initial-state 5))
              (with-ufo :large 500 300)
              (run (+ (game/ufo-fire-interval :large) 0.05) no-input))
        shots (filter #(= :ufo (:from %)) (:bullets s))]
    (is (= 1 (count shots)) "one shot per fire interval")
    (is (some #{:ufo-fire} (:events (-> (open-space (game/initial-state 5))
                                        (with-ufo :large 500 300)
                                        (assoc-in [:ufo :fire-timer] 0.001)
                                        (step no-input)))))))

(deftest a-small-saucer-aims-at-the-ship
  ;; Ship at the centre, saucer directly to its left: an accurate shot flies
  ;; right, i.e. angle near 0.
  (let [s      (-> (world-with [])
                   (assoc :score game/ufo-aim-tighten-by)   ; fully accurate by now
                   (with-ufo :small 200 384)
                   (assoc-in [:ufo :fire-timer] 0.001)
                   (step no-input))
        shot   (first (filter #(= :ufo (:from %)) (:bullets s)))
        angle  (js/Math.atan2 (:vy shot) (:vx shot))]
    (is (close? angle 0 (+ game/ufo-aim-spread-min 0.01)))))

(deftest player-shots-destroy-a-saucer-and-score
  (let [s (-> (world-with [])
              (with-ufo :large 512 184)
              (assoc-in [:ufo :vx] 0.0)
              (step #{:fire})
              (run 0.4 no-input))]
    (is (nil? (:ufo s)))
    (is (= (game/ufo-score :large) (:score s)))
    (is (some #{:bang-ufo} (-> (world-with [])
                               (with-ufo :large 512 184)
                               (assoc-in [:ufo :vx] 0.0)
                               (step #{:fire})
                               (events-during 0.4 no-input))))))

(deftest a-saucer-cannot-shoot-itself-down
  (let [s (-> (world-with [])
              (with-ufo :large 500 300)
              (assoc-in [:ufo :vx] 0.0)
              (assoc :bullets [{:x 500.0 :y 300.0 :vx 0.0 :vy 0.0 :life 1.0 :from :ufo}])
              (step no-input))]
    (is (some? (:ufo s)))))

(deftest saucer-shots-kill-the-ship
  (let [s (-> (world-with [])
              (assoc :bullets [{:x 512.0 :y 384.0 :vx 0.0 :vy 0.0 :life 1.0 :from :ufo}])
              (step no-input))]
    (is (= (dec game/start-lives) (:lives s)))
    (is (= :dead (:phase s)))))

(deftest saucer-shots-break-asteroids-but-do-not-score
  (let [s (-> (world-with [(still-asteroid :large 512 300)])
              (assoc :bullets [{:x 512.0 :y 300.0 :vx 0.0 :vy 0.0 :life 1.0 :from :ufo}])
              (step no-input))]
    (is (= [:medium :medium] (sizes s)))
    (is (= 0 (:score s)) "the player gets nothing for a saucer's stray shot")))

(deftest flying-into-the-ship-or-a-rock-destroys-the-saucer
  (let [rammed (-> (world-with [])
                   (with-ufo :large 512 384)
                   (step no-input))
        rocked (-> (world-with [(still-asteroid :large 500 300)])
                   (with-ufo :large 500 300)
                   (step no-input))]
    (is (= (dec game/start-lives) (:lives rammed)) "the ship dies too")
    (is (nil? (:ufo rocked)))
    (is (some #{:bang-ufo} (:events rocked)))))

(deftest a-level-does-not-end-while-a-saucer-is-around
  (let [held (-> (world-with [])
                 (with-ufo :large 500 300)
                 (step no-input))]
    (is (= :playing (:phase held))))
  (let [ended (-> (world-with []) (step no-input))]
    (is (= :next-level (:phase ended)))))

;; --- Obstacle avoidance -----------------------------------------------------

(defn- head-on
  "A saucer at (270, 384) flying right into a rock parked at (500, 384): close
   enough to be seen at once, far enough that a dodge has time to work."
  [kind decision]
  (-> (world-with [(still-asteroid :large 500 384)])
      (with-ufo kind 270 384)
      (assoc-in [:ufo :dodge?] decision)))

(deftest a-committed-dodge-clears-the-rock
  (let [s (run (head-on :large true) 3.5 no-input)]
    (is (some? (:ufo s)) "it survived the encounter")
    (is (> (:x (:ufo s)) 500) "and carried on past the rock")))

(deftest a-saucer-that-does-not-bother-is-destroyed
  (let [events (events-during (head-on :large false) 3.5 no-input)
        s      (run (head-on :large false) 3.5 no-input)]
    (is (nil? (:ufo s)))
    (is (some #{:bang-ufo} events))))

(deftest the-pilot-decision-is-taken-once-and-kept
  (let [encounter (-> (world-with [(still-asteroid :large 500 384)])
                      (with-ufo :large 270 384)
                      (step no-input))]
    (is (some? (:dodge? (:ufo encounter))) "meeting a rock forces the decision"))
  ;; 0.8 s in, still short of the rock but long past the first decision.
  (let [stubborn (run (head-on :large false) 0.8 no-input)]
    (is (some? (:ufo stubborn)) "not dead yet")
    (is (false? (:dodge? (:ufo stubborn)))
        "a saucer that is not a pilot does not quietly become one")))

(deftest an-empty-sky-provokes-no-decision
  (let [clear (-> (world-with [(still-asteroid :large 500 100)])   ; nowhere near the path
                  (with-ufo :large 270 384)
                  (step no-input))]
    (is (nil? (:dodge? (:ufo clear)))))
  (let [behind (-> (world-with [(still-asteroid :large 500 384)])
                   (with-ufo :large 700 384)      ; already past it, flying away
                   (step no-input))]
    (is (nil? (:dodge? (:ufo behind))) "nothing behind us is a threat")))

(deftest avoidance-predicts-from-relative-motion
  ;; The rock is 250px off the saucer's line right now, so no lane-shaped check
  ;; against present positions would see it — but it is diving fast enough to
  ;; arrive exactly where the saucer will be.
  (let [rock     (assoc (still-asteroid :large 520 134) :vy 200.0)
        drifting (-> (world-with [rock])
                     (with-ufo :large 270 384)
                     (step no-input))]
    (is (< 200 (js/Math.abs (- (:y rock) 384)))
        "the rock really is nowhere near the saucer's line to begin with")
    (is (some? (:dodge? (:ufo drifting)))
        "yet it is seen coming")))

(deftest pilots-are-nearly-all-competent
  (letfn [(pilots [kind]
            (count
             (filter (fn [i]
                       (-> (world-with [(still-asteroid :large 500 384)])
                           (with-ufo kind 270 384)
                           (assoc :seed (bit-or 1 (* i 2654435761)))
                           (step no-input)
                           (get-in [:ufo :dodge?])))
                     (range 400))))]
    (let [large (/ (pilots :large) 400.0)
          small (/ (pilots :small) 400.0)]
      (is (close? large (:large game/ufo-dodge-chance) 0.05))
      (is (close? small (:small game/ufo-dodge-chance) 0.02))
      (is (>= small large) "the small saucer is the better pilot"))))

(deftest saucers-fly-straight-legs-at-three-slopes
  ;; The look matters more than the success rate here: the original's saucer
  ;; flies a Z, not a smooth curve. So every heading must be climb, level or
  ;; dive at one fixed speed, and each leg must last long enough to read as a
  ;; straight line.
  (let [[rocks seed] (game/spawn-wave 4242 (game/asteroids-for-level 5))
        s            (* (game/ufo-speed :large) game/ufo-vertical-ratio)
        ;; let the field drift, then arrive through the game's own spawn logic
        start        (-> (world-with rocks)
                         (assoc :seed seed :invuln 9999.0 :ufo-timer 9999.0)
                         (run 5 no-input)
                         (assoc :ufo-timer 0.0)
                         (step no-input)
                         (assoc :ufo-timer 9999.0)
                         (assoc-in [:ufo :dodge?] true)
                         (assoc-in [:ufo :fire-timer] 9999.0))
        vys          (second
                      (reduce (fn [[st acc] _]
                                (let [st (step st no-input)]
                                  [st (if-let [u (:ufo st)] (conj acc (:vy u)) acc)]))
                              [start []]
                              (range (js/Math.round (* 60 8)))))
        legs         (count (partition-by identity vys))]
    (is (< 100 (count vys)) "the saucer stayed up long enough to judge")
    (is (every? (fn [vy] (some #(close? vy % 1e-9) [(- s) 0.0 s])) vys)
        "every heading is climb, level or dive — no intermediate angles")
    (is (< legs 24)
        (str "over 8 seconds the path should be a handful of straight legs, not "
             "a wobble; got " legs))))

(deftest a-saucer-picks-a-clear-entry-height
  ;; Asteroids enter from the edges too, which is exactly where a saucer appears,
  ;; so arriving at a random height would sometimes mean arriving inside a rock.
  ;; A wall of rocks down the left edge leaves only one gap; the saucer must
  ;; find it.
  (let [wall    (for [y (range 0 768 96) :when (not= y 384)]
                  (still-asteroid :large 0 y))
        arrived (-> (world-with wall)
                    (assoc :ufo-timer 0.0 :invuln 9999.0)
                    (step no-input))
        ufo     (:ufo arrived)]
    (is (some? ufo))
    (is (not-any? (fn [a]
                    (< (js/Math.abs (- (:y a) (:y ufo)))
                       (+ (game/ufo-radius (:size ufo)) (game/asteroid-radius :large))))
                  wall)
        "it came in through the gap, not on top of a rock")))

(deftest a-saucer-usually-crosses-a-crowded-field
  ;; The requirement is not "does it swerve" but "does it get across alive".
  ;; Full-width crossings of a drifting level-5 field, entering through the
  ;; game's own spawn logic, with the pilot decision forced so this measures the
  ;; flying rather than the dice.
  ;;
  ;; Restricting evasion to three headings held for a fixed time — which is what
  ;; keeps the flight path a Z — costs roughly 5-7% of crossings, deliberately.
  ;; The bound here is loose enough not to be flaky and tight enough to catch a
  ;; real regression: before avoidance existed, most crossings ended in a rock.
  (let [trials 40
        deaths (count
                (filter (fn [i]
                          (let [seed      (bit-or 1 (* i 2654435761))
                                [rocks s] (game/spawn-wave seed (game/asteroids-for-level 5))
                                drifted   (-> (world-with rocks)
                                              (assoc :seed s :ufo-timer 9999.0 :invuln 9999.0)
                                              (run 5 no-input))
                                arrived   (-> drifted (assoc :ufo-timer 0.0) (step no-input))]
                            (some #{:bang-ufo}
                                  (-> arrived
                                      (assoc :ufo-timer 9999.0)
                                      (assoc-in [:ufo :dodge?] true)
                                      (assoc-in [:ufo :fire-timer] 9999.0)
                                      (events-during 13 no-input)))))
                        (range trials)))]
    (is (<= deaths 8)
        (str "a competent saucer should get across the great majority of the "
             "time; " deaths " of " trials " flew into a rock"))))

;; --- Heartbeat --------------------------------------------------------------

(deftest the-heartbeat-speeds-up
  (is (close? (game/beat-interval 1 0) game/beat-interval-max 1e-9)
      "level 1 starts at the slowest interval")
  (is (< (game/beat-interval 3 0) (game/beat-interval 1 0))
      "later levels start faster")
  (is (< (game/beat-interval 1 30) (game/beat-interval 1 0))
      "and it accelerates as the level wears on")
  (is (close? (game/beat-interval 20 600) game/beat-interval-min 1e-9)
      "but never faster than the floor"))

(deftest the-heartbeat-alternates-two-thumps
  (let [beats (filter #{:beat-a :beat-b}
                      (events-during (open-space (game/initial-state 3)) 3 no-input))]
    (is (<= 3 (count beats)) "several beats within three seconds")
    (is (every? (fn [[a b]] (not= a b)) (partition 2 1 beats))
        "the two thumps alternate")))

(deftest the-heartbeat-stops-at-game-over
  (let [over (-> (world-with [(still-asteroid :large 512 384)])
                 (assoc :lives 1)
                 (step no-input)
                 (run 2 no-input))]
    (is (= :game-over (:phase over)))
    (is (empty? (filter #{:beat-a :beat-b} (:events over))))))

;; --- Sound events -----------------------------------------------------------

(deftest events-are-emitted-and-then-cleared
  (let [fired (step (open-space (game/initial-state 1)) #{:fire})]
    (is (some #{:fire} (:events fired)))
    (is (not (some #{:fire} (:events (step fired no-input))))
        "each frame reports only its own sounds")))

(deftest each-asteroid-size-bangs-differently
  (doseq [[size event] [[:large :bang-large]
                        [:medium :bang-medium]
                        [:small :bang-small]]]
    (testing (name size)
      (let [events (-> (world-with [(still-asteroid size 512 184)])
                       (step #{:fire})
                       (events-during 0.5 no-input))]
        (is (some #{event} events))))))

(deftest losing-a-ship-and-gaining-a-life-both-make-a-sound
  (let [dead (-> (world-with [(still-asteroid :large 512 384)]) (step no-input))
        life (-> (world-with [(still-asteroid :large 512 184)])
                 (assoc :score (- game/extra-life-every 20))
                 (step #{:fire})
                 (run 0.4 no-input))]
    (is (some #{:ship-explode} (:events dead)))
    (is (= (inc game/start-lives) (:lives life)))))

;; --- Hyperspace -------------------------------------------------------------

(defn- ship-xy [state] [(get-in state [:ship :x]) (get-in state [:ship :y])])

;; open-space, not an empty field: clearing the last rock ends the level, and
;; hyperspace is refused outside :playing.
(deftest hyperspace-moves-the-ship-and-stops-it-dead
  (let [before (-> (world-with []) (open-space) (run 1 #{:thrust}))
        after  (step before #{:hyperspace})]
    (is (not= (ship-xy before) (ship-xy after)) "it went somewhere else")
    (is (<= 0 (get-in after [:ship :x]) game/world-w))
    (is (<= 0 (get-in after [:ship :y]) game/world-h))
    (is (pos? (speed-of (:ship before))) "it was moving beforehand")
    (is (zero? (speed-of (:ship after))) "and arrives at a standstill")
    (is (= (get-in before [:ship :angle]) (get-in after [:ship :angle]))
        "the heading is kept — only where you are changes")
    (is (some #{:hyperspace} (:events after)))))

(deftest hyperspace-is-one-jump-per-press
  (let [s      (open-space (world-with []))
        tapped (-> s (step #{:hyperspace}) (step no-input) (step #{:hyperspace}))]
    (is (= 1 (count (filter #{:hyperspace} (events-during s 1 #{:hyperspace}))))
        "holding the key jumps once, not sixty times")
    (is (some #{:hyperspace} (:events tapped))
        "releasing and pressing again jumps a second time")))

(deftest hyperspace-has-no-safety-net
  ;; The whole point: no clear-area check and no invulnerability, so a jump can
  ;; drop you straight onto a rock. Search the seeds for one that does, and
  ;; check it kills rather than being quietly ignored.
  (let [field  (fn [seed]
                 (let [[rocks s] (game/spawn-wave seed (game/asteroids-for-level 5))]
                   ;; Drift the wave off the edges while untouchable, then drop
                   ;; the shield, so the only thing that can kill is the jump.
                   (-> (world-with rocks)
                       (assoc :seed s :invuln 9999.0)
                       (run 3 no-input)
                       (assoc :invuln 0.0))))
        deaths (count (filter (fn [i]
                                (let [jumped (step (field (bit-or 1 (* i 2654435761)))
                                                   #{:hyperspace})]
                                  (= :dead (:phase jumped))))
                              (range 60)))]
    (is (pos? deaths)
        (str "a jump into a crowded field must sometimes be fatal; " deaths " of 60 were"))
    (is (< deaths 60) "but not always, or it would be useless")))

(deftest a-jump-marks-the-ship-briefly
  (let [s      (open-space (world-with []))
        jumped (step s #{:hyperspace})]
    (is (zero? (:hyper-glow s)) "nothing to mark before a jump")
    (is (pos? (:hyper-glow jumped)) "the ship is marked on arrival")
    (is (< game/hyper-glow-time 1.0)
        "and only briefly — it must not become how the ship normally looks")
    (is (zero? (:hyper-glow (run jumped (+ game/hyper-glow-time 0.1) no-input)))
        "then it is over")
    (is (pos? (:hyper-glow (run jumped (* 0.5 game/hyper-glow-time) no-input)))
        "but not before its time"))
  (testing "it is a marker, not a shield"
    ;; Same seed, same everything: the jump must not change where the ship goes
    ;; or how it behaves, only how it is drawn.
    (let [glowing (-> (open-space (world-with [])) (step #{:hyperspace}))
          later   (run glowing 0.3 #{:thrust})]
      (is (pos? (speed-of (:ship later))) "it still accelerates normally")
      (is (zero? (:invuln later)) "and it is not invulnerable while marked"))))

(deftest hyperspace-is-reproducible
  (let [jump #(step (open-space (world-with [])) #{:hyperspace})]
    (is (= (ship-xy (jump)) (ship-xy (jump)))
        "same seed, same destination — the randomness still goes through :seed")))

(deftest hyperspace-only-works-while-playing
  (let [dead (-> (world-with [(still-asteroid :large 512 384)]) (step no-input))
        moved (step dead #{:hyperspace})]
    (is (= :dead (:phase dead)))
    (is (= (ship-xy dead) (ship-xy moved)) "no escaping from the grave"))
  (let [over (-> (world-with [(still-asteroid :large 512 384)])
                 (assoc :lives 1)
                 (step no-input))]
    (is (= :game-over (:phase over)))
    (is (empty? (filter #{:hyperspace} (:events (step over #{:hyperspace})))))))

;; --- End to end -------------------------------------------------------------

(deftest the-whole-game-is-deterministic
  (let [play #(run (game/initial-state 4242) 3 #{:thrust :fire})]
    (is (= (play) (play))
        "one seed plus one input sequence always produces an identical result")))
