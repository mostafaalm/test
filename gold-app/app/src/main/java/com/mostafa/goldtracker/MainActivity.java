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
        final android.content.SharedPreferences crash = getSharedPreferences("crash", MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                crash.edit().putString("last", sw.toString()).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });

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
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignored) { }
                    return true;
                }
                return false;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        } catch (Throwable e) { saveErr(e); }
        try { GoldJob.schedule(this); } catch (Throwable e) { saveErr(e); }
    }

    void saveErr(Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", sw.toString()).apply();
        } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /** Native HTTPS GET so the page is not limited by CORS. Only https URLs. */
    class Bridge {
        @JavascriptInterface
        public String lastCrash() {
            android.content.SharedPreferences sp = getSharedPreferences("crash", MODE_PRIVATE);
            String s = sp.getString("last", "");
            sp.edit().remove("last").apply();
            return s;
        }

        @JavascriptInterface
        public String settings() {
            try {
                android.content.SharedPreferences sp = getSharedPreferences("alerts", MODE_PRIVATE);
                String k = sp.getString("apiKey", "");
                return new JSONObject().put("hasKey", k.length() > 20)
                        .put("keyTail", k.length() > 6 ? k.substring(k.length() - 4) : "")
                        .put("model", sp.getString("model", "gemini-3.8-flash"))
                        .put("every", sp.getInt("aiEvery", 4)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void saveSettings(String key, String model, int every) {
            android.content.SharedPreferences.Editor ed = getSharedPreferences("alerts", MODE_PRIVATE).edit();
            if (key != null && key.trim().length() > 20 && !key.equals("__clear__")) ed.putString("apiKey", key.trim());
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
                String rr;
                try { rr = Analyzer.run(MainActivity.this).toString(); } catch (Throwable e) { saveErr(e); rr = "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"; }
                final String r = rr;
                runOnUiThread(() -> web.evaluateJavascript("window.__ai(" + JSONObject.quote(r) + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void saveProfile(String json) {
            try { getSharedPreferences("alerts", MODE_PRIVATE).edit().putString("profile", json).apply(); } catch (Throwable e) { saveErr(e); }
        }

        @JavascriptInterface
        public String climate(double price, double premium) {
            try { return GoldJob.climate(MainActivity.this, price, premium).toString(); } catch (Throwable e) { saveErr(e); return "{}"; }
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
