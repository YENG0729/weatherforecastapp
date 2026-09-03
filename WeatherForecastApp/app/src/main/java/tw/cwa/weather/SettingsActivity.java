package tw.cwa.weather;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 設定畫面：授權碼與 Proxy。
 *
 * 與桌面版一致，選「直接連線」時 Proxy 欄位會停用，
 * 儲存前會檢查連接埠是否為合法數字。
 */
public class SettingsActivity extends Activity {

    private RadioButton directRadio;
    private RadioButton proxyRadio;
    private EditText proxyHost;
    private EditText proxyPort;
    private EditText authKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        setContentView(R.layout.activity_settings);
        setTitle("連線設定");

        directRadio = (RadioButton) findViewById(R.id.directRadio);
        proxyRadio = (RadioButton) findViewById(R.id.proxyRadio);
        proxyHost = (EditText) findViewById(R.id.proxyHost);
        proxyPort = (EditText) findViewById(R.id.proxyPort);
        authKey = (EditText) findViewById(R.id.authKey);

        boolean useProxy = "1".equals(Prefs.connectType());
        directRadio.setChecked(!useProxy);
        proxyRadio.setChecked(useProxy);
        proxyHost.setText(Prefs.proxyHost());
        proxyPort.setText(Prefs.proxyPort());
        authKey.setText(Prefs.authorizationKey());
        updateProxyState();

        View.OnClickListener toggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateProxyState();
            }
        };
        directRadio.setOnClickListener(toggle);
        proxyRadio.setOnClickListener(toggle);

        findViewById(R.id.signupLink).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBrowser(WeatherData.SIGNUP_URL);
            }
        });

        ((Button) findViewById(R.id.saveButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.cancelButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void updateProxyState() {
        boolean enabled = proxyRadio.isChecked();
        proxyHost.setEnabled(enabled);
        proxyPort.setEnabled(enabled);
    }

    private void save() {
        String host = proxyHost.getText().toString().trim();
        String port = proxyPort.getText().toString().trim();
        if (proxyRadio.isChecked()) {
            if (host.length() == 0) {
                toast("請輸入 Proxy IP 位址。");
                return;
            }
            int number;
            try {
                number = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                toast("Proxy 連接埠必須是數字。");
                return;
            }
            if (number < 1 || number > 65535) {
                toast("Proxy 連接埠必須介於 1 至 65535 之間。");
                return;
            }
        }

        Prefs.setConnectType(proxyRadio.isChecked() ? "1" : "0");
        Prefs.setProxyHost(host);
        Prefs.setProxyPort(port);
        Prefs.setAuthorizationKey(authKey.getText().toString().trim());
        WeatherData.clearCache();
        WeatherWidgetProvider.refreshAll(this);
        toast("儲存設定資料完成！");
        finish();
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("無法開啟瀏覽器，請手動前往：" + url);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
