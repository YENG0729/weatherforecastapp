# 一週天氣預報（Android 版）

用 Java 重寫的 Android App，功能與外觀比照桌面 Java／Python 版，**並包含桌面小工具**。

---

## 這包裡面沒有 APK，需要建置一次

APK 必須用 Google 的工具鏈（aapt2、d8）打包，我的工作環境連不到 Google 的
伺服器，**所以產不出 APK**。下面三條路任選一條，第一條最省事。

### 路徑 A：用 GitHub 免費建置（不必在自己電腦裝任何東西）

專案已附好自動建置設定，只要有 GitHub 帳號：

1. 到 <https://github.com/new> 建一個新的儲存庫（Private 也可以）
2. 把這個資料夾的內容整包上傳
   （網頁上可直接拖曳；或用 `git push`）
3. 進入該儲存庫的 **Actions** 分頁 → 左側點「**建置 APK**」→
   右側「**Run workflow**」→ 綠色按鈕
4. 等 3～5 分鐘，跑完後點進該次執行，最下方 **Artifacts** 區
   下載 `weather-app-debug-apk`
5. 解壓得到 `app-debug.apk`，傳到手機安裝
   （需在手機開啟「允許安裝未知來源的應用程式」）

GitHub 的免費額度對公開儲存庫無限、私有儲存庫每月 2000 分鐘，
這個建置一次約 3 分鐘。

### 路徑 B：用 Android Studio

1. 下載安裝 <https://developer.android.com/studio>（免費，約 1GB）
2. 開啟這個 `WeatherForecastApp` 資料夾，等 Gradle 同步完成
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. 產出在 `app/build/outputs/apk/debug/app-debug.apk`

或接上手機（開啟 USB 偵錯）直接按 ▶ Run。

### 路徑 C：命令列

若已裝好 Android SDK 並設定 `ANDROID_HOME`：

```bash
./gradlew assembleDebug          # Windows 用 gradlew.bat assembleDebug
```

專案已附 Gradle wrapper，不需要另外安裝 Gradle。

---

## 先講清楚：哪些驗證過、哪些沒有

### 已實際驗證 ✅

| 項目 | 方式 | 結果 |
|---|---|---|
| 全部 Java 程式碼能編譯 | 取得真正的 `android.jar`（API 33）用 javac 編譯 | 34 個 class，零錯誤 |
| Android API 用法正確 | 同上，型別與方法簽章都經編譯器檢查 | 通過 |
| 資源 id 引用 | 交叉比對程式與版面檔 | 30 個 id 完全對應，無缺漏也無多餘 |
| XML 格式 | 全部 10 個檔案解析 | 通過 |
| 資料層邏輯 | 22 項測試，自架假伺服器重現真實情境 | 全部通過 |

### 尚未驗證 ⚠️

- **沒有產生過 APK**，Gradle 建置流程本身沒跑過
- **沒有在實機或模擬器上跑過**，畫面實際長相、小工具在桌面的行為都沒看過
- 版面比例、字級大小可能需要你依實機調整

**第一次建置時如果出錯，把訊息貼給我。**

---

## 一、零外部相依

`app/build.gradle` 的 `dependencies` 是空的 —— **連 AndroidX 都沒用**，
全部使用 Android 框架內建類別（`Activity`、`TableLayout`、`AppWidgetProvider`、
`RemoteViews`）。JSON 解析沿用桌面版自寫的 `MiniJson`。

這延續了整個專案的原則：不引入會隨時間腐朽的相依。
代價是版面用程式碼動態產生表格（沒有 RecyclerView），
但這支 App 的資料量很小，不是問題。

---

## 二、首次使用

新版氣象署 API 需要一組免費授權碼：

1. 前往 <https://opendata.cwa.gov.tw> 註冊會員（免費）
2. 登入後點「取得授權碼」，複製 `CWA-XXXXXXXX-...`
3. App 首次啟動會提示，或從選單「設定」填入

---

## 三、桌面小工具

**加入方式**：長按桌面空白處 → 小工具 → 找到「天氣小工具」→ 拖到桌面。
放置時會先問你要顯示哪個縣市。

**顯示內容**：縣市、目前時段天氣、溫度、降雨機率與舒適度，
下方一列是接下來三個時段。

| 操作 | 效果 |
|---|---|
| 點小工具本體 | 開啟 App |
| 點右上「更新」 | 立即重新查詢 |

**可放多個**，每個各自顯示不同縣市（設定分開儲存）。

### 幾個 Android 平台限制（已在程式中處理）

- **自動更新最短 30 分鐘**：`updatePeriodMillis` 設得更短系統會直接忽略，
  且系統可能再延後。所以另外提供手動更新按鈕。
- **onUpdate 不能做網路請求**：它跑在主執行緒且系統只給很短的時間。
  程式的做法是先立刻畫出「更新中…」，網路請求丟到背景，完成後再更新畫面。
- **PendingIntent 必須指定可變性**：Android 12 起沒指定會直接崩潰，
  程式一律使用 `FLAG_IMMUTABLE`。
- **小工具可能在 App 從未開啟時就更新**：因此每個進入點
  （Activity、AppWidgetProvider）都會先呼叫 `Prefs.init()`。

---

## 四、資料來源

| 用途 | 資料集代號 | 名稱 |
|---|---|---|
| 今明預報 | `F-C0032-001` | 今明 36 小時天氣預報 |
| 一週表格 | `F-C0032-005` | 一週縣市天氣預報 |

### 氣象署有兩組端點，涵蓋的資料集不同

| 端點 | 路徑 | 涵蓋範圍 |
|---|---|---|
| `datastore` | `/api/v1/rest/datastore/{id}` | 只支援部分資料集 |
| `fileapi` | `/fileapi/v1/opendataapi/{id}` | 涵蓋全部資料集 |

`F-C0032-005` **不在 `datastore` 支援清單中**，用 `datastore` 取會得到 HTTP 404。
這是很容易踩到的坑（桌面版就是先踩了這個才發現）。

程式採「多來源自動回退」，依序嘗試，用第一個成功的：

1. `fileapi` + `F-C0032-005`
2. `datastore` + `F-C0032-005`
3. `datastore` + `F-D0047-091`（全臺鄉鎮一週預報）
4. `fileapi` + `F-D0047-091`

若只取得鄉鎮層級資料，狀態列會標示實際使用的鄉鎮（例如「宜蘭縣（宜蘭市）」），
不會讓你誤以為那是全縣市的預報。

想知道哪些端點在你的網路環境可用，用選單的「**資料來源測試**」。
報告中不會顯示授權碼。

---

## 五、原始碼結構

| 檔案 | 說明 |
|---|---|
| `MainActivity.java` | 主畫面 |
| `WeatherData.java` | 資料存取與解析（**與桌面版同源**，多來源回退） |
| `MiniJson.java` | 極簡 JSON 剖析器（**與桌面版完全相同**） |
| `Prefs.java` | 設定儲存（SharedPreferences，介面比照桌面版 AppConfig） |
| `SettingsActivity.java` | 授權碼與 Proxy 設定 |
| `SourceDiagActivity.java` | 資料來源測試 |
| `WeatherWidgetProvider.java` | 桌面小工具 |
| `WidgetConfigActivity.java` | 放置小工具時選縣市 |
| `tools/R.java.verify-only` | **不要放進 src** — 見下方說明 |
| `.github/workflows/build-apk.yml` | GitHub 自動建置設定（路徑 A 用） |

### 關於 tools/R.java.verify-only

正式建置時 aapt2 會自動產生 `R.java`。那份檔案是我在沒有 Android SDK 的
環境下，為了實際編譯驗證而從資源檔自動產生的。
**放回 `app/src/main/java/` 會造成類別重複定義而建置失敗。**

它的用途只有離線驗證：

```bash
javac -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath <android.jar 路徑> \
      -d /tmp/out \
      app/src/main/java/tw/cwa/weather/*.java tools/R.java.verify-only
```

---

## 六、與桌面版的關係

| 項目 | 說明 |
|---|---|
| 資料層 | 同源。`WeatherData` 只改了設定來源與連線建立，解析邏輯完全相同 |
| `MiniJson` | 一字未改 |
| 設定 | **不互通**。Android 用 SharedPreferences，授權碼需要另外填一次 |
| 憑證處理 | 桌面版有「額外信任憑證」機制；Android 系統憑證庫夠新，不需要 |

---

## 七、關於未來再次失效的防範

解析器**遞迴尋找**具備「地點名稱＋預報因子」特徵的節點，不假設外層包裝結構。
舊版 `records.location`、新版 `records.Locations[].Location`、
`fileapi` 的 `cwaopendata.dataset.location`、鄉鎮資料的
`records.locations[].location` 四種都能吃。

預報因子名稱同時支援英文代碼與中文欄位名（`最高溫度`、`天氣現象`、
`降雨機率`、`舒適度`）。縣市名稱的「臺」與「台」自動互通。

---

## 八、已知限制

- **需要授權碼**才能查詢，這是氣象署的規定。
- Proxy 不支援帳號密碼驗證（桌面版也不支援）。
- 小工具自動更新最短 30 分鐘，這是 Android 的限制。
- **尚未在實機驗證過**，見文件開頭說明。
