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

## 開發環境

- Temurin JDK 21.0.11（ClojureScript 編譯器需要 JVM）
- Node.js v24.18.0 / npm 11.16.0

## 建置指令

```bash
npm install                    # 安裝依賴
npx shadow-cljs watch app      # 開發模式 + hot reload → http://localhost:8080
npx shadow-cljs release app    # 產出正式版到 public/js
```

- 開發模式的 shadow-cljs 主控台在 http://localhost:9630
- 產出物 `public/js/` 不進版控；部署時（里程碑 7）才 release 後另行處理

## 專案結構

```
public/index.html        頁面外殼與 canvas CSS（4:3 滿版）
src/asteroids/core.cljs  遊戲全部程式碼
shadow-cljs.edn          建置設定（:dev-http 8080 → public）
```

`core.cljs` 的分層：`initial-state` / `tick`（純函數，可測）→ `draw!`（唯一 side effect）
→ `frame!`（rAF 迴圈）。遊戲座標固定為 1024×768 邏輯單位，`ensure-size!` 每幀
把 canvas 緩衝區對齊實際顯示尺寸並縮放 context，所以視窗大小與 devicePixelRatio
都不影響遊戲數值。

`state` / `started?` 用 `defonce`，hot reload 時遊戲狀態與迴圈都不會重來。


## 換行符

`core.autocrlf = false`，曾因外部工具改寫 LICENSE 換行符造成全檔假差異。
已由 `.gitattributes` 的 `* text=auto eol=lf` 解決：版控內與工作目錄都是 LF，
外部工具寫入 CRLF 也會在 commit 時被正規化回來。

## 里程碑

每個里程碑一個 commit，都要能在瀏覽器中實際看到成果。

- [x] 1. 鷹架：shadow-cljs 專案 + 空白 canvas + 會動的白點（驗證工具鏈）
- [ ] 2. 飛船：旋轉、推進、慣性、螢幕環繞 ← 手感核心，需使用者實際試玩調參數
- [ ] 3. 小行星：多邊形生成、漂移、環繞
- [ ] 4. 射擊與碰撞：子彈生命週期、三級分裂
- [ ] 5. 遊戲規則：分數、命數、關卡遞增、無敵重生
- [ ] 6. UFO 與音效：大小飛碟 AI、心跳音效隨關卡加速
- [ ] 7. 部署：`shadow-cljs release` + GitHub Pages

## 協作方式

- 每個里程碑一個 commit，方便 `git diff` 檢視與回退
- 里程碑 2 的手感參數（推進力、旋轉速度、摩擦力）由使用者試玩後決定，不要自行定案

## 注意事項

- Asteroids 是 Atari 註冊商標。自寫 clone 放 GitHub 學習用沒問題，但不使用原版美術素材，不暗示與 Atari 有關聯。
