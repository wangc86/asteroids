(ns asteroids.game-test
  "asteroids.game 全是純函數，所以這些測試在 node 上跑，不需要瀏覽器。

   跑法：npm test"
  (:require [cljs.test :refer [deftest is testing]]
            [asteroids.game :as game]))

;; --- 輔助 -------------------------------------------------------------------

(def no-input #{})

(defn- close?
  ([a b] (close? a b 1e-9))
  ([a b eps] (< (js/Math.abs (- a b)) eps)))

(defn- step [state inputs]
  (game/tick state (/ 1 60) inputs))

(defn- run
  "以 60 fps 推進 secs 秒。"
  [state secs inputs]
  (reduce (fn [st _] (step st inputs)) state (range (js/Math.round (* 60 secs)))))

(defn- speed-of [{:keys [vx vy]}]
  (js/Math.sqrt (+ (* vx vx) (* vy vy))))

(defn- degrees [rad] (* rad (/ 180 js/Math.PI)))

(defn- sizes [state]
  (sort (map :size (:asteroids state))))

(defn- still-asteroid
  "固定在原地不動的小行星，用來測碰撞。"
  [size x y]
  (let [[a _] (game/make-asteroid 42 size x y)]
    (assoc a :vx 0.0 :vy 0.0)))

(defn- world-with
  "指定小行星、清空子彈、解除開場無敵的測試場地。"
  [asteroids]
  (assoc (game/initial-state 777)
         :asteroids (vec asteroids)
         :bullets   []
         :invuln    0.0))

(defn- open-space
  "只留一顆遠在角落的小行星：飛船前方實質淨空，但場上還有東西所以不會觸發過關。
   單獨測子彈時用這個——真的清空會進 :next-level，那時是不能開火的。"
  [state]
  (assoc state :asteroids [(still-asteroid :small 60 700)]))

;; --- 世界環繞 ---------------------------------------------------------------

(deftest wrap-test
  (is (= 5 (game/wrap 5 100)) "界內不動")
  (is (= 95 (game/wrap -5 100)) "從左緣出去要從右緣回來")
  (is (= 5 (game/wrap 105 100)) "從右緣出去要從左緣回來"))

;; --- 亂數 -------------------------------------------------------------------

(deftest rand-n-is-deterministic
  (let [[a s1] (game/rand-n 12345 8)
        [b s2] (game/rand-n 12345 8)
        [c _]  (game/rand-n 999 8)]
    (is (= a b) "同一個 seed 抽出同一串")
    (is (= s1 s2))
    (is (not= a c) "不同 seed 抽出不同串")
    (is (= 8 (count a)))
    (is (every? #(and (<= 0 %) (< % 1)) a) "值域是 [0,1)")))

;; --- 小行星生成 --------------------------------------------------------------

(deftest asteroid-shape-and-speed-are-in-range
  (doseq [size [:large :medium :small]]
    (testing (name size)
      (let [[a _]        (game/make-asteroid 42 size 100 100)
            r            (game/asteroid-radius size)
            [s-min s-max] (game/asteroid-speed size)
            radii        (map (fn [[x y]] (js/Math.sqrt (+ (* x x) (* y y))))
                              (:points a))]
        (is (= game/asteroid-verts (count (:points a))))
        (is (every? #(<= (* r game/jitter-min) % (* r game/jitter-max)) radii)
            "頂點半徑落在抖動範圍內")
        (is (<= s-min (speed-of a) s-max) "漂移速度落在該級距內")))))

(deftest initial-state-is-reproducible
  (is (= (game/initial-state 12345) (game/initial-state 12345))
      "同一個 seed 必須生出完全一樣的開局")
  (is (not= (game/initial-state 12345) (game/initial-state 999))))

(deftest first-wave-spawns-four-large-asteroids-on-the-edge
  (let [{:keys [asteroids]} (game/initial-state 12345)]
    (is (= game/level-1-asteroids (count asteroids)))
    (is (every? #(= :large (:size %)) asteroids))
    (is (every? (fn [{:keys [x y]}]
                  (or (zero? x) (zero? y)
                      (= x game/world-w) (= y game/world-h)))
                asteroids)
        "從邊緣進場，不會直接壓在飛船頭上")))

;; --- 飛船手感（里程碑 2 定案的數值）------------------------------------------

(deftest ship-turns-at-the-tuned-rate
  (let [s0 (game/initial-state 1)
        s1 (run s0 1 #{:right})
        turned (degrees (- (get-in s1 [:ship :angle])
                           (get-in s0 [:ship :angle])))]
    (is (close? turned game/rotate-speed 1e-6) "按住右轉一秒就是 rotate-speed 度")))

(deftest left-and-right-cancel-out
  (let [s0 (game/initial-state 1)
        s1 (run s0 1 #{:left :right})]
    (is (close? (get-in s1 [:ship :angle]) (get-in s0 [:ship :angle])))))

(deftest thrust-accelerates-along-the-nose
  (let [s (run (game/initial-state 1) 1 #{:thrust})
        {:keys [vx vy]} (:ship s)
        ;; 解析解：v(t) = (a/k)(1 - e^{-kt})，離散積分會差一點點
        expected (* (/ game/thrust game/drag)
                    (- 1 (js/Math.exp (- game/drag))))]
    (is (close? vx 0 1e-9) "初始朝正上方，x 方向不該有速度")
    (is (close? (- vy) expected 2.0) "y 方向速度貼近解析解")
    (is (:thrusting? (:ship s)))))

(deftest inertia-decays-but-does-not-stop
  (let [thrusted (run (game/initial-state 1) 1 #{:thrust})
        v0       (speed-of (:ship thrusted))
        coasted  (run thrusted 3 no-input)
        v1       (speed-of (:ship coasted))]
    (is (close? v1 (* v0 (js/Math.exp (* -3 game/drag))) 0.5)
        "放開推進後照指數衰減")
    (is (pos? v1) "但不會歸零——這就是慣性")
    (is (not (:thrusting? (:ship coasted))))))

(deftest speed-is-clamped
  (let [s (run (game/initial-state 1) 30 #{:thrust})]
    (is (close? (speed-of (:ship s)) game/max-speed 1e-6))))

;; --- 漂移與環繞 --------------------------------------------------------------

(deftest asteroids-drift-and-stay-inside-the-world
  (let [s0 (game/initial-state 12345)
        s1 (reduce (fn [st _]
                     (let [st (step st no-input)]
                       (doseq [{:keys [x y]} (:asteroids st)]
                         (is (<= 0 x game/world-w))
                         (is (<= 0 y game/world-h)))
                       st))
                   s0
                   (range 600))]
    (is (= (map speed-of (:asteroids s0)) (map speed-of (:asteroids s1)))
        "漂移不改變速率")
    (is (= (map :points (:asteroids s0)) (map :points (:asteroids s1)))
        "也不改變外形")))

;; --- 子彈生命週期 ------------------------------------------------------------

(deftest firing-is-edge-triggered
  (let [s      (open-space (game/initial-state 1))
        held   (run s 1 #{:fire})
        tapped (-> s (step #{:fire}) (step no-input) (step #{:fire}))]
    (is (= 1 (count (:bullets held))) "按住不放只會發射一次")
    (is (= 2 (count (:bullets tapped))) "放開再按才有第二發")))

(deftest at-most-four-bullets-on-screen
  (let [s (reduce (fn [st _] (-> st (step #{:fire}) (step no-input)))
                  (open-space (game/initial-state 1))
                  (range 10))]
    (is (= game/max-bullets (count (:bullets s))))))

(deftest bullet-leaves-the-nose-at-fixed-speed
  (let [s (step (open-space (game/initial-state 1)) #{:fire})
        b (first (:bullets s))]
    (is (close? (:x b) (/ game/world-w 2) 1e-6))
    (is (close? (:y b) (- (/ game/world-h 2) game/ship-nose) 1e-6)
        "從機鼻出膛")
    (is (close? (:vx b) 0 1e-6))
    (is (close? (:vy b) (- game/bullet-speed) 1e-6)
        "射速固定，不加上飛船速度（原版怪癖）")))

(deftest bullets-expire
  (let [fired (step (open-space (game/initial-state 1)) #{:fire})]
    (is (= 1 (count (:bullets (run fired 1.13 no-input)))) "壽命內還在")
    (is (= 0 (count (:bullets (run fired 1.2 no-input)))) "壽命到了就消失")))

;; --- 碰撞與分裂 --------------------------------------------------------------

(deftest large-splits-into-two-medium
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= [:medium :medium] (sizes s)))
    (is (empty? (:bullets s)) "子彈與小行星同歸於盡")))

(deftest medium-splits-into-two-small
  (let [s (-> (world-with [(still-asteroid :medium 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= [:small :small] (sizes s)))))

(deftest small-is-destroyed-outright
  (let [s (-> (world-with [(still-asteroid :small 512 184)])
              (step #{:fire})
              (run 0.4 no-input))]
    (is (empty? (:asteroids s)))))

(deftest children-fly-apart
  (let [s     (-> (world-with [(still-asteroid :large 512 184)])
                  (step #{:fire})
                  (run 0.4 no-input))
        [c1 c2] (:asteroids s)
        [s-min s-max] (game/asteroid-speed :medium)]
    (is (not= [(:vx c1) (:vy c1)] [(:vx c2) (:vy c2)]) "兩顆方向不同")
    (is (<= s-min (speed-of c1) s-max))
    (is (<= s-min (speed-of c2) s-max))))

(deftest collision-works-across-the-wrap-seam
  (let [s (-> (world-with [(still-asteroid :large 1020 384)])
              (assoc :bullets [{:x 6.0 :y 384.0 :vx 0.0 :vy 0.0 :life 1.0}])
              (step no-input))]
    (is (= [:medium :medium] (sizes s))
        "貼右緣的隕石與貼左緣的子彈在環繞世界裡很近")
    (is (empty? (:bullets s)))))

(deftest a-miss-destroys-nothing
  (let [s (-> (world-with [(still-asteroid :large 100 100)])
              (step #{:fire})
              (run 0.3 no-input))]
    (is (= [:large] (sizes s)))
    (is (= 1 (count (:bullets s))))))

;; --- 分數與命數 --------------------------------------------------------------

(deftest destroying-asteroids-scores-by-size
  (doseq [[size expected] [[:large (:large game/score-for)]
                           [:medium (:medium game/score-for)]
                           [:small (:small game/score-for)]]]
    (testing (name size)
      (let [s (-> (world-with [(still-asteroid size 512 184)])
                  (step #{:fire})
                  (run 0.4 no-input))]
        (is (= expected (:score s)))))))

(deftest score-starts-at-zero-with-three-lives
  (let [s (game/initial-state 1)]
    (is (= 0 (:score s)))
    (is (= game/start-lives (:lives s)))
    (is (= 1 (:level s)))
    (is (= :playing (:phase s)))))

(deftest extra-life-at-the-threshold
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              ;; 差 20 分就到門檻，打掉一顆大隕石剛好跨過
              (assoc :score (- game/extra-life-every 20))
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= game/extra-life-every (:score s)))
    (is (= (inc game/start-lives) (:lives s)) "跨過門檻加一命")
    (is (= (* 2 game/extra-life-every) (:next-extra-life s)) "門檻往上推"))
  (let [s (-> (world-with [(still-asteroid :large 512 184)])
              (assoc :score (- game/extra-life-every 100))
              (step #{:fire})
              (run 0.4 no-input))]
    (is (= game/start-lives (:lives s)) "沒跨過門檻就不加命")))

;; --- 撞擊與重生 --------------------------------------------------------------

(deftest ship-dies-when-it-hits-an-asteroid
  (let [s (-> (world-with [(still-asteroid :large 512 384)])   ; 正壓在飛船上
              (step no-input))]
    (is (= (dec game/start-lives) (:lives s)))
    (is (= :dead (:phase s)))
    (is (close? (:timer s) game/respawn-delay 0.02))))

(deftest invulnerable-ship-survives
  (let [s (-> (world-with [(still-asteroid :large 512 384)])
              (assoc :invuln game/invuln-time)
              (run 1 no-input))]
    (is (= game/start-lives (:lives s)) "無敵期間撞不死")
    (is (= :playing (:phase s)))))

(deftest new-game-starts-invulnerable
  (let [s (game/initial-state 1)]
    (is (pos? (:invuln s)))))

(deftest respawn-waits-for-a-clear-centre
  ;; 隕石停在正中央：倒數結束了也不能放人出來
  (let [blocked (-> (world-with [(still-asteroid :large 512 384)])
                    (step no-input)                 ; 撞死
                    (run 5 no-input))]              ; 遠超過 respawn-delay
    (is (= :dead (:phase blocked)) "中心沒淨空就一直等")
    (is (zero? (:timer blocked))))
  ;; 隕石在遠處：倒數結束就重生
  (let [freed (-> (world-with [(still-asteroid :large 512 384)])
                  (step no-input)
                  (assoc :asteroids [(still-asteroid :large 60 60)])
                  (run 3 no-input))]
    (is (= :playing (:phase freed)))
    (is (pos? (:invuln freed)) "重生後有無敵時間")
    (is (= (game/initial-ship) (dissoc (:ship freed) :thrusting?))
        "回到畫面中央、速度歸零")))

(deftest last-life-ends-the-game
  (let [s (-> (world-with [(still-asteroid :large 512 384)])
              (assoc :lives 1)
              (step no-input))]
    (is (= 0 (:lives s)))
    (is (= :game-over (:phase s)))))

(deftest game-over-ignores-controls-until-space
  (let [over    (-> (world-with [(still-asteroid :large 512 384)])
                    (assoc :lives 1)
                    (step no-input)
                    (run 3 #{:thrust :left}))
        pressed (step over #{:fire})]
    (is (= :game-over (:phase over)) "推進與轉向不會重開")
    (is (= :playing (:phase pressed)))
    (is (= 0 (:score pressed)))
    (is (= game/start-lives (:lives pressed)))
    (is (= 1 (:level pressed)) "重開回到第一關")))

;; --- 關卡遞增 ----------------------------------------------------------------

(deftest asteroid-count-grows-then-caps
  (is (= 4 (game/asteroids-for-level 1)))
  (is (= 6 (game/asteroids-for-level 2)))
  (is (= 8 (game/asteroids-for-level 3)))
  (is (= game/max-level-asteroids (game/asteroids-for-level 5)))
  (is (= game/max-level-asteroids (game/asteroids-for-level 20)) "上限之後不再增加"))

(deftest clearing-the-field-advances-the-level
  (let [cleared (-> (world-with []) (step no-input))]
    (is (= :next-level (:phase cleared)))
    (is (= 1 (:level cleared)) "停頓期間還沒換關"))
  (let [next-wave (-> (world-with []) (run (+ game/level-pause 0.2) no-input))]
    (is (= :playing (:phase next-wave)))
    (is (= 2 (:level next-wave)))
    (is (= (game/asteroids-for-level 2) (count (:asteroids next-wave))))
    (is (every? #(= :large (:size %)) (:asteroids next-wave)))))

(deftest ship-is-frozen-while-dead
  (let [dead   (-> (world-with [(still-asteroid :large 512 384)]) (step no-input))
        before (:ship dead)
        after  (:ship (run dead 1 #{:thrust :right}))]
    (is (= :dead (:phase dead)))
    (is (= before after) "死亡期間輸入不影響飛船")))

(deftest cannot-fire-while-dead
  (let [dead (-> (world-with [(still-asteroid :large 512 384)])
                 (step no-input)
                 (assoc :bullets []))
        shot (-> dead (step #{:fire}) (step no-input) (step #{:fire}))]
    (is (empty? (:bullets shot)))))

;; --- 整體 -------------------------------------------------------------------

(deftest the-whole-game-is-deterministic
  (let [play #(run (game/initial-state 4242) 3 #{:thrust :fire})]
    (is (= (play) (play))
        "同一個 seed 加同一串輸入，必定跑出一模一樣的結果")))
