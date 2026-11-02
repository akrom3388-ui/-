package uz.nasos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Engine.init(this);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        startForegroundService(new Intent(this, PollService.class));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b1220"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onPause() {
        Engine.save();
        super.onPause();
    }

    class Bridge {
        @JavascriptInterface
        public String getConfig() {
            return Engine.configJson();
        }

        @JavascriptInterface
        public void saveConfig(String json) {
            Engine.saveConfig(json);
        }

        @JavascriptInterface
        public String getLive() {
            return Engine.live();
        }

        @JavascriptInterface
        public String getHistory(int hours) {
            return Engine.history(hours);
        }

        @JavascriptInterface
        public void sendCommand(String code, String valueJson) {
            Engine.command(code, valueJson);
        }

        @JavascriptInterface
        public void resetData() {
            Engine.resetData();
        }

        @JavascriptInterface
        public void exportCsv() {
            final String csv = Engine.csv();
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, csv);
                    startActivity(Intent.createChooser(i, "Eksport"));
                }
            });
        }

        @JavascriptInterface
        public void openBattery() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }
}
