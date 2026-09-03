R.java.verify-only
------------------
這份 R.java 是在沒有 Android SDK 的環境下，為了「實際編譯驗證」而由資源檔
自動產生的。正式建置時 aapt2 會產生真正的 R.java，兩者會衝突，
因此**不要**把它放回 app/src/main/java/。

它的用途只有一個：在 Android Studio 之外驗證 Java 程式碼是否引用了
不存在的資源 id。驗證方式：

  javac -source 8 -target 8 -encoding UTF-8 \
        -bootclasspath <android.jar 路徑> \
        -d /tmp/out \
        app/src/main/java/tw/cwa/weather/*.java tools/R.java.verify-only
