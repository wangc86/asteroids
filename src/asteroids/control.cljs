(ns asteroids.control
  "Turning a virtual stick into the very same action keywords the keyboard
   produces, so asteroids.game never learns that touch exists.

   Pure, like asteroids.game and asteroids.mode: the caller passes in the ship's
   current heading and where the finger is, and gets back a set of actions.
   That is what makes the feel of the touch controls testable under node.")

(def ^:const tau (* 2 js/Math.PI))

;; Feel parameters. These want a playtest on a real device before they are
;; treated as settled — a mouse on a laptop is not a thumb on glass.
(def ^:const stick-max 55)          ; px of drag for full deflection
(def ^:const dead-zone 0.2)         ; fraction of full deflection that still counts as "no input"
(def ^:const thrust-threshold 0.55) ; push past this and the engine lights
(def ^:const thrust-align 0.9)      ; radians; only thrust when roughly facing where you pushed
;; Must exceed one frame of rotation (200 deg/s / 60 = 3.33 deg), or the ship
;; oscillates around the heading it is trying to hold.
(def ^:const turn-epsilon 0.09)     ; radians, about 5 degrees

(defn stick-vector
  "Finger offset in pixels from where it went down, as a vector whose length is
   0 at the origin and 1 at stick-max. Dragging further than stick-max keeps the
   direction but stops growing, so there is no way to over-push."
  [dx dy]
  (let [mag (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? mag)
      [0.0 0.0]
      (let [scaled (min 1.0 (/ mag stick-max))]
        [(* scaled (/ dx mag))
         (* scaled (/ dy mag))]))))

(defn shortest-angle
  "Fold an angle difference into [-pi, pi], so turning always takes the short way
   round rather than the long way."
  [a]
  (- (mod (+ a js/Math.PI) tau) js/Math.PI))

(defn stick->inputs
  "The actions implied by holding the stick at (sx, sy) while the ship points
   along ship-angle.

   The stick names a heading, not a velocity: the ship still turns at
   rotate-speed and still only accelerates under thrust, so inertia and drag are
   exactly as they are on the keyboard. What the player is spared is aiming the
   nose by hand.

   Thrust also needs the nose to be roughly pointing where the stick is pushed.
   Without that, pushing away from the ship's heading would accelerate it
   backwards while it turned, which reads as the controls being broken."
  [ship-angle sx sy]
  (let [mag (js/Math.sqrt (+ (* sx sx) (* sy sy)))]
    (if (< mag dead-zone)
      #{}
      (let [diff (shortest-angle (- (js/Math.atan2 sy sx) ship-angle))]
        (cond-> #{}
          (> diff turn-epsilon)     (conj :right)
          (< diff (- turn-epsilon)) (conj :left)
          (and (>= mag thrust-threshold)
               (< (js/Math.abs diff) thrust-align))
          (conj :thrust))))))
