(ns asteroids.core)

;; 遊戲一律用這組邏輯座標，畫布實際像素由 resize! 換算，
;; 所以視窗大小改變時遊戲數值完全不受影響。
(def ^:const world-w 1024)
(def ^:const world-h 768)

;; hot reload 時 defonce 讓遊戲狀態存活下來
(defonce state (atom nil))
(defonce started? (atom false))
(defonce last-ts (atom nil))

(defn initial-state []
  {:dot {:x (/ world-w 2)
         :y (/ world-h 2)
         :vx 140.0
         :vy 90.0}})

;; --- 純函數：推進一幀 ------------------------------------------------------

(defn wrap
  "螢幕環繞：超出邊界就從另一側出現。"
  [v limit]
  (cond
    (< v 0)      (+ v limit)
    (> v limit)  (- v limit)
    :else        v))

(defn move [{:keys [x y vx vy] :as dot} dt]
  (assoc dot
         :x (wrap (+ x (* vx dt)) world-w)
         :y (wrap (+ y (* vy dt)) world-h)))

(defn tick [state dt]
  (update state :dot move dt))

;; --- Side effect：繪圖 -----------------------------------------------------

(defn draw! [ctx {:keys [dot]}]
  (.clearRect ctx 0 0 world-w world-h)
  (set! (.-strokeStyle ctx) "#fff")
  (set! (.-lineWidth ctx) 2)
  (.beginPath ctx)
  (.arc ctx (:x dot) (:y dot) 3 0 (* 2 js/Math.PI))
  (.stroke ctx))

;; --- Canvas / 迴圈 ---------------------------------------------------------

(defn canvas [] (js/document.getElementById "game"))

(defn ensure-size!
  "把畫布緩衝區對齊實際顯示尺寸（含 devicePixelRatio），並縮放 context，
   讓繪圖端永遠使用 world-w × world-h 座標。尺寸沒變就什麼都不做。"
  [el]
  (let [dpr (or js/window.devicePixelRatio 1)
        w   (js/Math.round (* dpr (.-clientWidth el)))
        h   (js/Math.round (* dpr (.-clientHeight el)))]
    ;; 分頁隱藏時 clientWidth 會是 0，這時先不動，等版面出來再說
    (when (and (pos? w) (pos? h)
               (or (not= w (.-width el)) (not= h (.-height el))))
      (set! (.-width el) w)
      (set! (.-height el) h)
      (.setTransform (.getContext el "2d")
                     (/ w world-w) 0
                     0 (/ h world-h)
                     0 0))))

(defn frame! [ts]
  (let [prev (or @last-ts ts)
        ;; 分頁切回來時 dt 會很大，夾住上限避免物體瞬移穿過東西
        dt   (min 0.05 (/ (- ts prev) 1000.0))
        el   (canvas)]
    (reset! last-ts ts)
    (ensure-size! el)
    (swap! state tick dt)
    (draw! (.getContext el "2d") @state))
  (js/requestAnimationFrame frame!))

(defn init! []
  (when (nil? @state)
    (reset! state (initial-state)))
  ;; 只啟動一次迴圈；hot reload 後由 frame! 內的重新解析接手最新程式碼
  (when-not @started?
    (reset! started? true)
    (js/requestAnimationFrame frame!)))

(defn after-load! []
  ;; 重新編譯後畫布 transform 仍在，狀態也還在，什麼都不用做。
  (js/console.log "reloaded"))
