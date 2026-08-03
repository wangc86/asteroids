(ns asteroids.core
  "Every side effect lives here: canvas drawing, keyboard events, and the
   requestAnimationFrame loop. The rules themselves are in asteroids.game,
   which never touches the browser."
  (:require [asteroids.game :as game]
            [asteroids.sound :as sound]))

;; defonce keeps the game state alive across hot reloads.
(defonce state (atom nil))
(defonce keys-down (atom #{}))
(defonce started? (atom false))
(defonce last-ts (atom nil))

;; --- Drawing ----------------------------------------------------------------

(defn- draw-ship-body! [ctx]
  (.beginPath ctx)
  (.moveTo ctx game/ship-nose 0)
  (.lineTo ctx game/ship-tail game/ship-half-width)
  (.lineTo ctx game/ship-notch 0)
  (.lineTo ctx game/ship-tail (- game/ship-half-width))
  (.closePath ctx)
  (.stroke ctx))

(defn- draw-flame! [ctx]
  (.beginPath ctx)
  (.moveTo ctx (- game/ship-notch 1) 4)
  (.lineTo ctx (- game/ship-tail 7) 0)
  (.lineTo ctx (- game/ship-notch 1) -4)
  (.stroke ctx))

(defn- draw-ship! [ctx ship t x y]
  (.save ctx)
  (.translate ctx x y)
  (.rotate ctx (:angle ship))
  (draw-ship-body! ctx)
  ;; The flame blinks 10 times a second: like the original, thrust is shown by
  ;; flicker rather than a steady flame.
  (when (and (:thrusting? ship) (< (mod (* t 20) 2) 1))
    (draw-flame! ctx))
  (.restore ctx))

(defn- draw-asteroid! [ctx {:keys [points]} x y]
  (.save ctx)
  (.translate ctx x y)
  (.beginPath ctx)
  (let [p0 (nth points 0)]
    (.moveTo ctx (nth p0 0) (nth p0 1)))
  (doseq [p (subvec points 1)]
    (.lineTo ctx (nth p 0) (nth p 1)))
  (.closePath ctx)
  (.stroke ctx)
  (.restore ctx))

(defn- draw-bullet! [ctx x y]
  (.fillRect ctx (- x 1.5) (- y 1.5) 3 3))

(defn- draw-ufo!
  "The classic saucer: a flattened hexagonal hull with a dome on top and a line
   across each seam. Drawn from the radius so both sizes share one outline."
  [ctx size x y]
  (let [r (game/ufo-radius size)]
    (.save ctx)
    (.translate ctx x y)
    (.beginPath ctx)
    (.moveTo ctx (- r) 0)
    (.lineTo ctx (* -0.45 r) (* -0.36 r))
    (.lineTo ctx (* 0.45 r) (* -0.36 r))
    (.lineTo ctx r 0)
    (.lineTo ctx (* 0.45 r) (* 0.36 r))
    (.lineTo ctx (* -0.45 r) (* 0.36 r))
    (.closePath ctx)
    (.stroke ctx)
    ;; the dome
    (.beginPath ctx)
    (.moveTo ctx (* -0.45 r) (* -0.36 r))
    (.lineTo ctx (* -0.2 r) (* -0.66 r))
    (.lineTo ctx (* 0.2 r) (* -0.66 r))
    (.lineTo ctx (* 0.45 r) (* -0.36 r))
    (.stroke ctx)
    ;; the two hull seams
    (.beginPath ctx)
    (.moveTo ctx (- r) 0)
    (.lineTo ctx r 0)
    (.stroke ctx)
    (.restore ctx)))

(defn- wrap-coords
  "When an object straddles an edge, also give its mirrored coordinate on the
   opposite side, so crossing the border looks like sliding across rather than
   teleporting."
  [v limit margin]
  (cond
    (< v margin)           [v (+ v limit)]
    (> v (- limit margin)) [v (- v limit)]
    :else                  [v]))

(defn- draw-wrapped! [ctx x y margin draw-one!]
  (doseq [px (wrap-coords x game/world-w margin)
          py (wrap-coords y game/world-h margin)]
    (draw-one! px py)))

;; --- HUD --------------------------------------------------------------------

;; The score is drawn every frame, but (str n) only runs when the score
;; actually changes — no string work in the render loop, as CLAUDE.md requires.
(defonce score-cache (atom {:n -1 :s ""}))

(defn- score-text [n]
  (let [cached @score-cache]
    (if (= n (:n cached))
      (:s cached)
      (let [s (str n)]
        (reset! score-cache {:n n :s s})
        s))))

(defn- draw-life-icon!
  "Lives are shown as little ships, the same way the original does it."
  [ctx x y]
  (.save ctx)
  (.translate ctx x y)
  (.rotate ctx (- (/ js/Math.PI 2)))     ; point up
  (.scale ctx 0.7 0.7)
  (draw-ship-body! ctx)
  (.restore ctx))

(defn- draw-hud! [ctx {:keys [score lives phase]}]
  (set! (.-font ctx) "30px ui-monospace, Consolas, monospace")
  (set! (.-textAlign ctx) "left")
  (.fillText ctx (score-text score) 28 46)
  (dotimes [i lives]
    (draw-life-icon! ctx (+ 36 (* i 26)) 78))
  (when (= :game-over phase)
    (set! (.-textAlign ctx) "center")
    (.fillText ctx "GAME OVER" (/ game/world-w 2) (- (/ game/world-h 2) 20))
    (set! (.-font ctx) "20px ui-monospace, Consolas, monospace")
    (.fillText ctx "PRESS SPACE" (/ game/world-w 2) (+ (/ game/world-h 2) 20))))

;; --- The whole frame --------------------------------------------------------

(defn- ship-visible?
  "No ship while dead or after game over; while invulnerable it blinks 4 times a
   second so the player can see that damage has not started counting yet."
  [{:keys [phase invuln t]}]
  (and (#{:playing :next-level} phase)
       (or (zero? invuln) (< (mod (* t 8) 2) 1))))

(defn draw! [ctx {:keys [ship asteroids bullets ufo t] :as state}]
  (.clearRect ctx 0 0 game/world-w game/world-h)
  (set! (.-strokeStyle ctx) "#fff")
  (set! (.-fillStyle ctx) "#fff")
  (set! (.-lineWidth ctx) 2)
  (doseq [a asteroids]
    (draw-wrapped! ctx (:x a) (:y a) (game/asteroid-radius (:size a))
                   (fn [x y] (draw-asteroid! ctx a x y))))
  (doseq [b bullets]
    (draw-wrapped! ctx (:x b) (:y b) 2
                   (fn [x y] (draw-bullet! ctx x y))))
  (when ufo
    ;; A saucer wraps vertically only, so it needs no horizontal mirror.
    (draw-wrapped! ctx (:x ufo) (:y ufo) (game/ufo-radius (:size ufo))
                   (fn [x y] (draw-ufo! ctx (:size ufo) x y))))
  (when (ship-visible? state)
    (draw-wrapped! ctx (:x ship) (:y ship) game/ship-nose
                   (fn [x y] (draw-ship! ctx ship t x y))))
  (draw-hud! ctx state))

;; --- Input ------------------------------------------------------------------

;; .-code rather than .-key, so a different keyboard layout does not break it.
(def key->action
  {"ArrowLeft"  :left   "KeyA"  :left
   "ArrowRight" :right  "KeyD"  :right
   "ArrowUp"    :thrust "KeyW"  :thrust
   "Space"      :fire})

(defn- init-input! []
  (js/window.addEventListener
   "keydown"
   (fn [e]
     ;; Browsers will not start an AudioContext without a user gesture, so the
     ;; first key press is where audio comes to life.
     (sound/init!)
     (when (= "KeyM" (.-code e))
       (sound/toggle-mute!))
     (when-let [action (key->action (.-code e))]
       ;; Stop the arrow keys and space from scrolling the page.
       (.preventDefault e)
       (swap! keys-down conj action))))
  (js/window.addEventListener
   "keyup"
   (fn [e]
     (when-let [action (key->action (.-code e))]
       (swap! keys-down disj action))))
  ;; A window that loses focus never sends keyup; without this the ship would be
  ;; left spinning on a stuck key.
  (js/window.addEventListener "blur" (fn [_] (reset! keys-down #{}))))

;; --- Canvas and loop --------------------------------------------------------

(defn canvas [] (js/document.getElementById "game"))

(defn ensure-size!
  "Match the canvas buffer to its displayed size (including devicePixelRatio)
   and scale the context, so the drawing side always works in world-w × world-h
   coordinates. Does nothing when the size has not changed."
  [el]
  (let [dpr (or js/window.devicePixelRatio 1)
        w   (js/Math.round (* dpr (.-clientWidth el)))
        h   (js/Math.round (* dpr (.-clientHeight el)))]
    ;; A hidden tab reports clientWidth 0; leave things alone until layout exists.
    (when (and (pos? w) (pos? h)
               (or (not= w (.-width el)) (not= h (.-height el))))
      (set! (.-width el) w)
      (set! (.-height el) h)
      (.setTransform (.getContext el "2d")
                     (/ w game/world-w) 0
                     0 (/ h game/world-h)
                     0 0))))

(defn frame! [ts]
  (let [prev (or @last-ts ts)
        ;; Returning to a backgrounded tab produces a huge dt; cap it so nothing
        ;; teleports straight through anything else.
        dt   (min 0.05 (/ (- ts prev) 1000.0))
        el   (canvas)]
    (reset! last-ts ts)
    (ensure-size! el)
    (swap! state game/tick dt @keys-down)
    (let [s @state]
      (draw! (.getContext el "2d") s)
      ;; game decided what happened; sound decides what it sounds like.
      (doseq [event (:events s)]
        (sound/play! event))
      (sound/thruster! (and (game/playing? s) (:thrusting? (:ship s))))
      (sound/saucer! (:size (:ufo s)))))
  (js/requestAnimationFrame frame!))

(defn init! []
  (when (nil? @state)
    (reset! state (game/initial-state)))
  ;; Start the loop and the listeners exactly once; after a hot reload the
  ;; re-resolution inside frame! picks up the new code.
  (when-not @started?
    (reset! started? true)
    (init-input!)
    (js/requestAnimationFrame frame!)))

(defn after-load! []
  ;; After a recompile the canvas transform and the game state are both still
  ;; there, so there is nothing to do.
  (js/console.log "reloaded"))
