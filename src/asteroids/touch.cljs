(ns asteroids.touch
  "Pointer events for the touch layout: a virtual stick in the left strip, taps
   to fire in the right one.

   Pointer events rather than touch events, and pointer ids throughout, because
   steering and firing have to work at the same time with two thumbs. Pointer
   capture keeps a drag alive after the finger wanders off the strip it started
   on."
  (:require [asteroids.control :as control]))

;; defonce so a hot reload does not lose the finger currently on the glass.
(defonce stick (atom nil))        ; {:id :dx :dy}, pixels from the ring's centre
(defonce fire-down (atom #{}))    ; pointer ids currently held in the fire zone
(defonce fire-latch (atom false)) ; a tap seen since the last frame
(defonce started? (atom false))

(defn- el [id] (js/document.getElementById id))

(defn- offset-from-centre
  "Where the finger is relative to the middle of the strip, which is where the
   ring is drawn. The ring is a fixed landmark rather than something that
   appears under the thumb, so direction is absolute: touching near the top of
   the strip means up, wherever the previous touch happened to be."
  [pad e]
  (let [r (.getBoundingClientRect pad)]
    [(- (.-clientX e) (.-left r) (/ (.-width r) 2))
     (- (.-clientY e) (.-top r) (/ (.-height r) 2))]))

(defn- capture!
  "Keep the drag alive after the finger wanders off the strip. This is an
   enhancement, not a requirement — if the browser refuses, steering must still
   work, so a failure here is swallowed rather than aborting the handler."
  [pad e]
  (try (.setPointerCapture pad (.-pointerId e))
       (catch :default _ nil)))

(defn- render-stick!
  "The ring never moves; only the knob does. CSS parks both on the strip's
   centre, so all this has to add is the offset — which keeps it correct through
   a rotation or a resize without recomputing anything."
  []
  (let [[sx sy] (if-let [{:keys [dx dy]} @stick]
                  (control/stick-vector dx dy)
                  [0.0 0.0])]           ; no finger: the knob rests in the middle
    (set! (.-transform (.-style (el "stick-knob")))
          (str "translate(-50%,-50%) translate("
               (* sx control/stick-max) "px," (* sy control/stick-max) "px)"))))

(defn- release-stick! []
  (reset! stick nil)
  (render-stick!))

(defn- init-move-pad! []
  (let [pad (el "pad-left")]
    (.addEventListener
     pad "pointerdown"
     (fn [e]
       (.preventDefault e)
       ;; Anywhere in the strip steers, including well outside the ring — the
       ;; ring shows where centre is, it is not a target you have to hit.
       (let [[dx dy] (offset-from-centre pad e)]
         (capture! pad e)
         (reset! stick {:id (.-pointerId e) :dx dx :dy dy})
         (render-stick!))))
    (.addEventListener
     pad "pointermove"
     (fn [e]
       (when (= (.-pointerId e) (:id @stick))
         (let [[dx dy] (offset-from-centre pad e)]
           (swap! stick assoc :dx dx :dy dy)
           (render-stick!)))))
    (doseq [event ["pointerup" "pointercancel"]]
      (.addEventListener pad event
                         (fn [e]
                           (when (= (.-pointerId e) (:id @stick))
                             (release-stick!)))))))

(defn- init-fire-pad! []
  (let [pad (el "pad-right")]
    (.addEventListener
     pad "pointerdown"
     (fn [e]
       (.preventDefault e)
       (capture! pad e)
       (swap! fire-down conj (.-pointerId e))
       ;; Latch the tap. A tap that starts and ends between two frames would
       ;; otherwise never be seen, and firing is edge-triggered.
       (reset! fire-latch true)))
    (doseq [event ["pointerup" "pointercancel"]]
      (.addEventListener pad event
                         (fn [e] (swap! fire-down disj (.-pointerId e)))))))

(defn- size-ring!
  "Draw the ring at exactly stick-max, so the rim really is full deflection.
   Sizing it here rather than in the stylesheet keeps the picture and the number
   from drifting apart."
  []
  (doseq [[id r] [["stick-base" control/stick-max]
                  ["stick-knob" (/ control/stick-max 2.4)]]]
    (let [style (.-style (el id))]
      (set! (.-width style) (str (* 2 r) "px"))
      (set! (.-height style) (str (* 2 r) "px")))))

(defn init! []
  (when-not @started?
    (reset! started? true)
    (size-ring!)
    (render-stick!)
    (init-move-pad!)
    (init-fire-pad!)
    ;; A finger that leaves the window never sends pointerup, which would leave
    ;; the ship thrusting forever.
    (js/window.addEventListener "blur"
                                (fn [_]
                                  (release-stick!)
                                  (reset! fire-down #{})))))

(defn take-inputs!
  "The actions the fingers are asking for this frame, in the same vocabulary the
   keyboard uses. Consumes the tap latch, hence the bang."
  [ship-angle]
  (let [fire?  (or @fire-latch (seq @fire-down))
        moving (if-let [{:keys [dx dy]} @stick]
                 (let [[sx sy] (control/stick-vector dx dy)]
                   (control/stick->inputs ship-angle sx sy))
                 #{})]
    (reset! fire-latch false)
    (cond-> moving fire? (conj :fire))))
