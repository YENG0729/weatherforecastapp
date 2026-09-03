package tw.cwa.weather;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;

/**
 * 小工具設定畫面：放置小工具時選擇要顯示哪個縣市。
 *
 * Android 規定設定畫面必須：
 *   1. 一開始就把結果設為 RESULT_CANCELED，使用者中途返回才不會留下空白小工具。
 *   2. 完成時回傳帶有 widgetId 的 RESULT_OK。
 */
public class WidgetConfigActivity extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Spinner citySpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_widget_config);
        setTitle("選擇縣市");

        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            widgetId = intent.getExtras().getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        citySpinner = (Spinner) findViewById(R.id.configCitySpinner);
        List<String> items = new ArrayList<String>();
        for (String name : WeatherData.CITY_NAMES) {
            items.add(name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        citySpinner.setAdapter(adapter);

        String preset = Prefs.lastCity();
        for (int i = 0; i < WeatherData.CITY_NAMES.length; i++) {
            if (WeatherData.CITY_NAMES[i].equals(preset)) {
                citySpinner.setSelection(i);
                break;
            }
        }

        ((Button) findViewById(R.id.configOk)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
    }

    private void confirm() {
        int index = citySpinner.getSelectedItemPosition();
        if (index < 0) {
            index = 0;
        }
        Prefs.setWidgetCity(widgetId, WeatherData.CITY_NAMES[index]);

        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        new WeatherWidgetProvider().onUpdate(this, manager, new int[]{widgetId});

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
