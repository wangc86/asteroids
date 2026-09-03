(ns asteroids.touch
  "Pointer events for the touch layout: a virtual stick in the left strip, taps
   to fire in the right one.

   Pointer events rather than touch events, and pointer ids throughout, because
   steering and firing have to work at the same time with two thumbs. Pointer
   capture keeps a drag alive after the finger wanders off the strip it started
   on."
  (:require [asteroids.control :as control]))

;; defonce so a hot reload does not lose the finger currently on the glass.
(defonce stick (atom nil))        ; {:id :ox :oy :dx :dy}, pad-relative pixels
(defonce fire-down (atom #{}))    ; pointer ids currently held in the fire zone
(defonce fire-latch (atom false)) ; a tap seen since the last frame
(defonce started? (atom false))

(defn- el [id] (js/document.getElementById id))

(defn- pad-xy [pad e]
  (let [r (.getBoundingClientRect pad)]
    [(- (.-clientX e) (.-left r))
     (- (.-clientY e) (.-top r))]))

(defn- capture!
  "Keep the drag alive after the finger wanders off the strip. This is an
   enhancement, not a requirement — if the browser refuses, steering must still
   work, so a failure here is swallowed rather than aborting the handler."
  [pad e]
  (try (.setPointerCapture pad (.-pointerId e))
       (catch :default _ nil)))

(defn- place! [node x y]
  (set! (.-transform (.-style node)) (str "translate(" x "px," y "px)")))

(defn- render-stick! []
  (let [base (el "stick-base")
        knob (el "stick-knob")]
    (if-let [{:keys [ox oy dx dy]} @stick]
      (let [[sx sy] (control/stick-vector dx dy)]
        (set! (.-display (.-style base)) "block")
        (set! (.-display (.-style knob)) "block")
        (place! base ox oy)
        ;; Draw the knob from the clamped vector, so it never flies off the base
        ;; however far the finger travels.
        (place! knob
                (+ ox (* sx control/stick-max))
                (+ oy (* sy control/stick-max))))
      (do (set! (.-display (.-style base)) "none")
          (set! (.-display (.-style knob)) "none")))))

(defn- release-stick! []
  (reset! stick nil)
  (render-stick!))

(defn- init-move-pad! []
  (let [pad (el "pad-left")]
    (.addEventListener
     pad "pointerdown"
     (fn [e]
       (.preventDefault e)
       ;; Wherever the finger lands is the centre of the stick. A fixed base
       ;; would be a poor fit for a strip this narrow, and means looking down.
       (let [[x y] (pad-xy pad e)]
         (capture! pad e)
         (reset! stick {:id (.-pointerId e) :ox x :oy y :dx 0.0 :dy 0.0})
         (render-stick!))))
    (.addEventListener
     pad "pointermove"
     (fn [e]
       (when (= (.-pointerId e) (:id @stick))
         (let [[x y] (pad-xy pad e)
               {:keys [ox oy]} @stick]
           (swap! stick assoc :dx (- x ox) :dy (- y oy))
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

(defn init! []
  (when-not @started?
    (reset! started? true)
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
