(ns asteroids.core)

;; 遊戲一律用這組邏輯座標，畫布實際像素由 ensure-size! 換算，
;; 所以視窗大小改變時遊戲數值完全不受影響。
(def ^:const world-w 1024)
(def ^:const world-h 768)

(def ^:const tau (* 2 js/Math.PI))
(def ^:const deg->rad (/ js/Math.PI 180))

;; 手感參數，里程碑 2 由使用者試玩定案，不要隨意調整
(def ^:const rotate-speed 200)   ; 度/秒
(def ^:const thrust 340)         ; px/秒²
(def ^:const drag 0.25)          ; 每秒速度指數衰減係數（0 = 完全無摩擦）
(def ^:const max-speed 540)      ; px/秒

;; hot reload 時 defonce 讓遊戲狀態存活下來
(defonce state (atom nil))
(defonce keys-down (atom #{}))
(defonce started? (atom false))
(defonce last-ts (atom nil))

(defn initial-ship []
  {:x     (/ world-w 2)
   :y     (/ world-h 2)
   :vx    0.0
   :vy    0.0
   ;; 螢幕座標 y 向下，所以 -π/2 是朝正上方
   :angle (- (/ js/Math.PI 2))})

(defn initial-state []
  {:ship (initial-ship)
   :t    0.0})

;; --- 純函數：推進一幀 ------------------------------------------------------

(defn wrap
  "螢幕環繞：超出邊界就從另一側出現。"
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
  "旋轉 → 推進 → 阻力 → 限速 → 位移環繞。"
  [ship dt inputs]
  (let [angle      (+ (:angle ship)
                      (* (turn-dir inputs) rotate-speed deg->rad dt))
        thrusting? (boolean (inputs :thrust))
        ;; 推進沿著機鼻方向加速；放開後速度不歸零，只被阻力慢慢吃掉 = 慣性
        ax         (if thrusting? (* thrust (js/Math.cos angle)) 0.0)
        ay         (if thrusting? (* thrust (js/Math.sin angle)) 0.0)
        ;; 指數衰減與幀率無關，dt 抖動不會讓手感跟著抖
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

(defn tick [state dt inputs]
  (-> state
      (update :t + dt)
      (update :ship update-ship dt inputs)))

;; --- Side effect：繪圖 -----------------------------------------------------

;; 機身在自己的座標系裡朝 +x，機鼻在前、機尾是個朝前凹的 V 字（原版造型）
(def ^:const ship-nose 14)
(def ^:const ship-tail -10)
(def ^:const ship-half-width 9)
(def ^:const ship-notch -5)

(defn- draw-ship-body! [ctx]
  (.beginPath ctx)
  (.moveTo ctx ship-nose 0)
  (.lineTo ctx ship-tail ship-half-width)
  (.lineTo ctx ship-notch 0)
  (.lineTo ctx ship-tail (- ship-half-width))
  (.closePath ctx)
  (.stroke ctx))

(defn- draw-flame! [ctx]
  (.beginPath ctx)
  (.moveTo ctx (- ship-notch 1) 4)
  (.lineTo ctx (- ship-tail 7) 0)
  (.lineTo ctx (- ship-notch 1) -4)
  (.stroke ctx))

(defn draw! [ctx {:keys [ship t]}]
  (.clearRect ctx 0 0 world-w world-h)
  (set! (.-strokeStyle ctx) "#fff")
  (set! (.-lineWidth ctx) 2)
  (.save ctx)
  (.translate ctx (:x ship) (:y ship))
  (.rotate ctx (:angle ship))
  (draw-ship-body! ctx)
  ;; 火焰每秒閃 10 次，跟原版一樣是靠閃爍表現推進而不是持續亮著
  (when (and (:thrusting? ship) (< (mod (* t 20) 2) 1))
    (draw-flame! ctx))
  (.restore ctx))

;; --- 輸入 -------------------------------------------------------------------

;; 用 .-code 而不是 .-key，換鍵盤配置也不會壞
(def key->action
  {"ArrowLeft"  :left   "KeyA" :left
   "ArrowRight" :right  "KeyD" :right
   "ArrowUp"    :thrust "KeyW" :thrust})

(defn- init-input! []
  (js/window.addEventListener
   "keydown"
   (fn [e]
     (when-let [action (key->action (.-code e))]
       ;; 擋掉方向鍵捲動頁面
       (.preventDefault e)
       (swap! keys-down conj action))))
  (js/window.addEventListener
   "keyup"
   (fn [e]
     (when-let [action (key->action (.-code e))]
       (swap! keys-down disj action))))
  ;; 視窗失焦時鍵不會送 keyup，不清掉的話會變成卡住一直轉
  (js/window.addEventListener "blur" (fn [_] (reset! keys-down #{}))))

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
    (swap! state tick dt @keys-down)
    (draw! (.getContext el "2d") @state))
  (js/requestAnimationFrame frame!))

(defn init! []
  (when (nil? @state)
    (reset! state (initial-state)))
  ;; 只啟動一次迴圈與監聽；hot reload 後由 frame! 內的重新解析接手最新程式碼
  (when-not @started?
    (reset! started? true)
    (init-input!)
    (js/requestAnimationFrame frame!)))

(defn after-load! []
  ;; 重新編譯後畫布 transform 與遊戲狀態都還在，什麼都不用做。
  (js/console.log "reloaded"))
