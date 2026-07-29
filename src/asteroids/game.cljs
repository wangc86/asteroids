(ns asteroids.game
  "遊戲的純邏輯：世界常數、亂數、生成、每幀推進、碰撞分裂。

   這個 namespace 不碰任何瀏覽器 API——沒有 document、沒有 canvas、沒有 atom。
   所有函數都是輸入決定輸出，所以能在 node 上直接跑測試。
   會產生 side effect 的東西（繪圖、鍵盤、rAF 迴圈）都在 asteroids.core。")

;; 遊戲一律用這組邏輯座標，畫布實際像素由 core/ensure-size! 換算，
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

;; 機身在自己的座標系裡朝 +x，機鼻在前、機尾是個朝前凹的 V 字（原版造型）。
;; ship-nose 同時也是子彈的出膛位置，繪圖那側也用同一組數字。
(def ^:const ship-nose 14)
(def ^:const ship-tail -10)
(def ^:const ship-half-width 9)
(def ^:const ship-notch -5)

;; 小行星三級，半徑與漂移速度區間（px/秒）
(def asteroid-radius {:large 42 :medium 22 :small 11})
(def asteroid-speed  {:large [18 46] :medium [30 78] :small [50 118]})
(def next-size {:large :medium, :medium :small, :small nil})
(def ^:const asteroid-verts 12)
(def ^:const jitter-min 0.72)    ; 頂點半徑相對基準半徑的抖動範圍，決定稜角有多亂
(def ^:const jitter-max 1.12)
(def ^:const level-1-asteroids 4)

;; 子彈。原版同時最多 4 發，且射速固定不加上飛船速度——所以船開到極速時
;; 幾乎追得上自己的子彈，這個怪癖是原版手感的一部分。
(def ^:const bullet-speed 620)   ; px/秒
(def ^:const bullet-life 1.15)   ; 秒，約可飛過畫面七成寬
(def ^:const max-bullets 4)

;; 遊戲規則。分數與加命門檻照原版。
(def score-for {:large 20, :medium 50, :small 100})
(def ^:const start-lives 3)
(def ^:const extra-life-every 10000)
(def ^:const ship-radius 11)              ; 撞擊判定用的圓，比機身略小一點才不會覺得冤枉
(def ^:const respawn-delay 2.0)           ; 秒，爆炸到重生之間的空白
(def ^:const invuln-time 3.0)             ; 秒，出場後的無敵時間
(def ^:const respawn-clear-radius 90)     ; 重生點周圍要淨空到這個半徑才會放人出來
(def ^:const level-pause 1.5)             ; 秒，清空到下一波之間的停頓
(def ^:const level-asteroid-step 2)       ; 每關多兩顆
(def ^:const max-level-asteroids 11)      ; 原版的上限

(defn asteroids-for-level [level]
  (min max-level-asteroids
       (+ level-1-asteroids (* level-asteroid-step (dec level)))))

;; --- 亂數 -------------------------------------------------------------------
;; 亂數狀態（seed）放在遊戲 state 裡跟著走，這樣連「分裂出新小行星」這種需要
;; 亂數的行為都能留在純函數裡，同一個 seed 必定跑出同一場遊戲，方便測試。

(defn- xorshift32 [s]
  (let [s (bit-xor s (bit-shift-left s 13))
        s (bit-xor s (unsigned-bit-shift-right s 17))
        s (bit-xor s (bit-shift-left s 5))]
    s))

(defn rand-n
  "從 seed 抽 n 個 [0,1) 亂數，回傳 [亂數向量 新seed]。"
  [seed n]
  (loop [s seed, i 0, acc (transient [])]
    (if (< i n)
      (let [s' (xorshift32 s)]
        (recur s' (inc i)
               (conj! acc (/ (unsigned-bit-shift-right s' 0) 4294967296))))
      [(persistent! acc) s])))

(defn- lerp [a b t] (+ a (* t (- b a))))

;; --- 生成 -------------------------------------------------------------------

(defn make-asteroid
  "生一顆小行星：隨機方向、該級距內的隨機速度，外形是頂點半徑各自抖動過的多邊形。
   小行星不自轉（原版就是這樣），所以頂點座標在這裡算好，render 迴圈不必再做三角函數。"
  [seed size x y]
  (let [[rs seed]     (rand-n seed (+ 2 asteroid-verts))
        dir           (* tau (nth rs 0))
        [s-min s-max] (asteroid-speed size)
        speed         (lerp s-min s-max (nth rs 1))
        radius        (asteroid-radius size)
        step          (/ tau asteroid-verts)
        points        (mapv (fn [i]
                              (let [r (* radius (lerp jitter-min jitter-max
                                                      (nth rs (+ 2 i))))
                                    a (* i step)]
                                [(* r (js/Math.cos a)) (* r (js/Math.sin a))]))
                            (range asteroid-verts))]
    [{:x x :y y
      :vx (* speed (js/Math.cos dir))
      :vy (* speed (js/Math.sin dir))
      :size size
      :points points}
     seed]))

(defn- edge-point
  "沿著畫面四邊挑一點——新的一波從邊緣進場，不會直接壓在飛船頭上。"
  [r1 r2]
  (if (< r1 0.5)
    [(* r2 world-w) (if (< r1 0.25) 0.0 (double world-h))]
    [(if (< r1 0.75) 0.0 (double world-w)) (* r2 world-h)]))

(defn spawn-wave
  "在邊緣放 n 顆大隕石，回傳 [小行星向量 新seed]。"
  [seed n]
  (loop [i 0, seed seed, acc []]
    (if (< i n)
      (let [[rs seed]  (rand-n seed 2)
            [x y]      (edge-point (nth rs 0) (nth rs 1))
            [a seed]   (make-asteroid seed :large x y)]
        (recur (inc i) seed (conj acc a)))
      [acc seed])))

(defn initial-ship []
  {:x     (/ world-w 2)
   :y     (/ world-h 2)
   :vx    0.0
   :vy    0.0
   ;; 螢幕座標 y 向下，所以 -π/2 是朝正上方
   :angle (- (/ js/Math.PI 2))})

(defn initial-state
  "不給 seed 就用時間當種子；給 seed 則整場遊戲完全重現，測試用。"
  ([] (initial-state (bit-or (js/Date.now) 1)))   ; bit-or 1：截成 32 位元且保證非零
  ([seed]
   (let [[asteroids seed] (spawn-wave seed (asteroids-for-level 1))]
     {:ship            (initial-ship)
      :asteroids       asteroids
      :bullets         []
      ;; 射擊是按一次打一發，不是按著不放連射，所以要記上一幀有沒有按著
      :fire-held?      false
      :score           0
      :lives           start-lives
      :level           1
      :next-extra-life extra-life-every
      ;; :playing 正常遊玩／:dead 等待重生／:next-level 過關停頓／:game-over 等待重開
      :phase           :playing
      :timer           0.0        ; 目前階段的倒數，各階段共用這一個欄位
      :invuln          invuln-time
      :seed            seed
      :t               0.0})))

;; --- 推進一幀 ---------------------------------------------------------------

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

(defn drift
  "小行星只有等速漂移加環繞，沒有加速度也不自轉。"
  [{:keys [x y vx vy] :as a} dt]
  (assoc a
         :x (wrap (+ x (* vx dt)) world-w)
         :y (wrap (+ y (* vy dt)) world-h)))

(defn playing?
  "只有 :playing 階段飛船才受控、才會被撞、才能開火。"
  [state]
  (= :playing (:phase state)))

;; --- 子彈 -------------------------------------------------------------------

(defn- advance-bullets
  "子彈跟著漂移環繞，壽命到了就消失——這是唯一會讓子彈消失的自然原因。"
  [bullets dt]
  (into []
        (comp (map (fn [b]
                     (assoc b
                            :life (- (:life b) dt)
                            :x    (wrap (+ (:x b) (* (:vx b) dt)) world-w)
                            :y    (wrap (+ (:y b) (* (:vy b) dt)) world-h))))
              (filter #(pos? (:life %))))
        bullets))

(defn- fire-bullet [{:keys [ship] :as state}]
  (let [angle (:angle ship)
        cos   (js/Math.cos angle)
        sin   (js/Math.sin angle)]
    (update state :bullets conj
            {:x    (wrap (+ (:x ship) (* ship-nose cos)) world-w)
             :y    (wrap (+ (:y ship) (* ship-nose sin)) world-h)
             :vx   (* bullet-speed cos)
             :vy   (* bullet-speed sin)
             :life bullet-life})))

(defn- fire-edge?
  "這幀按下、上一幀沒按——按住不放不會連射。
   :fire-held? 在 tick 的最後才更新，所以同一幀裡開火與重開遊戲看到的是同一個邊緣。"
  [state inputs]
  (and (boolean (inputs :fire)) (not (:fire-held? state))))

(defn- maybe-fire [state inputs]
  (if (and (playing? state)
           (fire-edge? state inputs)
           (< (count (:bullets state)) max-bullets))
    (fire-bullet state)
    state))

;; --- 碰撞與分裂 --------------------------------------------------------------

(defn- wrap-delta
  "環繞世界裡兩點的最短距離分量：畫面兩側是相連的，貼著左緣和貼著右緣其實很近。"
  [d limit]
  (let [half (/ limit 2)]
    (cond
      (> d half)     (- d limit)
      (< d (- half)) (+ d limit)
      :else          d)))

(defn- hit?
  "子彈中心落在小行星基準半徑內就算打中。外形是不規則多邊形，但用圓形近似
   在這個尺寸下玩起來沒有違和，也省下多邊形內外判定。"
  [b a]
  (let [r  (asteroid-radius (:size a))
        dx (wrap-delta (- (:x a) (:x b)) world-w)
        dy (wrap-delta (- (:y a) (:y b)) world-h)]
    (< (+ (* dx dx) (* dy dy)) (* r r))))

(defn- first-hit-index [b asteroids already-hit]
  (loop [i 0]
    (when (< i (count asteroids))
      (if (and (not (already-hit i)) (hit? b (nth asteroids i)))
        i
        (recur (inc i))))))

(defn- split
  "被打中的小行星裂成兩顆小一級的，方向各自重抽；最小級直接消失。"
  [seed a]
  (if-let [child (next-size (:size a))]
    (let [[c1 seed] (make-asteroid seed child (:x a) (:y a))
          [c2 seed] (make-asteroid seed child (:x a) (:y a))]
      [[c1 c2] seed])
    [[] seed]))

(defn- award-extra-life
  "每累積 extra-life-every 分加一命。單幀最多 4 發子彈、至多幾百分，
   不可能一次跨過兩個門檻，所以判斷一次就夠。"
  [state]
  (if (>= (:score state) (:next-extra-life state))
    (-> state
        (update :lives inc)
        (update :next-extra-life + extra-life-every))
    state))

(defn- resolve-hits [{:keys [asteroids bullets] :as state}]
  (let [[survivors-b hit]
        (reduce (fn [[bs hit] b]
                  (if-let [i (first-hit-index b asteroids hit)]
                    [bs (conj hit i)]          ; 子彈與小行星同歸於盡
                    [(conj bs b) hit]))
                [[] #{}]
                bullets)]
    (if (empty? hit)
      state
      (let [[children seed]
            (reduce (fn [[acc seed] i]
                      (let [[cs seed] (split seed (nth asteroids i))]
                        [(into acc cs) seed]))
                    [[] (:seed state)]
                    hit)
            gained (reduce + 0 (map #(score-for (:size (nth asteroids %))) hit))]
        (-> state
            (assoc :bullets   survivors-b
                   :asteroids (into (into [] (keep-indexed #(when-not (hit %1) %2)) asteroids)
                                    children)
                   :seed      seed)
            (update :score + gained)
            (award-extra-life))))))

;; --- 命數、關卡、階段 --------------------------------------------------------

(defn- within?
  "兩點在環繞世界裡的距離小於 r？"
  [x1 y1 x2 y2 r]
  (let [dx (wrap-delta (- x2 x1) world-w)
        dy (wrap-delta (- y2 y1) world-h)]
    (< (+ (* dx dx) (* dy dy)) (* r r))))

(defn- ship-hit? [ship asteroids]
  (boolean
   (some (fn [a]
           (within? (:x ship) (:y ship) (:x a) (:y a)
                    (+ ship-radius (asteroid-radius (:size a)))))
         asteroids)))

(defn- ship-collision
  "撞上小行星就少一命。小行星不受影響——重生點淨空檢查會處理『原地又被撞死』。"
  [state]
  (if (and (playing? state)
           (zero? (:invuln state))
           (ship-hit? (:ship state) (:asteroids state)))
    (let [lives (dec (:lives state))]
      (assoc state
             :lives lives
             :phase (if (pos? lives) :dead :game-over)
             :timer respawn-delay))
    state))

(defn- check-level-clear [state]
  (if (and (playing? state) (empty? (:asteroids state)))
    (assoc state :phase :next-level :timer level-pause)
    state))

(defn- respawn-clear?
  "重生點周圍夠空曠了嗎？沒淨空就繼續等，避免一出場就撞死。"
  [asteroids]
  (let [cx (/ world-w 2)
        cy (/ world-h 2)]
    (not-any? (fn [a]
                (within? cx cy (:x a) (:y a)
                         (+ respawn-clear-radius (asteroid-radius (:size a)))))
              asteroids)))

(defn- advance-phase [state inputs]
  (case (:phase state)
    :dead
    (if (and (zero? (:timer state)) (respawn-clear? (:asteroids state)))
      (assoc state :ship (initial-ship) :invuln invuln-time :phase :playing)
      state)

    :next-level
    (if (zero? (:timer state))
      (let [level     (inc (:level state))
            [as seed] (spawn-wave (:seed state) (asteroids-for-level level))]
        (assoc state
               :level     level
               :asteroids as
               :seed      seed
               :invuln    invuln-time
               :phase     :playing))
      state)

    :game-over
    (if (fire-edge? state inputs)
      (initial-state (:seed state))
      state)

    state))

(defn tick
  "推進一幀。inputs 是動作關鍵字的 set，例如 #{:left :thrust :fire}。"
  [state dt inputs]
  (-> state
      (update :t + dt)
      (update :invuln #(max 0.0 (- % dt)))
      (update :timer #(max 0.0 (- % dt)))
      (cond-> (playing? state) (update :ship update-ship dt inputs))
      (update :asteroids (fn [as] (mapv #(drift % dt) as)))
      (update :bullets advance-bullets dt)
      (maybe-fire inputs)
      (resolve-hits)
      (ship-collision)
      (check-level-clear)
      (advance-phase inputs)
      ;; 最後才記下按鍵狀態，前面所有邊緣偵測看到的才會是同一幀的判斷
      (assoc :fire-held? (boolean (inputs :fire)))))
