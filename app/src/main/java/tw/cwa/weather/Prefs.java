package tw.cwa.weather;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 設定儲存。
 *
 * 桌面版用 connect.inf 檔案，Android 改用 SharedPreferences（系統標準做法，
 * 使用者清除 App 資料時會一併清掉，也不需要處理檔案權限）。
 *
 * 對外介面刻意與桌面版的 AppConfig 保持一致，
 * 這樣 WeatherData 幾乎不用改就能共用。
 *
 * 注意：這是靜態存取，使用前必須先呼叫 init()。
 * App 的 Application 類別與所有進入點（Activity、AppWidgetProvider）都會呼叫，
 * 因為小工具的更新可能在 Activity 尚未啟動時就發生。
 */
public final class Prefs {

    private static final String FILE = "weather_settings";

    private static final String KEY_CONNECT_TYPE = "connectType";
    private static final String KEY_PROXY_HOST = "proxyHost";
    private static final String KEY_PROXY_PORT = "proxyPort";
    private static final String KEY_AUTH = "authorizationKey";
    private static final String KEY_LAST_CITY = "lastCity";

    private static SharedPreferences preferences;

    private Prefs() {
    }

    /** 每個進入點都要呼叫；重複呼叫沒有副作用。 */
    public static synchronized void init(Context context) {
        if (preferences == null && context != null) {
            preferences = context.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static String get(String key, String fallback) {
        if (preferences == null) {
            return fallback;
        }
        String value = preferences.getString(key, fallback);
        return (value == null) ? fallback : value.trim();
    }

    private static void put(String key, String value) {
        if (preferences != null) {
            preferences.edit().putString(key, value == null ? "" : value.trim()).apply();
        }
    }

    // ------------------------------------------------------------------

    public static String connectType() {
        return get(KEY_CONNECT_TYPE, "0");
    }

    public static void setConnectType(String value) {
        put(KEY_CONNECT_TYPE, (value == null || value.length() == 0) ? "0" : value);
    }

    public static String proxyHost() {
        return get(KEY_PROXY_HOST, "");
    }

    public static void setProxyHost(String value) {
        put(KEY_PROXY_HOST, value);
    }

    public static String proxyPort() {
        return get(KEY_PROXY_PORT, "");
    }

    public static void setProxyPort(String value) {
        put(KEY_PROXY_PORT, value);
    }

    public static int proxyPortNumber() {
        try {
            return Integer.parseInt(proxyPort());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean useProxy() {
        return "1".equals(connectType())
                && proxyHost().length() > 0
                && proxyPortNumber() > 0;
    }

    public static String authorizationKey() {
        return get(KEY_AUTH, "");
    }

    public static void setAuthorizationKey(String value) {
        put(KEY_AUTH, value);
    }

    public static boolean hasAuthorizationKey() {
        return authorizationKey().length() > 0;
    }

    /** 最後查詢的縣市，App 與小工具共用，重開會回到上次的選擇。 */
    public static String lastCity() {
        return get(KEY_LAST_CITY, "");
    }

    public static void setLastCity(String value) {
        put(KEY_LAST_CITY, value);
    }

    // ------------------------------------------------------------------
    // 每個小工具實例各自記住自己的縣市
    // ------------------------------------------------------------------

    public static String widgetCity(int widgetId) {
        String city = get("widget_" + widgetId, "");
        if (city.length() > 0) {
            return city;
        }
        String last = lastCity();
        return (last.length() > 0) ? last : WeatherData.CITY_NAMES[0];
    }

    public static void setWidgetCity(int widgetId, String city) {
        put("widget_" + widgetId, city);
    }

    public static void removeWidget(int widgetId) {
        if (preferences != null) {
            preferences.edit().remove("widget_" + widgetId).apply();
        }
    }
}
