# Issue #36：共享 runtime.json 安全寫入

## 修補範圍

此變更優先阻止資料與檔案 metadata 被破壞，**不宣稱已恢復所有真機上的壁紙寫入功能**。

- 修改流程讀取既有且有效的 JSON 陣列；缺檔、讀失敗、空白、壞 JSON 不會作為空清單回寫。
- 修改相關資源前先預檢；實際提交仍重新檢查快照及寫入條件。同 classloader 的 store 共用提交鎖，避免兩個 writer 同時通過最後比較後互相覆蓋。
- 暫存檔採同目錄、唯一名稱、排他建立；驗證內容及 UID/GID/mode/SELinux 標籤，資料與 metadata 同步後才 rename 替換。
- 無權保留原 metadata、無法讀取標籤、標籤不同、寫入／同步／驗證／rename 失敗，均拒絕替換 runtime.json。不刪原檔、不退回覆寫複製、不放寬為 777、不啟動 root shell。
- 只讀展示仍允許缺少 runtime.json；其空清單 fallback 不可用於修改。

## 明確限制

1. `com.xiaomi.subscreencenter` 的未提權程序通常不能把新 inode 的 owner 轉交給原系統 owner。這種情況會**安全拒寫**，而不是悄悄更換屬主。恢復完整操作需要經裝置驗證的系統身分寫入介面；本變更不新增權限或猜測系統 UID。
2. 修改流程要求既有 runtime.json；檔案尚未建立時，包含既有模板設定入口也會安全拒絕，而不初始化未知 owner 的共享檔案。只讀展示不受此限制。
3. 已經被舊版本改壞的屬主／資料不能從現存檔案可靠推回。此變更不自動還原、不硬編碼 `6101`，也不重建遺失清單。請先保存原始資料與已知正常備份，再由裝置擁有者安排恢復。
4. 快照比較能拒絕已觀察到的其他 writer 更新；最後比較與 rename 之間仍不是核心層 compare-and-swap。未協作的系統 writer、可寫目錄下的惡意路徑替換仍存在競爭窗口。完全互斥需所有 writer 共用系統端協議。
5. 保證針對 runtime.json 的提交失敗保全，不是整個壁紙資源目錄的多檔案交易。預檢可提早攔截常見權限拒絕，之後若系統狀態改變，其他資源仍可能已更新。
6. 暫存內容與 metadata 有同步，但尚未驗證跨斷電的目錄項持久化；不把程序內原子替換等同完整斷電保證。

## 自動回歸

`SharedRuntimeFileStoreTest` 覆蓋成功保留 metadata、缺檔／讀錯誤／格式拒絕、寫入／同步／metadata／rename 失敗、暫存驗證、清理錯誤、過期快照、另一個 writer、真正雙執行緒競爭、預檢及預檢後權限變化。並發回歸另以移除提交鎖的 mutation 版本驗證，確認測試會失敗。`RuntimeJsonFormatTest` 覆蓋空陣列、有效陣列、空白、截斷、非陣列及尾隨垃圾。

可執行：

```sh
./gradlew :app:testDebugUnitTest --tests 'hk.uwu.reareye.utils.SharedRuntimeFileStoreTest' --tests 'hk.uwu.reareye.utils.RuntimeJsonFormatTest'
```

## 真機驗收（尚未執行）

- 在有備份的測試裝置，記錄 runtime.json 原始 bytes、有效 JSON 陣列項目、UID/GID/mode、SELinux 標籤與 inode；另核對實際宿主 package UID，不把它誤認為 REAREye UI UID。
- 覆蓋匯入、刪除、metadata 更新、套件替換、預覽產生、模板設定／重設。成功時只有預期清單內容改變；UID/GID/mode/標籤不變。
- 權限不足或標籤讀取失敗時必須回報失敗，runtime.json bytes 與 metadata 完全不變；不能提示成功。
- 在其他 writer 更新後重試舊快照須失敗；仍須另外測系統同時套用主題並記錄未協作競爭限制。
- 檢查連續操作的 fd 數量、暫存清理、系統套用結果及重啟後持久化；確認沒有新 EACCES／AVC 拒絕。
- 真機未驗證前維持 draft，不將模擬測試當作 HyperOS／SELinux 相容性通過。
