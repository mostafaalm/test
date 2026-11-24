package com.mostafa.goldtracker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                    return true;
                }
                return false;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        GoldJob.schedule(this);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** Native HTTPS GET so the page is not limited by CORS. Only https URLs. */
    class Bridge {
        @JavascriptInterface
        public String settings() {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("alerts", MODE_PRIVATE);
                String k = sp.getString("apiKey", "");
                return new JSONObject().put("hasKey", k.startsWith("sk-"))
                        .put("keyTail", k.length() > 6 ? k.substring(k.length() - 4) : "")
                        .put("model", sp.getString("model", "claude-sonnet-5-5"))
                        .put("every", sp.getInt("aiEvery", 4)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void saveSettings(String key, String model, int every) {
            android.content.SharedPreferences.Editor ed = getSharedPreferences("alerts", MODE_PRIVATE).edit();
            if (key != null && key.trim().startsWith("sk-")) ed.putString("apiKey", key.trim());
            if (key != null && key.equals("__clear__")) ed.remove("apiKey");
            ed.putString("model", model).putInt("aiEvery", every).apply();
        }

        @JavascriptInterface
        public String aiResult() {
            return getSharedPreferences("alerts", MODE_PRIVATE).getString("ai", "");
        }

        @JavascriptInterface
        public void analyze() {
            new Thread(() -> {
                final String r = Analyzer.run(MainActivity.this).toString();
                runOnUiThread(() -> web.evaluateJavascript("window.__ai(" + JSONObject.quote(r) + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void saveProfile(String json) {
            getSharedPreferences("alerts", MODE_PRIVATE).edit().putString("profile", json).apply();
        }

        @JavascriptInterface
        public String climate(double price, double premium) {
            return GoldJob.climate(MainActivity.this, price, premium).toString();
        }

        @JavascriptInterface
        public void get(final String id, final String url) {
            new Thread(() -> {
                String body = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("https only");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "GoldTracker/1.0");
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    body = sb.toString();
                } catch (Exception e) {
                    body = null;
                }
                final String js = "window.__cb(" + JSONObject.quote(id) + "," +
                        (body == null ? "null" : JSONObject.quote(body)) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
