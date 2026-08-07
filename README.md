# 7-ELEVEN 貨態查詢 Android App

支援 Android 6.0（API 23）以上。可逐筆查詢多個物流單號、自動辨識驗證碼並彙整貨態結果。

## 主要功能

- 輸入框預設保持空白，提示「請輸入寄件8碼或取件11碼，每行一筆」。
- 支援掃描 QR Code 與常見一維／二維條碼，自動將有效物流單號加入輸入框。
- 內建「圖例展示」按鈕，可離線查看寄件與取件單號的位置及輸入方式。
- 多筆物流單號逐筆查詢。
- 使用 ML Kit OCR 辨識四碼驗證碼，失敗時會自動重試。
- 可顯示網站查詢畫面，方便確認或手動處理。
- 單純顯示查詢結果，不會在手機儲存 CSV 或文字紀錄。
- 可透過 Android 分享功能直接分享查詢結果文字。
- 內建「關於 App」頁面，提供版本、隱私、免責聲明、GitHub 與 Copyright 資訊。
- 使用系統返回鍵離開時會清除輸入單號、查詢結果及暫存工作階段。
- 啟動時會自動檢查 GitHub Releases，也可按「檢查更新」手動確認。
- 發現新版時會顯示版本與更新內容，並可前往 GitHub Release 頁面下載 APK。
- 「圖例展示」使用綠色圓角按鈕，與 App 主題一致。
- 支援瀏海、動態島、狀態列與底部導覽列安全區域。
- 具備自訂 App icon 與 adaptive icon。

## 安裝

從 [GitHub Releases](https://github.com/kkbox2a/seven-eleven-tracker-android/releases) 下載 `SevenElevenTracker-v1.5.1.apk`，傳到 Android 手機後開啟安裝。若手機阻擋側載，請依系統提示允許該瀏覽器或檔案管理器安裝未知應用程式。

套件名稱為 `tw.codex.seveneleventracker`。目前版本為 1.5.1（versionCode 7），簽章與先前版本相同，可直接覆蓋安裝舊版。

## 使用方式

1. 在輸入框中每行輸入一筆物流單號。
2. 也可點擊「掃描 QR／條碼」讀取物流單號；不確定單號位置時可查看圖例。
3. 點擊「開始逐筆查詢」。
4. 查詢完成後可查看或分享結果；App 不會自動儲存紀錄檔。
5. 點擊「關於 App」可查看專案資訊與開啟 GitHub；點擊「檢查更新」可確認最新版。

## 建置

專案使用 Android Gradle Plugin 8.7.3、Gradle 8.9、Java 17 與 Android SDK 35。執行 `gradlew.bat assembleRelease` 後，APK 位於 `app/build/outputs/apk/release/`，檔名會自動包含版本號，例如 `SevenElevenTracker-v1.5.1.apk`。

## 版本規則

專案採用語意化版本（Semantic Versioning）：

- `MAJOR`：破壞相容性、重大架構或介面重新設計，例如 `2.0.0`。
- `MINOR`：新增向下相容功能，例如 `1.5.0`。
- `PATCH`：錯誤修正或小幅調整，例如 `1.5.1`。

次版本可以持續增加為 `1.10.0`、`1.11.0`，不會因數字累積而自動升為 `2.0.0`。

## 注意事項

查詢功能依賴 7-ELEVEN 網站與網路連線；更新檢查使用此專案的公開 GitHub Releases。QR／條碼掃描畫面由 Google Play 服務提供，App 本身不要求相機權限。若網站版面、驗證碼或查詢流程變更，可能需要更新 App。
