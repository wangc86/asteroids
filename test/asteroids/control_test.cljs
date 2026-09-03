(ns asteroids.control-test
  "The virtual stick is where the touch version could quietly stop playing by
   the same rules as the keyboard, so its mapping is pinned down here rather
   than judged by feel on a phone."
  (:require [cljs.test :refer [deftest is testing]]
            [asteroids.control :as control]))

(def ^:const up (- (/ js/Math.PI 2)))    ; screen y grows downward
(def ^:const down (/ js/Math.PI 2))
(def ^:const right 0.0)
(def ^:const left js/Math.PI)

(defn- at
  "Hold the stick at a heading, deflected by `frac` of full travel."
  [angle frac]
  [(* frac (js/Math.cos angle)) (* frac (js/Math.sin angle))])

;; --- The stick itself -------------------------------------------------------

(deftest deflection-is-normalised-and-clamped
  (let [[x y] (control/stick-vector control/stick-max 0)]
    (is (< (js/Math.abs (- x 1.0)) 1e-9) "full travel is 1")
    (is (< (js/Math.abs y) 1e-9)))
  (let [[x _] (control/stick-vector (* 10 control/stick-max) 0)]
    (is (< (js/Math.abs (- x 1.0)) 1e-9)
        "dragging further keeps the direction but stops growing"))
  (is (= [0.0 0.0] (control/stick-vector 0 0)) "no drag, no vector")
  (let [[x y] (control/stick-vector (/ control/stick-max 2) 0)]
    (is (< (js/Math.abs (- x 0.5)) 1e-9))
    (is (zero? y))))

(deftest angles-fold-to-the-short-way-round
  (is (< (js/Math.abs (control/shortest-angle 0)) 1e-9))
  (is (< (control/shortest-angle (* 1.75 js/Math.PI)) 0)
        "350 degrees clockwise is really 10 degrees anticlockwise")
  (is (pos? (control/shortest-angle (* -1.75 js/Math.PI))))
  (doseq [a [-7.0 -3.0 0.5 3.0 7.0 20.0]]
    (is (<= (- js/Math.PI) (control/shortest-angle a) js/Math.PI))))

;; --- Turning ----------------------------------------------------------------

(deftest a-light-touch-does-nothing
  (testing "below the dead zone the stick is ignored, so a resting thumb does not steer"
    (is (= #{} (apply control/stick->inputs up (at down 0.0))))
    (is (= #{} (apply control/stick->inputs up (at down (* 0.9 control/dead-zone)))))))

(deftest the-ship-turns-towards-the-stick
  (testing "ship pointing up"
    (is (contains? (apply control/stick->inputs up (at right 1.0)) :right)
        "push right, turn clockwise")
    (is (contains? (apply control/stick->inputs up (at left 1.0)) :left)
        "push left, turn anticlockwise"))
  (testing "ship pointing down, so the same push turns the other way"
    (is (contains? (apply control/stick->inputs down (at right 1.0)) :left))
    (is (contains? (apply control/stick->inputs down (at left 1.0)) :right))))

(deftest already-aligned-means-no-turning
  (let [actions (apply control/stick->inputs up (at up 1.0))]
    (is (not (contains? actions :left)))
    (is (not (contains? actions :right)))
    (is (contains? actions :thrust) "just thrust straight ahead")))

(deftest it-does-not-hunt-around-the-target
  (testing "within turn-epsilon the ship holds still rather than oscillating"
    (doseq [off [0.0 0.05 -0.05 0.08 -0.08]]
      (let [actions (apply control/stick->inputs up (at (+ up off) 1.0))]
        (is (not (contains? actions :left)) (str "offset " off))
        (is (not (contains? actions :right)) (str "offset " off)))))
  (testing "turn-epsilon covers more than one frame of rotation at 60 fps"
    ;; rotate-speed is 200 deg/s, so a frame is 3.33 deg = 0.058 rad
    (is (> control/turn-epsilon (/ (* 200 (/ js/Math.PI 180)) 60)))))

(deftest turning-always-takes-the-short-way
  (testing "target just anticlockwise of the nose turns left, not most of the way round"
    (is (contains? (apply control/stick->inputs 3.0 (at -3.0 1.0)) :right)
        "3.0 to -3.0 rad is a short hop across pi, not a long sweep back")))

;; --- Thrust -----------------------------------------------------------------

(deftest thrust-needs-a-firm-push
  (is (not (contains? (apply control/stick->inputs up (at up (* 0.9 control/thrust-threshold)))
                      :thrust))
      "a gentle push aims without accelerating")
  (is (contains? (apply control/stick->inputs up (at up 1.0)) :thrust)))

(deftest thrust-waits-until-the-nose-comes-round
  (testing "pushing away from where the ship points turns it, but does not fire the engine"
    (let [actions (apply control/stick->inputs up (at down 1.0))]
      (is (not (contains? actions :thrust))
          "otherwise a full push would accelerate the ship backwards while it turned")
      (is (or (contains? actions :left) (contains? actions :right))
          "it does still turn to face that way")))
  (testing "once roughly aligned the engine lights"
    (is (contains? (apply control/stick->inputs up (at (+ up (* 0.5 control/thrust-align)) 1.0))
                   :thrust))
    (is (not (contains? (apply control/stick->inputs up (at (+ up (* 1.5 control/thrust-align)) 1.0))
                        :thrust)))))

;; --- The whole vocabulary ---------------------------------------------------

(deftest it-only-ever-produces-keyboard-actions
  (let [seen (reduce (fn [acc [angle frac]]
                       (into acc (apply control/stick->inputs up (at angle frac))))
                     #{}
                     (for [angle (range 0 6.3 0.2)
                           frac  [0.0 0.3 0.6 1.0]]
                       [angle frac]))]
    (is (every? #{:left :right :thrust} seen)
        "game must not be able to tell touch from keyboard")
    (is (not (contains? seen :fire)) "firing comes from the other strip, not the stick")))
