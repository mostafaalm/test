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

/** Asks Claude (Messages API + web search) for a gold buy/wait verdict. */
public class Analyzer {
    static final String[] DOMAINS = {
            "kitco.com", "reuters.com", "investing.com", "fxstreet.com", "cnbc.com", "bloomberg.com",
            "wsj.com", "ft.com", "gold.org", "federalreserve.gov", "cmegroup.com", "marketwatch.com",
            "youm7.com", "ahram.org.eg", "almasryalyoum.com", "enterprise.press", "elwatannews.com"
    };

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("alerts", Context.MODE_PRIVATE); }

    static boolean hasKey(Context c) { return prefs(c).getString("apiKey", "").startsWith("sk-"); }

    /** Runs the analysis, stores it, returns the result JSON (or {"error":...}). Blocking: call off the UI thread. */
    static synchronized JSONObject run(Context c) {
        SharedPreferences sp = prefs(c);
        JSONObject out = new JSONObject();
        try {
            String key = sp.getString("apiKey", "");
            if (!key.startsWith("sk-")) return out.put("error", "مفيش مفتاح API. اكتبه في الإعدادات.");
            String model = sp.getString("model", "claude-sonnet-5-5");

            double p = GoldJob.fetchPrice();
            JSONObject cl = GoldJob.climate(c, p, -1);
            String profile = sp.getString("profile", "{}");

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 4000);
            body.put("system", system());
            JSONObject tool = new JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 6);
            JSONArray doms = new JSONArray();
            for (String d : DOMAINS) doms.put(d);
            tool.put("allowed_domains", doms);
            body.put("tools", new JSONArray().put(tool));
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", userMsg(p, cl, profile, sp.getFloat("premium", 1.79f))));
            body.put("messages", msgs);

            JSONObject resp = null;
            for (int i = 0; i < 4; i++) {
                resp = post(key, body.toString());
                if (resp.has("error")) {
                    String m = resp.getJSONObject("error").optString("message", "خطأ من الـAPI");
                    return out.put("error", m);
                }
                if (!"pause_turn".equals(resp.optString("stop_reason"))) break;
                msgs.put(new JSONObject().put("role", "assistant").put("content", resp.getJSONArray("content")));
            }

            StringBuilder text = new StringBuilder();
            Map<String, String> cites = new LinkedHashMap<>();
            JSONArray content = resp.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if (!"text".equals(b.optString("type"))) continue;
                text.append(b.optString("text"));
                JSONArray cs = b.optJSONArray("citations");
                for (int j = 0; cs != null && j < cs.length(); j++) {
                    JSONObject ci = cs.getJSONObject(j);
                    String u = ci.optString("url");
                    if (u.startsWith("https://")) cites.put(u, ci.optString("title", u));
                }
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
                + "(21k grams, gold pounds, bars) or Egyptian gold funds. Use the web_search tool to read TODAY's news and "
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

    static JSONObject post(String key, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("content-type", "application/json");
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
