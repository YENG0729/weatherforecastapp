package tw.cwa.weather;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 天氣資料存取層。
 *
 * 原版是抓 http://www.cwb.gov.tw/rss/forecast/36_XX.xml 這組 RSS，
 * 以及 /V7/forecast/taiwan/*.htm 這些網頁再用字串硬切。中央氣象局於 2023 年
 * 改制為中央氣象署，網域換成 cwa.gov.tw，上述 RSS 與 V7 頁面都已停用，
 * 因此改接「氣象資料開放平臺」的 JSON API：
 *
 *   一般天氣預報－今明 36 小時天氣預報   F-C0032-001
 *   一般天氣預報－一週縣市天氣預報       F-C0032-005
 *
 * 使用前需到 https://opendata.cwa.gov.tw 免費註冊會員並取得授權碼，
 * 填入「設定 -&gt; 設定連線方式」視窗中的授權碼欄位即可。
 */
public class WeatherData {

    public static final String DEFAULT_HOST = "https://opendata.cwa.gov.tw";
    public static final String DATASET_36HOUR = "F-C0032-001";
    public static final String DATASET_WEEK = "F-C0032-005";
    public static final String DATASET_WEEK_TOWNSHIP = "F-D0047-091";
    public static final String SIGNUP_URL = "https://opendata.cwa.gov.tw";

    /**
     * 氣象署有兩組取用端點，涵蓋的資料集並不相同：
     *
     *   datastore : /api/v1/rest/datastore/{id}
     *               「資料擷取 API」，只支援部分資料集。
     *               F-C0032-001、F-D0047-001~093 等屬於這一組。
     *
     *   fileapi   : /fileapi/v1/opendataapi/{id}
     *               「資料檔案下載 API」，涵蓋全部資料集，
     *               回傳的是原始檔案格式（根節點為 cwaopendata）。
     *
     * F-C0032-005（一週縣市天氣預報）不在 datastore 支援清單中，
     * 用 datastore 取會得到 HTTP 404，必須改用 fileapi。
     */
    public static final String KIND_DATASTORE = "datastore";
    public static final String KIND_FILEAPI = "fileapi";

    /** 一組候選資料來源。程式會依序嘗試，用第一個成功的。 */
    public static class Source {
        public final String kind;
        public final String dataId;
        public final String note;

        public Source(String kind, String dataId, String note) {
            this.kind = kind;
            this.dataId = dataId;
            this.note = note;
        }

        public String cacheKey() {
            return kind + ":" + dataId;
        }

        public String url(String authorizationKey) {
            if (KIND_FILEAPI.equals(kind)) {
                return host() + "/fileapi/v1/opendataapi/" + dataId
                        + "?Authorization=" + authorizationKey
                        + "&downloadType=WEB&format=JSON";
            }
            return host() + "/api/v1/rest/datastore/" + dataId
                    + "?Authorization=" + authorizationKey + "&format=JSON";
        }

        @Override
        public String toString() {
            return kind + " / " + dataId + "（" + note + "）";
        }
    }

    /** 一週預報的候選來源，依偏好順序排列。 */
    public static final Source[] WEEK_SOURCES = {
            new Source(KIND_FILEAPI, DATASET_WEEK, "一週縣市天氣預報"),
            new Source(KIND_DATASTORE, DATASET_WEEK, "一週縣市天氣預報"),
            new Source(KIND_DATASTORE, DATASET_WEEK_TOWNSHIP, "全臺鄉鎮一週天氣預報"),
            new Source(KIND_FILEAPI, DATASET_WEEK_TOWNSHIP, "全臺鄉鎮一週天氣預報")
    };

    /** 今明 36 小時預報的候選來源。 */
    public static final Source[] SHORT_SOURCES = {
            new Source(KIND_DATASTORE, DATASET_36HOUR, "今明 36 小時天氣預報"),
            new Source(KIND_FILEAPI, DATASET_36HOUR, "今明 36 小時天氣預報")
    };

    /** 主機位址，可用 -Dcwa.host=... 覆寫（測試或端點異動時使用）。 */
    public static String host() {
        return System.getProperty("cwa.host", DEFAULT_HOST);
    }

    /**
     * 縣市名稱，順序與原程式的下拉選單（代號 1~22）完全一致。
     * 這裡使用氣象署 API 的正式寫法「臺」，比對時會自動與「台」互通。
     */
    public static final String[] CITY_NAMES = {
            "臺北市", "新北市", "基隆市", "桃園市", "新竹縣", "新竹市",
            "苗栗縣", "臺中市", "彰化縣", "南投縣", "雲林縣", "嘉義縣",
            "嘉義市", "臺南市", "高雄市", "屏東縣", "宜蘭縣", "花蓮縣",
            "臺東縣", "澎湖縣", "金門縣", "連江縣"
    };

    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};

    /** 同一份資料集在短時間內重複查詢時直接使用快取，避免對氣象署重複請求。 */
    private static final long CACHE_MILLIS = 10L * 60L * 1000L;
    private static final Map<String, Object> CACHE = new HashMap<String, Object>();
    private static final Map<String, Long> CACHE_TIME = new HashMap<String, Long>();

    // ==================================================================
    // 資料模型
    // ==================================================================

    /** 一個預報時段。 */
    public static class Row {
        public String start = "";     // 原始起始時間字串，供排序與去重使用
        public String date = "";      // 08/18 (二)
        public String period = "";    // 白天 / 晚上
        public String range = "";     // 08/18 06:00 ~ 18:00
        public String temp = "";      // 24 ~ 31
        public String pop = "";       // 30%
        public String wx = "";        // 多雲時陰短暫陣雨
        public String comfort = "";   // 舒適
    }

    /** 某縣市的一份預報結果。 */
    public static class Forecast {
        public String city = "";
        public String description = "";
        public String validRange = "";
        public List<Row> rows = new ArrayList<Row>();
        /** 實際採用的資料來源。 */
        public Source source;
        /** 若只取得鄉鎮層級資料，這裡是實際使用的鄉鎮名稱；縣市層級則為空字串。 */
        public String subLocation = "";

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /** 給使用者看的地點說明，例如「宜蘭縣（羅東鎮）」。 */
        public String displayLocation() {
            return (subLocation.length() > 0) ? city + "（" + subLocation + "）" : city;
        }
    }

    // ==================================================================
    // 對外查詢方法
    // ==================================================================

    /** 清除快取，讓下一次查詢重新向氣象署取資料。 */
    public static synchronized void clearCache() {
        CACHE.clear();
        CACHE_TIME.clear();
    }

    /** 由下拉選單代號（1~22）取得縣市名稱，代號不合法時回傳 null。 */
    public static String cityNameByCode(String cityCode) {
        try {
            int index = Integer.parseInt(cityCode.trim());
            if (index >= 1 && index <= CITY_NAMES.length) {
                return CITY_NAMES[index - 1];
            }
        } catch (NumberFormatException ignored) {
            // 代號不是數字，視為不合法
        }
        return null;
    }

    /** 一週天氣預報。依序嘗試候選來源，用第一個成功的。 */
    public Forecast getWeekForecast(String cityName) throws IOException {
        return firstWorkingSource(WEEK_SOURCES, cityName);
    }

    /** 今明 36 小時天氣預報。 */
    public Forecast get36HourForecast(String cityName) throws IOException {
        return firstWorkingSource(SHORT_SOURCES, cityName);
    }

    /**
     * 依序嘗試候選來源。
     *
     * 氣象署的端點與資料集對應關係曾經調整過（例如 F-C0032-005 不支援 datastore
     * 端點，只能走 fileapi），為避免單一來源失效就整個不能用，這裡逐一嘗試，
     * 全部失敗時才把「第一個」錯誤丟出來 —— 第一個來源是最合適的，
     * 它的錯誤訊息對使用者最有參考價值。
     */
    private Forecast firstWorkingSource(Source[] sources, String cityName) throws IOException {
        IOException firstError = null;
        StringBuilder tried = new StringBuilder();
        for (Source source : sources) {
            try {
                return getForecast(source, cityName);
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
                if (tried.length() > 0) {
                    tried.append('\n');
                }
                tried.append("  ・").append(source).append(" → ")
                     .append(String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
        String detail = "所有資料來源都無法取得「" + cityName + "」的預報。\n\n"
                + "各來源的結果：\n" + tried
                + "\n\n可執行「說明 → 資料來源測試」查看詳細狀況。";
        throw new IOException(detail, firstError);
    }

    /** 相容用：以 datastore 端點查詢指定資料集。 */
    public Forecast getForecast(String datasetId, String cityName) throws IOException {
        return getForecast(new Source(KIND_DATASTORE, datasetId, datasetId), cityName);
    }

    // ==================================================================
    // 核心：抓取 + 解析
    // ==================================================================

    /** 從指定來源查詢某縣市的預報。 */
    public Forecast getForecast(Source source, String cityName) throws IOException {
        Object root = fetchDataset(source);
        Loc located = findLocation(root, cityName);
        if (located == null) {
            throw new IOException("這份資料中找不到「" + cityName + "」的預報資料。");
        }
        Object location = located.node;

        Forecast forecast = new Forecast();
        forecast.city = cityName;
        forecast.source = source;
        // 若比對到的是鄉鎮層級資料（縣市名稱相符、但本身是鄉鎮），記下實際鄉鎮名稱
        if (!normalizeCity(located.name).equals(normalizeCity(cityName))) {
            forecast.subLocation = located.name;
        }
        forecast.description = MiniJson.str(
                MiniJson.dig(root, "records", "datasetDescription"));

        // elementName -> (startTime -> value)
        Map<String, Map<String, String>> values = new LinkedHashMap<String, Map<String, String>>();
        // startTime -> endTime，依出現順序保留
        Map<String, String> slots = new LinkedHashMap<String, String>();

        Object elements = MiniJson.get(location, "weatherElement", "WeatherElement");
        for (Object element : MiniJson.list(elements)) {
            String name = canonicalElement(MiniJson.str(
                    MiniJson.get(element, "elementName", "ElementName")));
            Map<String, String> perTime = new LinkedHashMap<String, String>();
            Object times = MiniJson.get(element, "time", "Time");
            for (Object time : MiniJson.list(times)) {
                String start = MiniJson.str(MiniJson.get(time,
                        "startTime", "StartTime", "dataTime", "DataTime"));
                String end = MiniJson.str(MiniJson.get(time, "endTime", "EndTime"));
                if (start.length() == 0) {
                    continue;
                }
                perTime.put(start, extractValue(time));
                if (!slots.containsKey(start)) {
                    slots.put(start, end);
                }
            }
            if (name.length() > 0) {
                values.put(name, perTime);
            }
        }

        List<String> starts = new ArrayList<String>(slots.keySet());
        java.util.Collections.sort(starts);

        for (String start : starts) {
            String end = slots.get(start);
            Row row = new Row();
            row.start = start;
            row.date = formatDate(start);
            row.period = periodName(start);
            row.range = formatRange(start, end);
            row.temp = formatTemperature(values, start);
            row.pop = formatPop(values, start);
            row.wx = firstOf(values, start, "WX");
            row.comfort = firstOf(values, start, "CI", "MINCI", "MAXCI");
            forecast.rows.add(row);
        }

        if (!starts.isEmpty()) {
            String first = starts.get(0);
            String last = starts.get(starts.size() - 1);
            String lastEnd = slots.get(last);
            forecast.validRange = formatRange(first,
                    (lastEnd == null || lastEnd.length() == 0) ? last : lastEnd);
        }
        return forecast;
    }

    /** 取得（必要時下載）整份資料集，回傳已剖析的 JSON 結構。 */
    private Object fetchDataset(Source source) throws IOException {
        String cacheKey = source.cacheKey();
        long now = System.currentTimeMillis();
        Long cachedAt = CACHE_TIME.get(cacheKey);
        if (cachedAt != null && now - cachedAt.longValue() < CACHE_MILLIS) {
            Object cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        String key = Prefs.authorizationKey();
        if (key.length() == 0) {
            throw new IOException("尚未設定氣象署開放資料授權碼。\n"
                    + "請點選「設定 → 設定連線方式」填入授權碼，\n"
                    + "授權碼可至 " + SIGNUP_URL + " 免費註冊取得。");
        }

        String body = httpGet(source.url(key));
        Object root = MiniJson.parse(body);

        String success = MiniJson.str(MiniJson.get(root, "success"));
        if (success.length() > 0 && !"true".equalsIgnoreCase(success)) {
            throw new IOException("氣象署回應查詢失敗，請確認授權碼是否正確或已過期。");
        }

        CACHE.put(cacheKey, root);
        CACHE_TIME.put(cacheKey, Long.valueOf(now));
        return root;
    }

    /** 依設定決定直接連線或走 Proxy，讀回 UTF-8 內容。 */
    private String httpGet(String urlText) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(urlText);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "WeatherForecastApp/1.0 (Android)");

            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("授權碼遭拒（HTTP " + status + "）。\n"
                        + "請確認「設定 → 設定連線方式」中的授權碼是否正確。");
            }
            if (status == 404) {
                throw new IOException("這個端點沒有提供該資料集（HTTP 404）。");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("連線氣象署失敗，HTTP 狀態碼 " + status + "。");
            }
            return readAll(connection.getInputStream());
        } catch (javax.net.ssl.SSLHandshakeException e) {
            throw new IOException(CERT_HELP + "\n\n原始訊息：" + e.getMessage(), e);
        } catch (java.net.UnknownHostException e) {
            throw new IOException("無法解析主機名稱，請確認網路連線或 Proxy 設定。", e);
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("連線氣象署逾時，請稍後再試。", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 憑證驗證失敗時給使用者的說明。 */
    public static final String CERT_HELP =
            "TLS 憑證驗證失敗，Java 不信任氣象署伺服器出示的憑證。\n\n"
          + "注意：Java 有自己的憑證信任清單，和 Windows 的是分開的，\n"
          + "所以瀏覽器連得上不代表 Java 連得上。\n\n"
          + "常見原因：\n"
          + "  1. 這個 Java 版本太舊，內建憑證清單缺少根憑證。\n"
          + "  2. 公司 Proxy 或防毒軟體進行 TLS 攔截，用自家根憑證重簽。\n\n"
          + "請執行「說明 → 連線診斷」查出實際原因並取得處理方式。";

    /**
     * 建立連線，依設定決定直接連線或走 Proxy。
     *
     * Android 的系統憑證庫夠新，不需要桌面版那套「額外信任憑證」機制，
     * 因此這裡不做任何憑證信任的調整，一律使用系統預設驗證。
     */
    private static HttpURLConnection openConnection(String urlText) throws IOException {
        URL url = new URL(urlText);
        if (Prefs.useProxy()) {
            int port = Prefs.proxyPortNumber();
            if (port <= 0) {
                throw new IOException("Proxy 連接埠設定不正確：" + Prefs.proxyPort());
            }
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(Prefs.proxyHost(), port));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[8192];
        int count;
        try {
            while ((count = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    // ==================================================================
    // JSON 結構處理（同時相容氣象署新舊欄位命名）
    // ==================================================================

    /** 走訪 JSON 找到的一個地點節點。 */
    private static class Loc {
        final String name;    // 這個節點自己的名稱（縣市或鄉鎮）
        final String county;  // 所屬縣市（鄉鎮資料才有）
        final Object node;

        Loc(String name, String county, Object node) {
            this.name = name;
            this.county = county;
            this.node = node;
        }
    }

    /**
     * 遞迴走訪整份 JSON，收集所有「同時具備地點名稱與預報因子」的節點。
     *
     * 這樣做是刻意的：氣象署不同端點與不同世代的資料，外層包裝差異很大 ——
     *   datastore 舊版 : records.location[]
     *   datastore 新版 : records.Locations[].Location[]
     *   fileapi        : cwaopendata.dataset.location[]
     *   鄉鎮資料       : records.locations[].location[]（外層有 locationsName 為縣市）
     * 與其為每種形狀各寫一段解析，不如直接找出具備特徵的節點，
     * 日後外層包裝再變也不會整個抓不到資料。
     */
    private static void walkLocations(Object node, String county, List<Loc> out, int depth) {
        if (node == null || depth > 12) {
            return;
        }
        if (node instanceof List) {
            for (Object child : (List<?>) node) {
                walkLocations(child, county, out, depth + 1);
            }
            return;
        }
        if (!(node instanceof Map)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) node;

        // 外層若標示了所屬縣市，往下傳遞
        Object groupName = MiniJson.get(node, "locationsName", "LocationsName");
        String currentCounty = (groupName != null) ? MiniJson.str(groupName) : county;

        Object name = MiniJson.get(node, "locationName", "LocationName");
        Object elements = MiniJson.get(node, "weatherElement", "WeatherElement");
        if (name != null && elements != null) {
            out.add(new Loc(MiniJson.str(name), currentCounty, node));
            return; // 地點節點底下不會再有地點節點
        }
        for (Object child : map.values()) {
            walkLocations(child, currentCounty, out, depth + 1);
        }
    }

    private static List<Loc> allLocations(Object root) {
        List<Loc> found = new ArrayList<Loc>();
        walkLocations(root, "", found, 0);
        return found;
    }

    /**
     * 找出對應的地點。優先取縣市層級；只有鄉鎮資料時，退而取該縣市的第一個鄉鎮，
     * 並在畫面上標示實際使用的鄉鎮名稱，避免使用者誤以為是全縣市的預報。
     */
    private static Loc findLocation(Object root, String cityName) {
        String target = normalizeCity(cityName);
        List<Loc> all = allLocations(root);
        for (Loc loc : all) {
            if (normalizeCity(loc.name).equals(target)) {
                return loc;
            }
        }
        for (Loc loc : all) {
            if (normalizeCity(loc.county).equals(target)) {
                return loc;
            }
        }
        return null;
    }

    /** 「臺」「台」互通，並去除空白，避免縣市名稱寫法不同而比對失敗。 */
    private static String normalizeCity(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("臺", "台").replaceAll("\\s+", "").trim();
    }

    /** 取出單一時段的數值，涵蓋 parameter 與 elementValue 兩種格式。 */
    @SuppressWarnings("unchecked")
    private static String extractValue(Object timeNode) {
        Object parameter = MiniJson.get(timeNode, "parameter", "Parameter");
        if (parameter != null) {
            String name = MiniJson.str(
                    MiniJson.get(parameter, "parameterName", "ParameterName"));
            if (name.length() > 0) {
                return name;
            }
        }
        Object elementValue = MiniJson.get(timeNode, "elementValue", "ElementValue");
        List<Object> candidates = MiniJson.list(elementValue);
        if (!candidates.isEmpty()) {
            Object first = candidates.get(0);
            if (first instanceof Map) {
                Object value = MiniJson.get(first, "value", "Value");
                if (value != null) {
                    return MiniJson.str(value);
                }
                Map<String, Object> map = (Map<String, Object>) first;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (key.toLowerCase(Locale.ROOT).indexOf("measure") < 0
                            && key.toLowerCase(Locale.ROOT).indexOf("unit") < 0) {
                        return MiniJson.str(entry.getValue());
                    }
                }
                return "";
            }
            return MiniJson.str(first);
        }
        return "";
    }

    /**
     * 把預報因子名稱統一成內部代碼。
     * 氣象署部分資料集的新版格式改用中文欄位名（例如「最高溫度」），
     * 這裡一併對應，避免日後格式再調整就整個抓不到資料。
     */
    private static String canonicalElement(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.trim();
        if (name.length() == 0) {
            return "";
        }
        if (name.indexOf("最高體感") >= 0 || name.indexOf("最低體感") >= 0) {
            return "AT";                        // 體感溫度，目前不顯示
        }
        if (name.indexOf("最高溫") >= 0) {
            return "MAXT";
        }
        if (name.indexOf("最低溫") >= 0) {
            return "MINT";
        }
        if (name.indexOf("平均溫度") >= 0 || name.equals("溫度")) {
            return "T";
        }
        if (name.indexOf("天氣現象") >= 0) {
            return "WX";
        }
        if (name.indexOf("降雨機率") >= 0) {
            return "POP";
        }
        if (name.indexOf("舒適度") >= 0) {
            return "CI";
        }
        return name.toUpperCase(Locale.ROOT);
    }

    private static String firstOf(Map<String, Map<String, String>> values,
                                  String start, String... elementNames) {
        for (String name : elementNames) {
            Map<String, String> perTime = values.get(name);
            if (perTime != null) {
                String value = perTime.get(start);
                if (value != null && value.length() > 0) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String formatTemperature(Map<String, Map<String, String>> values, String start) {
        String min = firstOf(values, start, "MINT");
        String max = firstOf(values, start, "MAXT");
        if (min.length() > 0 && max.length() > 0) {
            return min + " ~ " + max;
        }
        if (max.length() > 0) {
            return max;
        }
        if (min.length() > 0) {
            return min;
        }
        return firstOf(values, start, "T");
    }

    private static String formatPop(Map<String, Map<String, String>> values, String start) {
        String pop = firstOf(values, start, "POP", "POP12H", "POP6H");
        if (pop.length() == 0) {
            return "";
        }
        return pop.endsWith("%") ? pop : pop + "%";
    }

    // ==================================================================
    // 時間字串處理（同時支援 "2026-08-18 06:00:00" 與 ISO8601 兩種寫法）
    // ==================================================================

    private static int[] parseDateTime(String text) {
        if (text == null || text.length() < 16) {
            return null;
        }
        try {
            int year = Integer.parseInt(text.substring(0, 4));
            int month = Integer.parseInt(text.substring(5, 7));
            int day = Integer.parseInt(text.substring(8, 10));
            int hour = Integer.parseInt(text.substring(11, 13));
            int minute = Integer.parseInt(text.substring(14, 16));
            return new int[]{year, month, day, hour, minute};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String formatDate(String startTime) {
        int[] parts = parseDateTime(startTime);
        if (parts == null) {
            return startTime;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(parts[0], parts[1] - 1, parts[2]);
        String weekday = WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1];
        return two(parts[1]) + "/" + two(parts[2]) + " (" + weekday + ")";
    }

    private static String periodName(String startTime) {
        int[] parts = parseDateTime(startTime);
        if (parts == null) {
            return "";
        }
        int hour = parts[3];
        if (hour >= 6 && hour < 12) {
            return "白天";
        }
        if (hour >= 12 && hour < 18) {
            return "午後";
        }
        return "晚上";
    }

    private static String formatRange(String startTime, String endTime) {
        int[] start = parseDateTime(startTime);
        int[] end = parseDateTime(endTime);
        if (start == null) {
            return "";
        }
        String head = two(start[1]) + "/" + two(start[2]) + " " + two(start[3]) + ":" + two(start[4]);
        if (end == null) {
            return head;
        }
        String tail = two(end[1]) + "/" + two(end[2]) + " " + two(end[3]) + ":" + two(end[4]);
        return head + " ~ " + tail;
    }

    // ==================================================================
    // 文字輸出（供文字模式與 GUI 上方摘要使用）
    // ==================================================================

    /** 把 36 小時預報整理成幾行摘要文字。 */
    public static String summarize36Hour(Forecast forecast) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < forecast.rows.size(); i++) {
            Row row = forecast.rows.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(row.date).append(' ').append(row.period)
              .append("　").append(row.wx.length() > 0 ? row.wx : "—")
              .append("　氣溫 ").append(row.temp.length() > 0 ? row.temp : "—").append(" ℃");
            if (row.pop.length() > 0) {
                sb.append("　降雨機率 ").append(row.pop);
            }
            if (row.comfort.length() > 0) {
                sb.append("　").append(row.comfort);
            }
        }
        if (sb.length() == 0) {
            sb.append("查無今明天氣預報資料。");
        }
        return sb.toString();
    }

}
