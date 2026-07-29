(ns asteroids.core
  "所有 side effect 都在這裡：Canvas 繪圖、鍵盤事件、requestAnimationFrame 迴圈。
   遊戲規則本身在 asteroids.game，那邊完全不碰瀏覽器。"
  (:require [asteroids.game :as game]))

;; hot reload 時 defonce 讓遊戲狀態存活下來
(defonce state (atom nil))
(defonce keys-down (atom #{}))
(defonce started? (atom false))
(defonce last-ts (atom nil))

;; --- 繪圖 -------------------------------------------------------------------

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
  ;; 火焰每秒閃 10 次，跟原版一樣是靠閃爍表現推進而不是持續亮著
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

(defn- wrap-coords
  "物體壓在邊界上時，回傳它在對側的鏡像座標，讓它跨越邊界是滑過去而不是整個彈過去。"
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

;; 分數每幀都要畫，但 (str n) 只在分數真的改變時才做一次——render 迴圈裡不做
;; 字串操作，這是 CLAUDE.md 的規矩。
(defonce score-cache (atom {:n -1 :s ""}))

(defn- score-text [n]
  (let [cached @score-cache]
    (if (= n (:n cached))
      (:s cached)
      (let [s (str n)]
        (reset! score-cache {:n n :s s})
        s))))

(defn- draw-life-icon!
  "命數用小飛船表示，跟原版一樣。"
  [ctx x y]
  (.save ctx)
  (.translate ctx x y)
  (.rotate ctx (- (/ js/Math.PI 2)))     ; 朝上
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

;; --- 整個畫面 ----------------------------------------------------------------

(defn- ship-visible?
  "死亡與遊戲結束時不畫飛船；無敵期間每秒閃 4 次，讓玩家看得出還沒真正開始受傷害。"
  [{:keys [phase invuln t]}]
  (and (#{:playing :next-level} phase)
       (or (zero? invuln) (< (mod (* t 8) 2) 1))))

(defn draw! [ctx {:keys [ship asteroids bullets t] :as state}]
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
  (when (ship-visible? state)
    (draw-wrapped! ctx (:x ship) (:y ship) game/ship-nose
                   (fn [x y] (draw-ship! ctx ship t x y))))
  (draw-hud! ctx state))

;; --- 輸入 -------------------------------------------------------------------

;; 用 .-code 而不是 .-key，換鍵盤配置也不會壞
(def key->action
  {"ArrowLeft"  :left   "KeyA"  :left
   "ArrowRight" :right  "KeyD"  :right
   "ArrowUp"    :thrust "KeyW"  :thrust
   "Space"      :fire})

(defn- init-input! []
  (js/window.addEventListener
   "keydown"
   (fn [e]
     (when-let [action (key->action (.-code e))]
       ;; 擋掉方向鍵與空白鍵捲動頁面
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
                     (/ w game/world-w) 0
                     0 (/ h game/world-h)
                     0 0))))

(defn frame! [ts]
  (let [prev (or @last-ts ts)
        ;; 分頁切回來時 dt 會很大，夾住上限避免物體瞬移穿過東西
        dt   (min 0.05 (/ (- ts prev) 1000.0))
        el   (canvas)]
    (reset! last-ts ts)
    (ensure-size! el)
    (swap! state game/tick dt @keys-down)
    (draw! (.getContext el "2d") @state))
  (js/requestAnimationFrame frame!))

(defn init! []
  (when (nil? @state)
    (reset! state (game/initial-state)))
  ;; 只啟動一次迴圈與監聽；hot reload 後由 frame! 內的重新解析接手最新程式碼
  (when-not @started?
    (reset! started? true)
    (init-input!)
    (js/requestAnimationFrame frame!)))

(defn after-load! []
  ;; 重新編譯後畫布 transform 與遊戲狀態都還在，什麼都不用做。
  (js/console.log "reloaded"))
