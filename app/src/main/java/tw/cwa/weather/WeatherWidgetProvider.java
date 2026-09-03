package tw.cwa.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桌面小工具。
 *
 * 顯示選定縣市的天氣重點與接下來三個時段，可放在手機桌面上。
 *
 * 幾個 Android 平台上必須注意的地方：
 *   1. onUpdate() 執行在主執行緒，且系統只給很短的時間，
 *      所以網路請求一定要丟到背景，先畫出「更新中」再非同步補上資料。
 *   2. 小工具的更新可能在 App 從未開啟的情況下發生，
 *      因此每個進入點都要先呼叫 Prefs.init()。
 *   3. updatePeriodMillis 最短只能設 30 分鐘，系統還可能延後，
 *      所以另外提供手動更新按鈕。
 */
public class WeatherWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "tw.cwa.weather.WIDGET_REFRESH";
    private static final String EXTRA_WIDGET_ID = "widgetId";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Prefs.init(context);
        for (int widgetId : widgetIds) {
            refresh(context, manager, widgetId, false);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        Prefs.init(context);
        if (!ACTION_REFRESH.equals(intent.getAction())) {
            return;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            refreshAll(context);
        } else {
            refresh(context, manager, widgetId, true);
        }
    }

    @Override
    public void onDeleted(Context context, int[] widgetIds) {
        Prefs.init(context);
        for (int widgetId : widgetIds) {
            Prefs.removeWidget(widgetId);
        }
    }

    /** 讓 App 內查詢完成後也能同步更新桌面上的小工具。 */
    public static void refreshAll(Context context) {
        Prefs.init(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, WeatherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null) {
            return;
        }
        for (int widgetId : ids) {
            refresh(context, manager, widgetId, true);
        }
    }

    // ------------------------------------------------------------------

    private static void refresh(final Context context, final AppWidgetManager manager,
                                final int widgetId, final boolean force) {
        final String city = Prefs.widgetCity(widgetId);

        // 先立刻畫出目前狀態，避免小工具長時間空白
        RemoteViews loading = baseViews(context, widgetId, city);
        loading.setTextViewText(R.id.widgetStatus, "更新中…");
        manager.updateAppWidget(widgetId, loading);

        if (!Prefs.hasAuthorizationKey()) {
            RemoteViews views = baseViews(context, widgetId, city);
            views.setTextViewText(R.id.widgetWx, "尚未設定授權碼");
            views.setTextViewText(R.id.widgetTemp, "");
            views.setTextViewText(R.id.widgetDetail, "請開啟 App 設定");
            views.setTextViewText(R.id.widgetStatus, "");
            hideUpcoming(views);
            manager.updateAppWidget(widgetId, views);
            return;
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                if (force) {
                    WeatherData.clearCache();
                }
                WeatherData data = new WeatherData();
                WeatherData.Forecast shortTerm = null;
                WeatherData.Forecast week = null;
                String error = null;
                try {
                    shortTerm = data.get36HourForecast(city);
                } catch (Exception e) {
                    error = firstLine(e);
                }
                try {
                    week = data.getWeekForecast(city);
                } catch (Exception e) {
                    // 一週資料拿不到不影響主要顯示
                }
                manager.updateAppWidget(widgetId,
                        buildViews(context, widgetId, city, shortTerm, week, error));
            }
        });
    }

    private static RemoteViews buildViews(Context context, int widgetId, String city,
                                          WeatherData.Forecast shortTerm,
                                          WeatherData.Forecast week, String error) {
        RemoteViews views = baseViews(context, widgetId, city);
        String stamp = new SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)
                .format(new Date());

        if (shortTerm == null || shortTerm.isEmpty()) {
            views.setTextViewText(R.id.widgetWx, "查詢失敗");
            views.setTextViewText(R.id.widgetTemp, "");
            views.setTextViewText(R.id.widgetDetail,
                    error == null ? "查無資料" : error);
            views.setTextViewText(R.id.widgetStatus, stamp + " 更新失敗");
            hideUpcoming(views);
            return views;
        }

        WeatherData.Row current = shortTerm.rows.get(0);
        views.setTextViewText(R.id.widgetWx, dash(current.wx));
        views.setTextViewText(R.id.widgetTemp, dash(current.temp) + " ℃");

        StringBuilder detail = new StringBuilder();
        if (current.pop.length() > 0) {
            detail.append("降雨 ").append(current.pop);
        }
        if (current.comfort.length() > 0) {
            if (detail.length() > 0) {
                detail.append("　");
            }
            detail.append(current.comfort);
        }
        views.setTextViewText(R.id.widgetDetail, detail.toString());

        List<WeatherData.Row> upcoming = upcoming(current, shortTerm, week, 3);
        int[] slots = {R.id.widgetNext1, R.id.widgetNext2, R.id.widgetNext3};
        for (int i = 0; i < slots.length; i++) {
            if (i < upcoming.size()) {
                WeatherData.Row row = upcoming.get(i);
                StringBuilder text = new StringBuilder();
                text.append(row.date).append(' ').append(row.period)
                    .append('\n').append(dash(row.temp));
                if (row.pop.length() > 0) {
                    text.append("  ").append(row.pop);
                }
                views.setTextViewText(slots[i], text.toString());
                views.setViewVisibility(slots[i], View.VISIBLE);
            } else {
                views.setViewVisibility(slots[i], View.GONE);
            }
        }

        views.setTextViewText(R.id.widgetStatus, "更新於 " + stamp);
        return views;
    }

    /**
     * 取出目前時段之後的預報。
     *
     * 優先使用一週預報，因為它的時段切法一致（白天／晚上各 12 小時）。
     * 36 小時預報的時段邊界不同，兩者混在一起會出現「晚上」排在「白天」
     * 之前這種看起來像排錯的畫面（其實 00:00 也算晚上）。
     */
    private static List<WeatherData.Row> upcoming(WeatherData.Row current,
                                                  WeatherData.Forecast shortTerm,
                                                  WeatherData.Forecast week,
                                                  int limit) {
        List<WeatherData.Row> source =
                (week != null && !week.isEmpty()) ? week.rows : shortTerm.rows;
        List<WeatherData.Row> later = new ArrayList<WeatherData.Row>();
        for (WeatherData.Row row : source) {
            if (row.start.length() > 0 && row.start.compareTo(current.start) > 0) {
                later.add(row);
            }
        }
        Collections.sort(later, new Comparator<WeatherData.Row>() {
            @Override
            public int compare(WeatherData.Row a, WeatherData.Row b) {
                return a.start.compareTo(b.start);
            }
        });
        return later.subList(0, Math.min(limit, later.size()));
    }

    private static RemoteViews baseViews(Context context, int widgetId, String city) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
        views.setTextViewText(R.id.widgetCity, city);

        // 點小工具本體 -> 開啟 App
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widgetRoot,
                PendingIntent.getActivity(context, widgetId, open, pendingFlags()));

        // 點更新鈕 -> 只重新整理這一個小工具
        Intent refresh = new Intent(context, WeatherWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        refresh.putExtra(EXTRA_WIDGET_ID, widgetId);
        views.setOnClickPendingIntent(R.id.widgetRefresh,
                PendingIntent.getBroadcast(context, widgetId, refresh, pendingFlags()));
        return views;
    }

    private static void hideUpcoming(RemoteViews views) {
        views.setViewVisibility(R.id.widgetNext1, View.GONE);
        views.setViewVisibility(R.id.widgetNext2, View.GONE);
        views.setViewVisibility(R.id.widgetNext3, View.GONE);
    }

    /**
     * Android 12（API 31）起，PendingIntent 必須明確指定可變或不可變，
     * 否則會直接丟出例外。這裡一律使用不可變的版本。
     */
    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static String dash(String value) {
        return (value == null || value.length() == 0) ? "—" : value;
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
