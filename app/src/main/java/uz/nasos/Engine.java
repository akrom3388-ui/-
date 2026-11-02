package uz.nasos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Engine {
    static final String LIVE = "live";
    static final String ALARM = "alarm";
    static final Object lock = new Object();
    static final String[][] DEF = {
            {"id", ""}, {"secret", ""}, {"device", ""}, {"region", "eu"},
            {"compressor", "7700"}, {"heater", "0"}, {"pump", "100"}, {"fan", "200"},
            {"tariff", "0"}, {"coef", "1"}, {"interval", "1"}, {"standby", "0"}
    };
    static final String[][] NAMES = {
            {"switch", "Nasos"}, {"compressor_state", "Kompressor"}, {"ele_heating_state", "Elektr teni"},
            {"pump_state", "Sirkulyatsiya nasosi"}, {"draught_fan_state", "Ventilyator"}, {"defrost", "Defrost"},
            {"temp_set", "Belgilangan harorat"}, {"mode", "Rejim"}
    };

    static Context app;
    static SharedPreferences sp;
    static boolean loaded = false;
    static volatile int gen = 0;
    static Thread th;

    static JSONObject days = new JSONObject();
    static JSONArray mins = new JSONArray();
    static JSONArray events = new JSONArray();
    static JSONArray sessions = new JSONArray();
    static JSONArray liveBuf = new JSONArray();
    static JSONObject status = new JSONObject();
    static String specs = "{}";
    static int watts = 0;
    static long updatedMs = 0;
    static String error = "";
    static String msg = "";

    // joriy kompressor seansi
    static boolean sessOn = false;
    static long sessStart = 0;
    static double sessKwh = 0, sessTStart = 0, sessMaxStr = 0;

    // minutlik o'rtacha
    static long bucketMin = 0, bN = 0;
    static double bSumW = 0, bSumT = 0;

    static long specTry = 0;
    static volatile String token = null;
    static volatile long tokenExp = 0;

    // ---------- boshlash ----------
    static synchronized void init(Context c) {
        if (loaded) return;
        app = c.getApplicationContext();
        sp = app.getSharedPreferences("mango", Context.MODE_PRIVATE);
        try {
            JSONObject d = new JSONObject(sp.getString("data", "{}"));
            JSONObject x = d.optJSONObject("days");
            if (x != null) days = x;
            JSONArray a = d.optJSONArray("mins");
            if (a != null) mins = a;
            a = d.optJSONArray("events");
            if (a != null) events = a;
            a = d.optJSONArray("sessions");
            if (a != null) sessions = a;
        } catch (Exception ignored) {
        }
        loaded = true;
    }

    static void channels() {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(LIVE, "Jonli ko'rsatkichlar", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALARM, "Ogohlantirishlar", NotificationManager.IMPORTANCE_HIGH));
    }

    static Notification notif(String channel, String title, String text) {
        Intent i = new Intent(app, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(app, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(app, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(pi).setOnlyAlertOnce(true)
                .setOngoing(LIVE.equals(channel)).build();
    }

    static void updateNotif() {
        try {
            String text;
            synchronized (lock) {
                if (!configured()) text = "Sozlamalarni kiriting";
                else if (!error.isEmpty()) text = "Aloqa xatosi";
                else text = watts + " Vt  •  " + status.optString("temp_current", "-") + "° / " + status.optString("temp_set", "-") + "°";
            }
            app.getSystemService(NotificationManager.class).notify(1, notif(LIVE, "Mango Monitor", text));
        } catch (Exception ignored) {
        }
    }

    static void alarm(String text) {
        try {
            app.getSystemService(NotificationManager.class).notify(2, notif(ALARM, "⚠ Nosozlik", text));
        } catch (Exception ignored) {
        }
    }

    // ---------- sozlamalar ----------
    static String cfg(String k) {
        for (String[] d : DEF) if (d[0].equals(k)) return sp.getString(k, d[1]);
        return "";
    }

    static double num(String k, double def) {
        try {
            return Double.parseDouble(cfg(k).replace(",", "."));
        } catch (Exception e) {
            return def;
        }
    }

    static boolean configured() {
        return !cfg("id").isEmpty() && !cfg("secret").isEmpty() && !cfg("device").isEmpty();
    }

    static String configJson() {
        JSONObject o = new JSONObject();
        try {
            for (String[] d : DEF) o.put(d[0], cfg(d[0]));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    static void saveConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = sp.edit();
            for (String[] d : DEF) ed.putString(d[0], o.optString(d[0], d[1]).trim());
            ed.apply();
        } catch (Exception ignored) {
        }
        token = null;
        tokenExp = 0;
        synchronized (lock) {
            error = "";
            specs = "{}";
        }
        start(true);
    }

    static String baseUrl() {
        switch (cfg("region")) {
            case "us": return "https://openapi.tuyaus.com";
            case "cn": return "https://openapi.tuyacn.com";
            case "in": return "https://openapi.tuyain.com";
            default: return "https://openapi.tuyaeu.com";
        }
    }

    static String fmt(String p) {
        return new SimpleDateFormat(p, Locale.US).format(new Date());
    }

    static String nice(String code) {
        for (String[] n : NAMES) if (n[0].equals(code)) return n[1];
        return code;
    }

    // ---------- so'rov halqasi ----------
    static void start(boolean force) {
        if (!loaded) return;
        if (!force && th != null && th.isAlive()) return;
        final int g = ++gen;
        th = new Thread(new Runnable() {
            public void run() {
                loop(g);
            }
        }, "poll");
        th.start();
    }

    static void stop() {
        gen++;
        save();
    }

    static void loop(int g) {
        long last = System.currentTimeMillis();
        long lastSave = last, lastNotif = 0;
        int fails = 0;
        while (g == gen) {
            long t0 = System.currentTimeMillis();
            long wait = (long) (Math.max(1, num("interval", 1)) * 1000);
            if (configured()) {
                try {
                    if (specs.length() < 5 && System.currentTimeMillis() - specTry > 300000) {
                        specTry = System.currentTimeMillis();
                        fetchSpecs();
                    }
                    JSONObject s = fetchStatus();
                    fails = 0;
                    long now = System.currentTimeMillis();
                    double dt = Math.min((now - last) / 1000.0, 120);
                    last = now;
                    double w = estimate(s);
                    synchronized (lock) {
                        if (g != gen) return;
                        detectEvents(status, s);
                        addEnergy(w, dt, s, now);
                        status = s;
                        watts = (int) Math.round(w);
                        error = "";
                        updatedMs = now;
                    }
                } catch (Exception e) {
                    fails++;
                    String m = String.valueOf(e.getMessage());
                    synchronized (lock) {
                        error = m.length() > 200 ? m.substring(0, 200) : m;
                    }
                    wait = Math.min(60000, 1000L * (1 << Math.min(fails, 6)));
                    if (m.contains("28841002") || m.toLowerCase(Locale.US).contains("quota")) wait = 60000;
                }
            }
            long now2 = System.currentTimeMillis();
            if (now2 - lastNotif > 10000) {
                lastNotif = now2;
                updateNotif();
            }
            if (now2 - lastSave > 30000) {
                lastSave = now2;
                save();
            }
            long until = t0 + wait;
            while (g == gen && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    static double estimate(JSONObject s) {
        if (!s.optBoolean("switch")) return num("standby", 0);
        double p = 0;
        if (s.optBoolean("compressor_state"))
            p += num("compressor", 7700) * s.optDouble("compressor_strength", 0) / 100.0;
        if (s.optBoolean("ele_heating_state")) p += num("heater", 0);
        if (s.optBoolean("pump_state")) p += num("pump", 100);
        if (s.optBoolean("draught_fan_state")) p += num("fan", 200);
        return p * num("coef", 1);
    }

    static void addEnergy(double w, double dt, JSONObject s, long nowMs) throws Exception {
        String day = fmt("yyyy-MM-dd");
        JSONObject d = days.optJSONObject(day);
        if (d == null) {
            d = new JSONObject();
            days.put(day, d);
        }
        double kwh = w * dt / 3600000.0;
        d.put("k", d.optDouble("k", 0) + kwh);
        boolean comp = s.optBoolean("switch") && s.optBoolean("compressor_state");
        if (comp) d.put("c", d.optDouble("c", 0) + dt / 60.0);

        // seanslar
        double tc = s.optDouble("temp_current", 0);
        if (comp && !sessOn) {
            sessOn = true;
            sessStart = nowMs / 1000;
            sessKwh = 0;
            sessTStart = tc;
            sessMaxStr = 0;
        }
        if (sessOn) {
            sessKwh += kwh;
            sessMaxStr = Math.max(sessMaxStr, s.optDouble("compressor_strength", 0));
            if (!comp) {
                sessions.put(new JSONArray().put(sessStart).put(nowMs / 1000).put(sessKwh).put(sessTStart).put(tc).put(sessMaxStr));
                while (sessions.length() > 300) sessions.remove(0);
                sessOn = false;
            }
        }

        // minutlik o'rtacha
        long mk = nowMs / 60000;
        if (bN > 0 && mk != bucketMin) {
            mins.put(new JSONArray().put(bucketMin * 60).put(Math.round(bSumW / bN)).put(Math.round(bSumT / bN * 10) / 10.0));
            while (mins.length() > 10080) mins.remove(0);
            bSumW = 0;
            bSumT = 0;
            bN = 0;
        }
        bucketMin = mk;
        bSumW += w;
        bSumT += tc;
        bN++;

        liveBuf.put(new JSONArray().put(nowMs / 1000).put(Math.round(w)).put(tc));
        while (liveBuf.length() > 300) liveBuf.remove(0);
    }

    static void detectEvents(JSONObject old, JSONObject neu) {
        if (old.length() == 0) return;
        Iterator<String> it = neu.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!old.has(k)) continue;
            try {
                Object a = old.get(k), b = neu.get(k);
                if (a.equals(b)) continue;
                String text = null;
                if (b instanceof Boolean) text = nice(k) + ((Boolean) b ? " yoqildi" : " o'chdi");
                else if (b instanceof String || k.equals("temp_set")) text = nice(k) + ": " + a + " → " + b;
                else if (k.contains("fault")) {
                    text = "Nosozlik (" + k + "): " + a + " → " + b;
                    if (!"0".equals(String.valueOf(b))) alarm(text);
                }
                if (text != null) events.put(new JSONArray().put(System.currentTimeMillis() / 1000).put(text));
            } catch (Exception ignored) {
            }
        }
        while (events.length() > 300) events.remove(0);
    }

    // ---------- Tuya bulut ----------
    static JSONObject fetchStatus() throws Exception {
        JSONObject r = call("GET", "/v1.0/devices/" + cfg("device") + "/status", null, true);
        if (!r.optBoolean("success")) {
            if (r.optInt("code") == 1010) token = null;
            throw new Exception(r.optString("msg", r.toString()) + " (" + r.optInt("code") + ")");
        }
        JSONArray arr = r.getJSONArray("result");
        JSONObject s = new JSONObject();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.getJSONObject(i);
            s.put(it.getString("code"), it.get("value"));
        }
        return s;
    }

    static void fetchSpecs() {
        try {
            JSONObject r = call("GET", "/v1.0/devices/" + cfg("device") + "/specifications", null, true);
            if (r.optBoolean("success")) {
                synchronized (lock) {
                    specs = r.getJSONObject("result").toString();
                }
            }
        } catch (Exception ignored) {
        }
    }

    static void ensureToken() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExp) return;
        JSONObject r = call("GET", "/v1.0/token?grant_type=1", null, false);
        if (!r.optBoolean("success"))
            throw new Exception("Token: " + r.optString("msg") + " (" + r.optInt("code") + ")");
        JSONObject res = r.getJSONObject("result");
        token = res.getString("access_token");
        tokenExp = System.currentTimeMillis() + Math.max(60, res.optLong("expire_time", 7200) - 120) * 1000;
    }

    static JSONObject call(String method, String path, String body, boolean auth) throws Exception {
        if (auth) ensureToken();
        String id = cfg("id");
        String t = String.valueOf(System.currentTimeMillis());
        byte[] bb = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String sts = method + "\n" + sha256(bb) + "\n\n" + path;
        String sign = hmac(cfg("secret"), id + (auth ? token : "") + t + sts);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("client_id", id);
        c.setRequestProperty("sign", sign);
        c.setRequestProperty("t", t);
        c.setRequestProperty("sign_method", "HMAC-SHA256");
        if (auth) c.setRequestProperty("access_token", token);
        if (body != null) {
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(bb);
            o.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return new JSONObject(sb.toString());
    }

    static String sha256(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String hmac(String key, String msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static void setMsg(String m) {
        synchronized (lock) {
            msg = m;
        }
    }

    static void command(final String code, final String valueJson) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Object v = new JSONTokener(valueJson).nextValue();
                    JSONObject c = new JSONObject().put("code", code).put("value", v);
                    JSONObject body = new JSONObject().put("commands", new JSONArray().put(c));
                    JSONObject r = call("POST", "/v1.0/devices/" + cfg("device") + "/commands", body.toString(), true);
                    setMsg(r.optBoolean("success") ? "Buyruq yuborildi" : "Xato: " + r.optString("msg") + " (" + r.optInt("code") + ")");
                } catch (Exception e) {
                    setMsg("Xato: " + e.getMessage());
                }
            }
        }).start();
    }

    // ---------- saqlash va UI uchun ma'lumot ----------
    static void save() {
        if (!loaded) return;
        synchronized (lock) {
            try {
                JSONObject d = new JSONObject();
                d.put("days", days);
                d.put("mins", mins);
                d.put("events", events);
                d.put("sessions", sessions);
                sp.edit().putString("data", d.toString()).apply();
            } catch (Exception ignored) {
            }
        }
    }

    static void resetData() {
        synchronized (lock) {
            days = new JSONObject();
            mins = new JSONArray();
            events = new JSONArray();
            sessions = new JSONArray();
            liveBuf = new JSONArray();
            sessOn = false;
        }
        save();
    }

    static String live() {
        JSONObject o = new JSONObject();
        synchronized (lock) {
            try {
                String today = fmt("yyyy-MM-dd"), ym = fmt("yyyy-MM");
                double month = 0, total = 0;
                Iterator<String> it = days.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    double v = days.getJSONObject(k).optDouble("k", 0);
                    total += v;
                    if (k.startsWith(ym)) month += v;
                }
                JSONObject td = days.optJSONObject(today);
                o.put("day", td == null ? 0 : td.optDouble("k", 0));
                o.put("comp", td == null ? 0 : td.optDouble("c", 0));
                o.put("month", month);
                o.put("total", total);
                o.put("status", status);
                o.put("watts", watts);
                o.put("updated", updatedMs);
                o.put("error", error);
                o.put("msg", msg);
                msg = "";
                o.put("live", liveBuf);
                o.put("tariff", num("tariff", 0));
                o.put("interval", Math.max(1, num("interval", 1)));
                o.put("configured", configured());
                o.put("sessOn", sessOn);
                o.put("sessStart", sessStart);
                o.put("sessKwh", sessKwh);
                o.put("specs", new JSONObject(specs));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    static String history(int hours) {
        JSONObject o = new JSONObject();
        synchronized (lock) {
            try {
                long from = System.currentTimeMillis() / 1000 - hours * 3600L;
                JSONArray m = new JSONArray();
                for (int i = 0; i < mins.length(); i++) {
                    JSONArray p = mins.getJSONArray(i);
                    if (p.getLong(0) >= from) m.put(p);
                }
                List<String> keys = new ArrayList<String>();
                Iterator<String> it = days.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);
                JSONArray d = new JSONArray();
                for (String k : keys) {
                    JSONObject v = days.getJSONObject(k);
                    d.put(new JSONArray().put(k).put(v.optDouble("k", 0)).put(v.optDouble("c", 0)));
                }
                o.put("mins", m);
                o.put("days", d);
                o.put("events", events);
                o.put("sessions", sessions);
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    static String csv() {
        StringBuilder sb = new StringBuilder("sana,kWh,kompressor_soat,narx\n");
        synchronized (lock) {
            List<String> keys = new ArrayList<String>();
            Iterator<String> it = days.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            double tariff = num("tariff", 0);
            for (String k : keys) {
                JSONObject v = days.optJSONObject(k);
                if (v == null) continue;
                double kwh = v.optDouble("k", 0);
                sb.append(k).append(',').append(String.format(Locale.US, "%.3f", kwh)).append(',')
                        .append(String.format(Locale.US, "%.2f", v.optDouble("c", 0) / 60)).append(',')
                        .append(Math.round(kwh * tariff)).append('\n');
            }
        }
        return sb.toString();
    }
}
