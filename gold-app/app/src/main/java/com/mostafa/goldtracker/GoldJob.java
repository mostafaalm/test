package com.mostafa.goldtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Checks the gold price every ~15 minutes and notifies on sharp moves, plan levels and big US news. */
public class GoldJob extends JobService {
    static final int JOB_ID = 4242;
    static final String CH = "gold_alerts";
    static final double MOVE_PCT = 1.0;          // alert when price moves this % since last alert

    /** High-impact US events, UTC millis (8:30 ET = 13:30Z, 14:00 ET = 19:00Z in winter). */
    static final Object[][] EVENTS = {
            {"PCE التضخم", 1795613400000L},   // 2026-11-25 13:30Z
            {"تقرير الوظائف الأمريكي", 1796391000000L}, // 2026-12-04 13:30Z
            {"قرار الفيدرالي بالفايدة", 1796842800000L}, // 2026-12-09 19:00Z
            {"التضخم CPI", 1796909400000L},   // 2026-12-10 13:30Z
            {"PCE التضخم", 1798032600000L},   // 2026-12-23 13:30Z
    };

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, GoldJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { check(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    static void check(Context c) throws Exception {
        double p = fetchPrice();
        if (p <= 0) return;
        SharedPreferences sp = c.getSharedPreferences("alerts", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        long now = System.currentTimeMillis();

        // 1) sharp move since last alert
        float base = sp.getFloat("base", 0f);
        if (base <= 0) {
            ed.putFloat("base", (float) p);
        } else {
            double pct = (p - base) / base * 100.0;
            if (Math.abs(pct) >= MOVE_PCT) {
                String dir = pct > 0 ? "طلع" : "نزل";
                notify(c, 1, "الدهب " + dir + " " + fmt(Math.abs(pct)) + "% في وقت قصير",
                        "الأونصة دلوقتي " + fmt0(p) + " دولار. حركة بالشكل ده غالبًا وراها خبر، افتح التطبيق وشوف التحليل.");
                ed.putFloat("base", (float) p);
            }
        }

        // 2) market climate change
        JSONObject cl = climate(c, p, -1);
        String lvl = cl.optString("level");
        String last = sp.getString("lastLevel", "");
        if (!lvl.equals(last)) {
            if (!last.isEmpty() && !lvl.equals("neutral")) {
                String body = cl.optString("summary");
                if (lvl.equals("good")) {
                    long amt = suggest(sp, "good");
                    if (amt > 0) body += " المقترح حسب ملفك: حوالي " + fmt0(amt) + " جنيه.";
                }
                notify(c, 3, "المستشار: الجو " + cl.optString("title"), body);
            }
            ed.putString("lastLevel", lvl);
        }

        // 3) big US events: heads-up before, reaction after
        for (int i = 0; i < EVENTS.length; i++) {
            String name = (String) EVENTS[i][0];
            long t = (Long) EVENTS[i][1];
            String pre = "pre" + i, post = "post" + i;
            if (now >= t - 60 * 60000L && now < t && !sp.getBoolean(pre, false)) {
                notify(c, 100 + i, "خلال أقل من ساعة: " + name,
                        "خبر كبير ممكن يحرّك الدهب جامد. الأونصة دلوقتي " + fmt0(p) + " دولار.");
                ed.putBoolean(pre, true);
                ed.putFloat("prep" + i, (float) p);
            }
            if (now >= t + 15 * 60000L && now <= t + 3 * 3600000L && !sp.getBoolean(post, false)) {
                float before = sp.getFloat("prep" + i, 0f);
                String body = "الأونصة دلوقتي " + fmt0(p) + " دولار";
                if (before > 0) {
                    double pct = (p - before) / before * 100.0;
                    body += " (" + (pct >= 0 ? "+" : "") + fmt(pct) + "% من قبل الخبر)";
                }
                notify(c, 200 + i, "رد فعل الدهب على " + name, body + ". افتح التطبيق للتفاصيل.");
                ed.putBoolean(post, true);
            }
        }
        ed.apply();

        // 4) Claude analysis on schedule
        int every = sp.getInt("aiEvery", 4);
        if (every > 0 && Analyzer.hasKey(c)) {
            long lastMs = 0;
            String prevVerdict = "";
            try {
                JSONObject prev = new JSONObject(sp.getString("ai", "{}"));
                lastMs = prev.optLong("atMs", 0);
                prevVerdict = prev.optString("verdict", "");
            } catch (Exception ignored) { }
            if (now - lastMs >= every * 3600000L - 10 * 60000L) {
                JSONObject r = Analyzer.run(c);
                if (!r.has("error")) {
                    String v = r.optString("verdict");
                    String vt = v.equals("buy") ? "اشتري" : v.equals("wait") ? "استنى" : "محايد";
                    String body = r.optString("headline");
                    long amt = r.optLong("amount_egp", 0);
                    if (v.equals("buy") && amt > 0) body += " المقترح: حوالي " + fmt0(amt) + " جنيه.";
                    if (r.optBoolean("breaking")) notify(c, 300, "خبر مهم للدهب", body);
                    else if (!v.equals(prevVerdict)) notify(c, 301, "تحليل Claude: " + vt, body);
                }
            }
        }
    }

    /** Seed points (real closes/lows from news reports) so the indicator works from day one. */
    static final double[][] SEED = {
            {1794603600000.0, 3887.6}, // 2026-11-13 21:00Z
            {1794787200000.0, 3869.3}, // 2026-11-16 week open
            {1795197600000.0, 3502.9}, // 2026-11-20 low
            {1795204800000.0, 3537.9}, // 2026-11-20 close
            {1795467600000.0, 3634.0}, // 2026-11-23 close
            {1795536000000.0, 3568.5}, // 2026-11-24 16:00Z
    };

    static synchronized JSONArray history(SharedPreferences sp) {
        try {
            String h = sp.getString("hist", null);
            if (h != null) return new JSONArray(h);
            JSONArray a = new JSONArray();
            for (double[] d : SEED) a.put(new JSONArray().put((long) d[0]).put(d[1]));
            return a;
        } catch (Exception e) { return new JSONArray(); }
    }

    /** Records price p (max one point per 50 min, 60 days kept) and returns the climate as JSON. premium<0 = use stored. */
    static synchronized JSONObject climate(Context c, double p, double premium) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = c.getSharedPreferences("alerts", Context.MODE_PRIVATE);
            if (premium >= 0) sp.edit().putFloat("premium", (float) premium).apply();
            double prem = sp.getFloat("premium", 1.79f);
            long now = System.currentTimeMillis();
            JSONArray h = history(sp);
            long lastT = h.length() > 0 ? h.getJSONArray(h.length() - 1).getLong(0) : 0;
            if (p > 0 && now - lastT > 50 * 60000L) h.put(new JSONArray().put(now).put(p));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < h.length(); i++) if (now - h.getJSONArray(i).getLong(0) < 60L * 86400000L) kept.put(h.get(i));
            sp.edit().putString("hist", kept.toString()).apply();
            if (p <= 0 && kept.length() > 0) p = kept.getJSONArray(kept.length() - 1).getDouble(1);

            double lo = p, hi = p, p24 = 0, p7 = 0;
            for (int i = 0; i < kept.length(); i++) {
                JSONArray pt = kept.getJSONArray(i);
                long age = now - pt.getLong(0);
                double v = pt.getDouble(1);
                if (age <= 30L * 86400000L) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
                if (age >= 20L * 3600000L) p24 = v;
                if (age >= 6L * 86400000L) p7 = v;
            }
            double ch24 = p24 > 0 ? (p - p24) / p24 * 100 : Double.NaN;
            double ch7 = p7 > 0 ? (p - p7) / p7 * 100 : Double.NaN;
            double pos = hi > lo ? (p - lo) / (hi - lo) : 0.5;
            double offHigh = hi > 0 ? (hi - p) / hi * 100 : 0;

            int score = 0;
            JSONArray r = new JSONArray();
            if (pos <= 0.25) { score += 2; r.put("+|السعر قريب من أقل مستوى في آخر 30 يوم (" + fmt0(lo) + ")"); }
            else if (pos >= 0.75) { score -= 2; r.put("-|السعر قريب من أعلى مستوى في آخر 30 يوم (" + fmt0(hi) + ")"); }
            else r.put("=|السعر في نص المدى بتاع آخر 30 يوم");
            if (offHigh >= 8) { score += 1; r.put("+|نازل " + fmt(offHigh) + "% من أعلى سعر في الشهر، يعني فيه خصم"); }
            if (!Double.isNaN(ch24) && ch24 <= -2) { score -= 1; r.put("-|لسه بيقع بقوة (" + fmt(ch24) + "% في 24 ساعة)، استنى يهدى"); }
            else if (!Double.isNaN(ch24) && !Double.isNaN(ch7) && ch24 > 0 && ch7 < 0) { score += 1; r.put("+|بدأ يرتد بعد نزول الأسبوع"); }
            for (Object[] e : EVENTS) {
                long t = (Long) e[1];
                if (t > now && t - now <= 24 * 3600000L) { score -= 1; r.put("-|خبر كبير خلال 24 ساعة: " + e[0] + "، الأفضل تستنى رد الفعل"); break; }
            }
            if (prem >= 2.5) { score -= 1; r.put("-|دولار الصاغة غالي عن البنك (" + fmt(prem) + " ج)، يعني بتشتري بزيادة"); }
            else if (prem <= 1.2) { score += 1; r.put("+|الفرق بين دولار الصاغة والبنك صغير (" + fmt(prem) + " ج)"); }

            String level = score >= 2 ? "good" : score <= -1 ? "wait" : "neutral";
            String title = level.equals("good") ? "مناسب للشراء" : level.equals("wait") ? "الأحسن تستنى" : "محايد";
            String summary = level.equals("good") ? "المؤشرات في صالح الشراء دلوقتي. الأونصة " + fmt0(p) + " دولار."
                    : level.equals("wait") ? "فيه عوامل بتقول استنى شوية. الأونصة " + fmt0(p) + " دولار."
                    : "مفيش إشارة قوية في أي اتجاه. الأونصة " + fmt0(p) + " دولار.";
            o.put("level", level).put("title", title).put("summary", summary).put("score", score)
                    .put("reasons", r).put("low", lo).put("high", hi).put("price", p)
                    .put("ch24", Double.isNaN(ch24) ? JSONObject.NULL : ch24)
                    .put("ch7", Double.isNaN(ch7) ? JSONObject.NULL : ch7);
        } catch (Exception ignored) { }
        return o;
    }

    /** Same rule as the page: share of remaining budget by climate, risk and horizon. */
    static long suggest(SharedPreferences sp, String level) {
        try {
            JSONObject pr = new JSONObject(sp.getString("profile", "{}"));
            if (!pr.optBoolean("emergency", true)) return 0;
            double rem = pr.optDouble("budget", 0) - pr.optDouble("invested", 0);
            if (rem <= 0) return 0;
            double pct = level.equals("good") ? 0.4 : level.equals("neutral") ? 0.2 : 0;
            String risk = pr.optString("risk", "balanced"), hz = pr.optString("horizon", "mid");
            double m = risk.equals("bold") ? 1.25 : risk.equals("careful") ? 0.6 : 1;
            m *= hz.equals("short") ? 0.5 : hz.equals("long") ? 1.2 : 1;
            double a = Math.min(rem, rem * pct * m);
            if (rem < 2000 && level.equals("good")) a = rem;
            return Math.round(a / 500.0) * 500L;
        } catch (Exception e) { return 0; }
    }

    static double fetchPrice() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.gold-api.com/price/XAU").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "GoldTracker/1.2");
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString()).optDouble("price", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static void notify(Context c, int id, String title, String body) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "تنبيهات الدهب", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }
    static String fmt0(double v) { return String.format(Locale.US, "%,.0f", v); }
}
