package com.mostafa.goldtracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Asks Gemini (generateContent + Google Search grounding) for a gold buy/wait verdict. */
public class Analyzer {
    static final String[] DOMAINS = {
            "kitco.com", "reuters.com", "investing.com", "fxstreet.com", "cnbc.com", "bloomberg.com",
            "wsj.com", "ft.com", "gold.org", "federalreserve.gov", "cmegroup.com", "marketwatch.com",
            "youm7.com", "ahram.org.eg", "almasryalyoum.com", "enterprise.press", "elwatannews.com"
    };

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("alerts", Context.MODE_PRIVATE); }

    static boolean hasKey(Context c) { return prefs(c).getString("apiKey", "").length() > 20; }

    /** Runs the analysis, stores it, returns the result JSON (or {"error":...}). Blocking: call off the UI thread. */
    static synchronized JSONObject run(Context c) {
        SharedPreferences sp = prefs(c);
        JSONObject out = new JSONObject();
        try {
            String key = sp.getString("apiKey", "");
            if (key.length() <= 20) return out.put("error", "مفيش مفتاح API. اكتبه في الإعدادات.");
            String model = sp.getString("model", "gemini-flash-latest");

            double p = GoldJob.fetchPrice();
            JSONObject cl = GoldJob.climate(c, p, -1);
            String profile = sp.getString("profile", "{}");

            JSONObject body = new JSONObject();
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system()))));
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", userMsg(p, cl, profile, sp.getFloat("premium", 1.79f)))))));
            body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));

            // Try the chosen model first, then free-tier friendly fallbacks.
            String[] chain = {model, "gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite"};
            JSONObject resp = null;
            String lastErr = "خطأ من Gemini";
            java.util.HashSet<String> tried = new java.util.HashSet<>();
            for (String m : chain) {
                if (!tried.add(m)) continue;
                resp = post(key, m, body.toString());
                if (!resp.has("error")) { model = m; break; }
                JSONObject err = resp.getJSONObject("error");
                lastErr = err.optString("message", lastErr);
                int code = err.optInt("code", 0);
                if (code != 429 && code != 404 && code != 400) return out.put("error", lastErr);
                resp = null;
            }
            if (resp == null) return out.put("error", "كل الموديلات المجانية رفضت دلوقتي. آخر رسالة: " + lastErr);
            JSONArray cands = resp.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return out.put("error", "Gemini مرجّعش رد. جرّب تاني.");
            JSONObject cand = cands.getJSONObject(0);
            StringBuilder text = new StringBuilder();
            JSONObject cont = cand.optJSONObject("content");
            JSONArray parts = cont == null ? null : cont.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text"));
            Map<String, String> cites = new LinkedHashMap<>();
            JSONObject gm = cand.optJSONObject("groundingMetadata");
            JSONArray chunks = gm == null ? null : gm.optJSONArray("groundingChunks");
            for (int i = 0; chunks != null && i < chunks.length(); i++) {
                JSONObject w = chunks.getJSONObject(i).optJSONObject("web");
                if (w != null && w.optString("uri").startsWith("https://")) cites.put(w.optString("uri"), w.optString("title", "مصدر"));
            }
            JSONObject r = extractJson(text.toString());
            if (r == null) return out.put("error", "الرد مكانش بالشكل المتوقع. جرّب تاني.");
            JSONArray src = r.optJSONArray("sources");
            if (src == null || src.length() == 0) {
                src = new JSONArray();
                for (Map.Entry<String, String> e : cites.entrySet()) src.put(new JSONObject().put("t", e.getValue()).put("u", e.getKey()));
                r.put("sources", src);
            }
            SimpleDateFormat f = new SimpleDateFormat("d/M h:mm a", new Locale("ar", "EG"));
            f.setTimeZone(TimeZone.getTimeZone("Africa/Cairo"));
            r.put("at", f.format(new Date())).put("atMs", System.currentTimeMillis()).put("price", p).put("model", model);
            sp.edit().putString("ai", r.toString()).apply();
            return r;
        } catch (Exception e) {
            try { return out.put("error", "فشل الاتصال: " + e.getMessage()); } catch (Exception x) { return out; }
        }
    }

    static String system() {
        return "You are a disciplined gold-investment advisor for an individual investor in Egypt who buys physical gold "
                + "(21k grams, gold pounds, bars) or Egyptian gold funds. Use Google Search to read TODAY's news and "
                + "analysis from trusted sources (Kitco, Reuters, Investing.com, FXStreet, CNBC, Bloomberg, World Gold Council, "
                + "the Fed, Egyptian outlets for local 21k prices and the dealers' dollar). Search at least 3 times: global gold "
                + "price drivers today, upcoming US data/Fed, and Egypt gold price today. Weigh: Fed/real yields, dollar, "
                + "equity-driven liquidation, central-bank buying, analyst consensus, upcoming events, and the local dealers' "
                + "dollar premium. Combine with the rule-based indicator and the investor profile you are given. Be honest "
                + "about uncertainty; prefer 'wait' before a major event unless the case is strong; never recommend more than "
                + "the remaining budget; respect the profile (short horizon or no emergency fund => smaller or zero). "
                + "Write all human-readable fields in Egyptian Arabic, short and plain. Do not quote more than 15 words from "
                + "any source. End your answer with ONE fenced ```json block and nothing after it, exactly this shape: "
                + "{\"verdict\":\"buy|wait|neutral\",\"amount_egp\":number,\"confidence\":\"low|medium|high\","
                + "\"headline\":\"one sentence\",\"reasons\":[\"...\"],\"risks\":[\"...\"],\"next_event\":\"...\","
                + "\"k21_today\":number_or_null,\"breaking\":true_or_false,"
                + "\"sources\":[{\"t\":\"site - title\",\"u\":\"https://...\"}]}. "
                + "'breaking' is true only if there is news in the last few hours likely to move gold more than 1.5%.";
    }

    static String userMsg(double p, JSONObject cl, String profile, double prem) {
        SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM yyyy HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Africa/Cairo"));
        return "Now (Cairo): " + f.format(new Date()) + "\n"
                + "Spot gold now: " + p + " USD/oz\n"
                + "Rule-based indicator: " + cl.toString() + "\n"
                + "Dealers' dollar premium over bank rate (EGP): " + prem + "\n"
                + "Investor profile (budget/invested in EGP, horizon short<1y mid=1-3y long>3y, risk careful/balanced/bold): " + profile + "\n"
                + "Give today's verdict and how much of the remaining budget to buy now (0 if wait).";
    }

    static JSONObject extractJson(String t) {
        try {
            int s = t.lastIndexOf("```json");
            String j;
            if (s >= 0) {
                int e = t.indexOf("```", s + 7);
                j = t.substring(s + 7, e < 0 ? t.length() : e);
            } else {
                int a = t.indexOf('{'), b = t.lastIndexOf('}');
                if (a < 0 || b <= a) return null;
                j = t.substring(a, b + 1);
            }
            return new JSONObject(j.trim());
        } catch (Exception e) { return null; }
    }

    static JSONObject post(String key, String model, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("x-goog-api-key", key);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream os = c.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return new JSONObject(sb.toString());
    }
}
