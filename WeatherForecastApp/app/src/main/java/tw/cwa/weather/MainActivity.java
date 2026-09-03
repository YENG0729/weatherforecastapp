package tw.cwa.weather;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主畫面。
 *
 * 版面沿襲桌面版：城市與日期選單、今明預報摘要、36 小時詳細表、
 * 一週預報表格、狀態列。
 *
 * 兩個沿襲自桌面版的重要行為：
 *   1. 一週預報與今明預報分開取，其中一份失敗仍顯示另一份。
 *   2. 縣市選單只在「真的換了縣市」時才查詢，避免重複請求。
 */
public class MainActivity extends Activity {

    private static final String ALL_DATES = "全部顯示";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WeatherData weatherData = new WeatherData();
    private final List<WeatherData.Row> weekRows = new ArrayList<WeatherData.Row>();

    private Spinner citySpinner;
    private Spinner dateSpinner;
    private TextView headerText;
    private TextView rangeText;
    private TextView summaryText;
    private TableLayout detailTable;
    private TableLayout weekTable;
    private TextView statusText;
    private ProgressBar progress;

    private boolean updatingDates;
    private String currentCity = "";
    private int requestId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_main);

        citySpinner = (Spinner) findViewById(R.id.citySpinner);
        dateSpinner = (Spinner) findViewById(R.id.dateSpinner);
        headerText = (TextView) findViewById(R.id.headerText);
        rangeText = (TextView) findViewById(R.id.rangeText);
        summaryText = (TextView) findViewById(R.id.summaryText);
        detailTable = (TableLayout) findViewById(R.id.detailTable);
        weekTable = (TableLayout) findViewById(R.id.weekTable);
        statusText = (TextView) findViewById(R.id.statusText);
        progress = (ProgressBar) findViewById(R.id.progress);

        setupCitySpinner();
        setupDateSpinner();

        if (!Prefs.hasAuthorizationKey()) {
            promptForKey();
        } else {
            statusText.setText("就緒，請選擇城市。");
            restoreLastCity();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 選單
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "重新查詢");
        menu.add(0, 2, 0, "設定");
        menu.add(0, 3, 0, "資料來源測試");
        menu.add(0, 4, 0, "關於");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                WeatherData.clearCache();
                reload(true);
                return true;
            case 2:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case 3:
                startActivity(new Intent(this, SourceDiagActivity.class));
                return true;
            case 4:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 從設定頁回來，若剛填好授權碼就自動查一次
        if (Prefs.hasAuthorizationKey() && currentCity.length() == 0) {
            restoreLastCity();
        }
    }

    // ------------------------------------------------------------------
    // 選單元件
    // ------------------------------------------------------------------

    private void setupCitySpinner() {
        List<String> items = new ArrayList<String>();
        items.add("--請選擇城市--");
        for (String name : WeatherData.CITY_NAMES) {
            items.add(name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        citySpinner.setAdapter(adapter);
        citySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                reload(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupDateSpinner() {
        setDateOptions(new ArrayList<String>());
        dateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (!updatingDates) {
                    fillWeekTable();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setDateOptions(List<String> dates) {
        updatingDates = true;
        try {
            List<String> items = new ArrayList<String>();
            items.add(ALL_DATES);
            items.addAll(dates);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                    this, android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            dateSpinner.setAdapter(adapter);
            dateSpinner.setSelection(0);
        } finally {
            updatingDates = false;
        }
    }

    private void restoreLastCity() {
        String last = Prefs.lastCity();
        if (last.length() == 0) {
            return;
        }
        for (int i = 0; i < WeatherData.CITY_NAMES.length; i++) {
            if (WeatherData.CITY_NAMES[i].equals(last)) {
                citySpinner.setSelection(i + 1);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // 查詢
    // ------------------------------------------------------------------

    private void reload(boolean force) {
        int index = citySpinner.getSelectedItemPosition();
        if (index <= 0) {
            clearAll();
            statusText.setText("請選擇城市。");
            return;
        }
        final String city = WeatherData.CITY_NAMES[index - 1];
        if (!force && city.equals(currentCity)) {
            return;              // 同一個縣市不重複查詢
        }
        currentCity = city;
        Prefs.setLastCity(city);

        requestId++;
        final int thisRequest = requestId;
        progress.setVisibility(View.VISIBLE);
        statusText.setText("查詢「" + city + "」中，請稍候…");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 兩份資料分開抓，一份失敗不影響另一份
                WeatherData.Forecast week = null;
                Exception weekError = null;
                try {
                    week = weatherData.getWeekForecast(city);
                } catch (Exception e) {
                    weekError = e;
                }
                WeatherData.Forecast shortTerm = null;
                Exception shortError = null;
                try {
                    shortTerm = weatherData.get36HourForecast(city);
                } catch (Exception e) {
                    shortError = e;
                }
                final WeatherData.Forecast finalWeek = week;
                final WeatherData.Forecast finalShort = shortTerm;
                final Exception finalWeekError = weekError;
                final Exception finalShortError = shortError;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (thisRequest != requestId) {
                            return;          // 已被較新的查詢取代
                        }
                        progress.setVisibility(View.GONE);
                        if (finalWeek == null && finalShort == null) {
                            showFailure(city, finalWeekError != null
                                    ? finalWeekError : finalShortError);
                        } else {
                            showForecast(city, finalWeek, finalShort,
                                    finalWeekError, finalShortError);
                        }
                    }
                });
            }
        });
    }

    private void showForecast(String city, WeatherData.Forecast week,
                              WeatherData.Forecast shortTerm,
                              Exception weekError, Exception shortError) {
        headerText.setText(city + " 今明天氣預報");

        if (shortTerm != null) {
            rangeText.setText(shortTerm.validRange.length() > 0
                    ? "預報時間 " + shortTerm.validRange : "");
            summaryText.setText(WeatherData.summarize36Hour(shortTerm));
            fillDetailTable(shortTerm);
        } else {
            rangeText.setText("");
            summaryText.setText("今明預報取得失敗：\n" + firstLine(shortError));
            fillDetailTable(null);
        }

        weekRows.clear();
        if (week != null) {
            weekRows.addAll(week.rows);
        }
        Set<String> dates = new LinkedHashSet<String>();
        for (WeatherData.Row row : weekRows) {
            dates.add(row.date);
        }
        setDateOptions(new ArrayList<String>(dates));
        fillWeekTable();

        statusText.setText(buildStatus(city, week, shortTerm, weekError));

        // 讓桌面小工具跟著更新成同一個縣市的資料
        WeatherWidgetProvider.refreshAll(this);
    }

    private String buildStatus(String city, WeatherData.Forecast week,
                               WeatherData.Forecast shortTerm, Exception weekError) {
        if (week == null) {
            return "今明預報已取得；一週預報失敗：" + firstLine(weekError);
        }
        if (week.isEmpty()) {
            return "氣象署目前未提供「" + city + "」的一週預報資料。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("查詢完成：").append(week.displayLocation())
          .append("，共 ").append(week.rows.size()).append(" 個時段");
        if (week.validRange.length() > 0) {
            sb.append("。有效期間 ").append(week.validRange);
        }
        if (shortTerm == null) {
            sb.append("（今明預報取得失敗）");
        }
        return sb.toString();
    }

    private void showFailure(String city, Exception error) {
        clearAll();
        String message = (error == null || error.getMessage() == null)
                ? "原因不明" : error.getMessage();
        statusText.setText("查詢失敗：" + message.replace('\n', ' '));
        new AlertDialog.Builder(this)
                .setTitle("查詢失敗")
                .setMessage("查詢「" + city + "」失敗。\n\n" + message)
                .setPositiveButton("確定", null)
                .show();
    }

    private void clearAll() {
        headerText.setText("今明天氣預報");
        rangeText.setText("");
        summaryText.setText("");
        fillDetailTable(null);
        weekRows.clear();
        setDateOptions(new ArrayList<String>());
        fillWeekTable();
    }

    // ------------------------------------------------------------------
    // 表格
    // ------------------------------------------------------------------

    private void fillDetailTable(WeatherData.Forecast forecast) {
        detailTable.removeAllViews();
        detailTable.addView(makeRow(true, "預報時段", "天氣概況", "溫度(℃)", "降雨", "舒適度"));
        if (forecast == null) {
            return;
        }
        for (WeatherData.Row row : forecast.rows) {
            detailTable.addView(makeRow(false, row.range,
                    dash(row.wx), dash(row.temp), dash(row.pop), dash(row.comfort)));
        }
    }

    private void fillWeekTable() {
        String chosen = (dateSpinner.getSelectedItem() == null)
                ? ALL_DATES : dateSpinner.getSelectedItem().toString();
        weekTable.removeAllViews();
        weekTable.addView(makeRow(true, "日期", "時段", "溫度(℃)", "降雨", "天氣概況"));
        for (WeatherData.Row row : weekRows) {
            if (!ALL_DATES.equals(chosen) && !chosen.equals(row.date)) {
                continue;
            }
            weekTable.addView(makeRow(false, row.date, row.period,
                    dash(row.temp), dash(row.pop), dash(row.wx)));
        }
    }

    private TableRow makeRow(boolean header, String... values) {
        TableRow row = new TableRow(this);
        row.setLayoutParams(new TableLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (header) {
            row.setBackgroundColor(Color.parseColor("#DCE6F1"));
        }
        for (int i = 0; i < values.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(values[i]);
            cell.setPadding(12, 10, 12, 10);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setTextSize(14);
            if (header) {
                cell.setTypeface(Typeface.DEFAULT_BOLD);
                cell.setTextColor(Color.parseColor("#1A3A6B"));
            } else {
                cell.setTextColor(Color.parseColor("#202020"));
            }
            // 最後一欄吃掉剩餘寬度，其餘依內容
            TableRow.LayoutParams params = new TableRow.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == values.length - 1) {
                params.width = 0;
                params.weight = 1f;
            }
            cell.setLayoutParams(params);
            row.addView(cell);
        }
        return row;
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

    // ------------------------------------------------------------------

    private void promptForKey() {
        new AlertDialog.Builder(this)
                .setTitle("尚未設定授權碼")
                .setMessage("中央氣象署開放資料 API 需要一組免費授權碼才能查詢。\n\n"
                        + "是否現在開啟設定畫面填入？\n\n"
                        + "（授權碼請至 " + WeatherData.SIGNUP_URL + " 註冊會員後取得）")
                .setPositiveButton("前往設定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton("稍後", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("關於")
                .setMessage("一週天氣預報查詢（Android 版 1.0）\n\n"
                        + "資料來源：交通部中央氣象署 開放資料平臺\n"
                        + "  今明 36 小時天氣預報（" + WeatherData.DATASET_36HOUR + "）\n"
                        + "  一週縣市天氣預報（" + WeatherData.DATASET_WEEK + "）\n\n"
                        + "天氣預報資料及版權來自交通部中央氣象署")
                .setPositiveButton("確定", null)
                .show();
    }
}
