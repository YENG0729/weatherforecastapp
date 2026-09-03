package tw.cwa.weather;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 資料來源測試。
 *
 * 逐一連線每個候選端點，回報 HTTP 狀態與能否解析。
 * 氣象署的「端點 ↔ 資料集」並非所有組合都成立
 * （F-C0032-005 不支援 datastore 端點，會回 404），
 * 這支工具讓使用者在自己的網路環境實際測出可用組合。
 *
 * 報告中不會顯示授權碼。
 */
public class SourceDiagActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_diag);
        setTitle("資料來源測試");

        final TextView output = (TextView) findViewById(R.id.diagText);
        output.setText("測試中，請稍候…\n（會逐一連線各端點，約需十餘秒）");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final String report = buildReport();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        output.setText(report);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private String buildReport() {
        String city = Prefs.lastCity();
        if (city.length() == 0) {
            city = WeatherData.CITY_NAMES[16];      // 預設宜蘭縣
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【1】環境\n");
        sb.append("  Android : ").append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("  連線方式 : ").append(Prefs.useProxy()
                ? "透過 Proxy " + Prefs.proxyHost() + ":" + Prefs.proxyPort()
                : "直接連線").append('\n');
        sb.append("  授權碼   : ").append(Prefs.hasAuthorizationKey() ? "已設定" : "尚未設定").append('\n');
        sb.append("  測試縣市 : ").append(city).append("\n\n");

        if (!Prefs.hasAuthorizationKey()) {
            sb.append("尚未設定授權碼，無法測試。請先於「設定」填入。\n");
            return sb.toString();
        }

        sb.append("【2】逐一測試各端點\n\n");
        WeatherData data = new WeatherData();
        int usable = 0;
        WeatherData.Source[][] groups = {WeatherData.SHORT_SOURCES, WeatherData.WEEK_SOURCES};
        for (WeatherData.Source[] group : groups) {
            for (WeatherData.Source source : group) {
                sb.append("  ").append(source).append('\n');
                try {
                    WeatherData.Forecast forecast = data.getForecast(source, city);
                    usable++;
                    sb.append("    可用：").append(forecast.displayLocation())
                      .append("，").append(forecast.rows.size()).append(" 個時段")
                      .append(forecast.subLocation.length() > 0
                              ? "（鄉鎮層級）" : "（縣市層級）").append('\n');
                } catch (Exception e) {
                    sb.append("    失敗：").append(firstLine(e)).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append("【3】結論\n");
        if (usable > 0) {
            sb.append("  共有 ").append(usable)
              .append(" 個端點可正常取得資料，程式會自動採用第一個可用的。\n");
        } else {
            sb.append("  沒有任何端點可取得資料。\n");
            sb.append("  若全是 401/403，請檢查授權碼；\n");
            sb.append("  若全是連線逾時，請確認網路或 Proxy 設定。\n");
        }
        return sb.toString();
    }

    private static String firstLine(Exception error) {
        if (error == null || error.getMessage() == null) {
            return "原因不明";
        }
        String text = error.getMessage();
        int cut = text.indexOf('\n');
        return (cut < 0) ? text : text.substring(0, cut);
    }
}
