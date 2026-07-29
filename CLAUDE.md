# Asteroids — ClojureScript 瀏覽器版

經典 Atari Asteroids (1979) 的復刻，用 ClojureScript 寫，在瀏覽器中以 HTML5 Canvas 執行。

- Repo: https://github.com/wangc86/asteroids
- 授權: GPL-3.0
- 本機路徑: `C:\Users\chaot\code\asteroids`

## 技術決策（2026-07-29 議定）

| 項目 | 決定 | 理由 |
|---|---|---|
| 語言 | ClojureScript | 使用者指定 |
| 建置工具 | shadow-cljs | hot reload 保留遊戲狀態、npm 整合、錯誤訊息友善 |
| 渲染 | Canvas 2D (`moveTo`/`lineTo`/`stroke`) | 原版是向量顯示器線條圖形，天生對味 |
| **不用** Reagent / re-frame | — | Canvas 遊戲不需要 React DOM diff；HUD 直接畫在 canvas 上 |
| 遊戲迴圈 | `requestAnimationFrame` + delta time | |
| 輸入 | `keydown`/`keyup` 存入 set | 避免 OS 按鍵重複延遲 |
| 音效 | Web Audio API 程式合成 | 原版心跳聲/推進器噪音不需音檔 |
| 遊戲範圍 | **忠實復刻原版** | 含三級小行星分裂、大小 UFO、hyperspace、心跳音效、關卡遞增 |

## 核心架構

遊戲狀態是一個 immutable map，每幀由純函數推進，唯一的 side effect 在繪圖：

```clojure
(defn tick  [state dt inputs] ...)  ; 純函數 → 新 state，可單元測試
(defn draw! [ctx state]      ...)   ; 唯一的 side effect
```

碰撞偵測初期用 O(n²) 全對全即可（n≈40 時僅 ~800 次比較/幀）——**不要提早最佳化**。
避免在 render 迴圈中做 `str` / `pr-str` 等字串操作。

**亂數狀態放在 state 裡**（`:seed`，xorshift32）。需要亂數的行為——生成小行星、
分裂、UFO 出場時機——都寫成 `(f seed ...) → [結果 新seed]`，`tick` 因此不必碰
`js/Math.random` 就能保持純函數。`(initial-state seed)` 給定 seed 會完全重現同一場
遊戲，測試靠這個。**不要在 `tick` 底下直接呼叫 `js/Math.random`。**

## 開發環境

- Temurin JDK 21.0.11（ClojureScript 編譯器需要 JVM）
- Node.js v24.18.0 / npm 11.16.0

## 建置指令

```bash
npm install                    # 安裝依賴
npx shadow-cljs watch app      # 開發模式 + hot reload → http://localhost:8080
npm test                       # 在 node 上跑單元測試（shadow-cljs compile test）
npx shadow-cljs release app    # 產出正式版到 public/js
```

- 開發模式的 shadow-cljs 主控台在 http://localhost:9630
- 產出物 `public/js/`、`out/` 不進版控；部署時（里程碑 7）才 release 後另行處理
- **改到 `shadow-cljs.edn` 的 `:source-paths` 後要重啟 server**（`npx shadow-cljs stop`），
  常駐的 server 不會重讀這個設定，症狀是測試明明寫了卻顯示 `Ran 0 tests`

## 專案結構

```
public/index.html             頁面外殼與 canvas CSS（4:3 滿版）
src/asteroids/game.cljs       純邏輯：常數、亂數、生成、tick、碰撞分裂
src/asteroids/core.cljs       side effect：draw!、鍵盤、canvas、rAF 迴圈
test/asteroids/game_test.cljs game 的單元測試
shadow-cljs.edn               建置設定（:app → public、:test → node）
```

**分層規則：`game` 不准碰任何瀏覽器 API**——沒有 `document`、沒有 canvas、沒有 atom，
只有純函數。所以它能在 node 上直接測試。依賴方向是單向的 `core → game`，
Clojure 不允許循環 require，新增功能時請維持這個方向。

- `game/tick`：`(tick state dt inputs)`，inputs 是動作關鍵字的 set
- `core/draw!`：唯一的繪圖 side effect
- `core/frame!`：rAF 迴圈，把 `@keys-down` 餵給 `tick`，再把結果交給 `draw!`
- 遊戲座標固定為 1024×768 邏輯單位，`core/ensure-size!` 每幀把 canvas 緩衝區
  對齊實際顯示尺寸並縮放 context，視窗大小與 devicePixelRatio 都不影響遊戲數值
- `core` 的 `state` / `started?` 用 `defonce`，hot reload 時遊戲狀態與迴圈都不會重來

之後音效（里程碑 6）獨立成 `asteroids.sound`，繪圖若超過 150 行再拆 `asteroids.render`。

## 測試

`npm test` 跑 `test/asteroids/game_test.cljs`，node 上執行，不需要瀏覽器。

寫測試靠 `(initial-state seed)` 的可重現性：固定 seed + 固定輸入序列 → 固定結果。
測試裡的 `step` / `run` 以 60 fps 推進固定秒數，所以「按住右轉一秒剛好 200 度」
這種手感規格可以直接寫成斷言。**改動 `game` 的行為時請一併更新或新增測試。**

## 手感參數（里程碑 2 使用者試玩定案，2026-07-29）

```clojure
rotate-speed 200   ; 度/秒
thrust       340   ; px/秒²
drag         0.25  ; 每秒速度指數衰減係數
max-speed    540   ; px/秒
```

**未經使用者試玩不要調整這四個值。** 阻力用 `v *= exp(-drag·dt)` 而非每幀乘固定
係數，手感才與幀率脫鉤。

操作：`←` `→` 轉向、`↑` 推進（或 `A` / `D` / `W`）、`空白鍵` 開火。

## 小行星（里程碑 3）

三級半徑 42 / 22 / 11，漂移速度隨級數遞增。外形是 12 個頂點、半徑各自在基準的
0.72–1.12 之間抖動的多邊形，**生成時就把頂點座標算好存進 `:points`**，render 迴圈
不再做三角函數。

**小行星不自轉**——原版就是只有平移。若哪天想改成會轉，那是偏離原版的決定。

## 射擊與碰撞（里程碑 4）

- 同時最多 4 發，**按一次打一發**（`:fire-held?` 記住上一幀狀態），按住不放不會連射
- 子彈速度 620 px/秒、壽命 1.15 秒，**不加上飛船速度**：船開到極速 540 時幾乎追得上
  自己的子彈。這是原版的怪癖，如果實際玩起來覺得不對，`fire-bullet` 裡加上
  `(:vx ship)` / `(:vy ship)` 就是另一種版本
- 碰撞用**圓形近似**（小行星基準半徑），不做多邊形內外判定
- 距離用 `wrap-delta` 算環繞世界的最短距離，貼左緣和貼右緣的東西會正確地互相打到
- 大 → 兩顆中 → 各兩顆小 → 消失，子彈與小行星同歸於盡

**飛船與小行星的碰撞還沒做**，連同命數、無敵重生一起留在里程碑 5。


## 換行符

`core.autocrlf = false`，曾因外部工具改寫 LICENSE 換行符造成全檔假差異。
已由 `.gitattributes` 的 `* text=auto eol=lf` 解決：版控內與工作目錄都是 LF，
外部工具寫入 CRLF 也會在 commit 時被正規化回來。

## 里程碑

每個里程碑一個 commit，都要能在瀏覽器中實際看到成果。

- [x] 1. 鷹架：shadow-cljs 專案 + 空白 canvas + 會動的白點（驗證工具鏈）
- [x] 2. 飛船：旋轉、推進、慣性、螢幕環繞 ← 手感核心，需使用者實際試玩調參數
- [x] 3. 小行星：多邊形生成、漂移、環繞
- [x] 4. 射擊與碰撞：子彈生命週期、三級分裂
- [ ] 5. 遊戲規則：分數、命數、關卡遞增、無敵重生
- [ ] 6. UFO 與音效：大小飛碟 AI、心跳音效隨關卡加速
- [ ] 7. 部署：`shadow-cljs release` + GitHub Pages

## 協作方式

- 每個里程碑一個 commit，方便 `git diff` 檢視與回退
- 里程碑 2 的手感參數（推進力、旋轉速度、摩擦力）由使用者試玩後決定，不要自行定案

## 注意事項

- Asteroids 是 Atari 註冊商標。自寫 clone 放 GitHub 學習用沒問題，但不使用原版美術素材，不暗示與 Atari 有關聯。
