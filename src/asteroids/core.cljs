(ns asteroids.core
  "Every side effect lives here: canvas drawing, keyboard events, and the
   requestAnimationFrame loop. The rules themselves are in asteroids.game,
   which never touches the browser."
  (:require [asteroids.game :as game]
            [asteroids.mode :as mode]
            [asteroids.sound :as sound]
            [asteroids.touch :as touch]))

;; defonce keeps the game state alive across hot reloads.
(defonce state (atom nil))
(defonce keys-down (atom #{}))
(defonce started? (atom false))
(defonce last-ts (atom nil))
;; Held upright in touch mode: the game waits rather than killing you behind a
;; prompt you cannot see past.
(defonce paused? (atom false))

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
    (if @paused?
      ;; Keep drawing so the field is still there behind the prompt, but do not
      ;; advance anything and do not leave a sound running.
      (do (draw! (.getContext el "2d") @state)
          (sound/thruster! false)
          (sound/saucer! nil))
      (do
        ;; Both input layers speak the same vocabulary, so game cannot tell them
        ;; apart. In desktop mode the touch side is simply always empty.
        (swap! state game/tick dt
               (into @keys-down (touch/take-inputs! (get-in @state [:ship :angle]))))
        (let [s @state]
          (draw! (.getContext el "2d") s)
          ;; game decided what happened; sound decides what it sounds like.
          (doseq [event (:events s)]
            (sound/play! event))
          (sound/thruster! (and (game/playing? s) (:thrusting? (:ship s))))
          (sound/saucer! (:size (:ufo s)))))))
  (js/requestAnimationFrame frame!))

;; --- Page shell: choosing and remembering a control mode --------------------

(defn- el-by-id [id] (js/document.getElementById id))

(defn- url-mode []
  (.get (js/URLSearchParams. js/window.location.search) "mode"))

;; Storage throws in some privacy modes, so every touch of it is guarded and a
;; failure just means "not remembered".
(defn- saved-mode []
  (try (.getItem js/window.localStorage mode/storage-key)
       (catch :default _ nil)))

(defn- remember-mode! [m]
  (try (.setItem js/window.localStorage mode/storage-key (mode/->str m))
       (catch :default _ nil)))

(defn- coarse-pointer? []
  (.-matches (js/window.matchMedia "(pointer: coarse)")))

(defn- show! [id on?]
  (.toggle (.-classList (el-by-id id)) "show" on?))

(defn- watch-orientation!
  "Touch play only makes sense sideways. Pausing while upright means the prompt
   is not covering a game that is quietly killing you."
  []
  (let [portrait (js/window.matchMedia "(orientation: portrait)")
        apply!   (fn []
                   (let [p? (.-matches portrait)]
                     (.toggle (.-classList js/document.body) "portrait" p?)
                     (reset! paused? (and p? (.contains (.-classList js/document.body)
                                                        "mode-touch")))))]
    (.addEventListener portrait "change" apply!)
    (apply!)))

(defn- start-game!
  "Apply the chosen mode and get the loop going. Called either straight away,
   when the mode is already known, or from the chooser."
  [m]
  (let [classes (.-classList js/document.body)]
    (.remove classes "mode-desktop" "mode-touch")
    (.add classes (str "mode-" (mode/->str m))))
  (show! "chooser" false)
  (watch-orientation!)
  (when (nil? @state)
    (reset! state (game/initial-state)))
  ;; Start the loop and the listeners exactly once; after a hot reload the
  ;; re-resolution inside frame! picks up the new code.
  (when-not @started?
    (reset! started? true)
    (init-input!)
    (when (= :touch m)
      (touch/init!))
    (js/requestAnimationFrame frame!)))

(defn- choose! [m]
  ;; A tap on these buttons is a real user gesture, which is exactly what the
  ;; browser wants before it will let us start any audio.
  (sound/init!)
  (remember-mode! m)
  (start-game! m))

(defn- other-mode []
  (if (.contains (.-classList js/document.body) "mode-touch") :desktop :touch))

(defn- init-shell! []
  (.addEventListener (el-by-id "choose-desktop") "click" #(choose! :desktop))
  (.addEventListener (el-by-id "choose-touch") "click" #(choose! :touch))
  ;; Switching mid-game is a big enough change to be worth confirming; a stray
  ;; tap on a phone should not throw away a run.
  (.addEventListener (el-by-id "switch-mode") "click"
                     (fn []
                       (set! (.-textContent (el-by-id "switch-question"))
                             (str "Switch to "
                                  (if (= :touch (other-mode))
                                    "touch controls?"
                                    "keyboard controls?")))
                       (show! "switch-confirm" true)))
  (.addEventListener (el-by-id "switch-cancel") "click" #(show! "switch-confirm" false))
  (.addEventListener (el-by-id "switch-ok") "click"
                     (fn []
                       (remember-mode! (other-mode))
                       ;; Drop any ?mode= from the URL on the way out: an
                       ;; explicit switch has to beat a link someone shared,
                       ;; otherwise the override would just reload us back into
                       ;; the mode we asked to leave.
                       (let [url (js/URL. js/window.location.href)]
                         (.delete (.-searchParams url) "mode")
                         ;; Reload rather than tear the input layer down by
                         ;; hand: a clean slate is worth more than the run.
                         (.replace js/window.location (.-href url))))))

(defn init! []
  (init-shell!)
  (if-let [m (mode/resolve-mode (url-mode) (saved-mode))]
    (start-game! m)
    (do
      ;; Nothing decided yet, so ask. The browser's guess is only a hint on the
      ;; button; it never chooses for the player.
      (.setAttribute (el-by-id (str "choose-" (mode/->str (mode/suggested (coarse-pointer?)))))
                     "data-suggested" "")
      (show! "chooser" true))))

(defn after-load! []
  ;; After a recompile the canvas transform and the game state are both still
  ;; there, so there is nothing to do.
  (js/console.log "reloaded"))
