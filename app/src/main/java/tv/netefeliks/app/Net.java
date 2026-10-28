package tv.netefeliks.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.nio.charset.Charset;

/**
 * Netzwerk-Arbeit außerhalb der Oberfläche, damit die App flüssig bleibt:
 *  - grab(): lädt eine Webseite im Hintergrund und gibt nur die gesuchten Stellen zurück (Bewertungen, Trailer, Top 10)
 *  - Bilder: Poster werden einmal geladen, auf Bildschirmgröße verkleinert und auf dem Gerät zwischengespeichert
 *  - speed(): misst das Internet-Tempo zum IPTV-Server (für die automatische Qualitätswahl)
 */
class Net {
    interface Events { void emit(String fn, String id, String json); }

    static final String IMG_HOST = "nfk.img";
    private static final long IMG_MAX = 90L * 1024 * 1024;   // höchstens 90 MB Bild-Zwischenspeicher
    private final Context ctx;
    private final String ua;
    private final Events events;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ExecutorService one = Executors.newSingleThreadExecutor();
    private int writes = 0;

    Net(Context ctx, String ua, Events events) {
        this.ctx = ctx;
        this.ua = ua;
        this.events = events;
    }

    // ---------- Webseite holen und auswerten ----------
    void grab(final String id, final String url, final String opts) {
        pool.execute(new Runnable() {
            @Override public void run() {
                String out;
                try { out = doGrab(url, opts == null || opts.length() == 0 ? new JSONObject() : new JSONObject(opts)).toString(); }
                catch (Throwable t) {
                    try { out = new JSONObject().put("err", String.valueOf(t.getMessage())).toString(); }
                    catch (Exception e) { out = "{\"err\":\"fehler\"}"; }
                }
                events.emit("nfkG", id, out);
            }
        });
    }

    private HttpURLConnection open(String url, JSONObject headers, int timeout) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = null;
        for (int i = 0; i < 6; i++) {
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 5000);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
            if (headers != null) {
                Iterator<String> k = headers.keys();
                while (k.hasNext()) { String n = k.next(); c.setRequestProperty(n, headers.optString(n)); }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(u, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            return c;
        }
        return c;
    }

    private JSONObject doGrab(String url, JSONObject o) throws Exception {
        int max = o.optInt("max", 4 * 1024 * 1024);
        int head = o.optInt("head", 0);
        HttpURLConnection c = open(url, o.optJSONObject("h"), 12000);
        JSONObject r = new JSONObject();
        int code = c.getResponseCode();
        r.put("status", code);
        if (code >= 400) { c.disconnect(); r.put("err", "HTTP " + code); return r; }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] b = new byte[32 * 1024];
        int n, lines = 0;
        outer:
        while ((n = in.read(b)) > 0) {
            if (head > 0) {
                for (int i = 0; i < n; i++) {
                    if (b[i] == '\n' && ++lines >= head) { buf.write(b, 0, i); break outer; }
                }
            }
            buf.write(b, 0, n);
            if (buf.size() >= max) break;
        }
        in.close();
        c.disconnect();
        String body = buf.toString("UTF-8");
        if (head > 0 || o.optBoolean("body", false)) r.put("body", body);
        JSONArray rules = o.optJSONArray("rules");
        if (rules != null) {
            JSONArray res = new JSONArray();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                Matcher m = Pattern.compile(rule.getString("re")).matcher(body);
                int lim = rule.optInt("max", 1);
                JSONArray g = rule.optJSONArray("g");
                int cut = rule.optInt("cut", 200000);
                JSONArray hits = new JSONArray();
                while (hits.length() < lim && m.find()) {
                    JSONArray h = new JSONArray();
                    if (g == null) h.put(m.group());
                    else for (int k = 0; k < g.length(); k++) {
                        String s = m.group(g.getInt(k));
                        if (s != null && s.length() > cut) s = s.substring(0, cut);
                        h.put(s == null ? "" : s);
                    }
                    hits.put(h);
                }
                res.put(hits);
            }
            r.put("r", res);
        }
        return r;
    }

    // ---------- Untertitel laden (gz oder Text), in UTF-8 umwandeln und als .srt speichern ----------
    void sub(final String id, final String url, final String enc) {
        pool.execute(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    HttpURLConnection c = open(url, null, 12000);
                    if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream raw = new ByteArrayOutputStream(64 * 1024);
                    byte[] b = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) { raw.write(b, 0, n); if (raw.size() > 8 * 1024 * 1024) break; }
                    in.close();
                    c.disconnect();
                    byte[] data = raw.toByteArray();
                    if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
                        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
                        ByteArrayOutputStream un = new ByteArrayOutputStream(data.length * 3);
                        while ((n = gz.read(b)) > 0) un.write(b, 0, n);
                        gz.close();
                        data = un.toByteArray();
                    }
                    Charset cs = Charset.forName("UTF-8");
                    boolean utf8bom = data.length > 2 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF;
                    if (!utf8bom && enc != null && enc.length() > 0 && !enc.toUpperCase().contains("UTF")) {
                        String e = enc.toUpperCase().replace("CP", "windows-");
                        try { cs = Charset.forName(e); } catch (Exception x) { try { cs = Charset.forName(enc); } catch (Exception y) { /* UTF-8 */ } }
                    }
                    String text = new String(data, cs);
                    if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
                    File d = new File(ctx.getCacheDir(), "subs");
                    if (!d.exists()) d.mkdirs();
                    File[] old = d.listFiles();
                    if (old != null && old.length > 30) for (File f : old) f.delete();
                    File f = new File(d, Integer.toHexString(url.hashCode()) + ".srt");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(text.getBytes("UTF-8"));
                    fo.close();
                    out = new JSONObject().put("path", f.getAbsolutePath()).toString();
                } catch (Throwable t) {
                    out = "{\"err\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}";
                }
                events.emit("nfkSub", id, out);
            }
        });
    }

    // ---------- Internet-Tempo zum Server messen ----------
    void speed(final String id, final String url, final int seconds) {
        one.execute(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    HttpURLConnection c = open(url, null, 10000);
                    InputStream in = c.getInputStream();
                    byte[] b = new byte[64 * 1024];
                    long start = System.nanoTime(), first = 0, bytes = 0, end = start + seconds * 1000000000L;
                    int n;
                    while ((n = in.read(b)) > 0) {
                        if (first == 0) first = System.nanoTime();
                        bytes += n;
                        if (System.nanoTime() > end || bytes > 60L * 1024 * 1024) break;
                    }
                    long t = System.nanoTime() - (first == 0 ? start : first);
                    try { in.close(); } catch (Exception ignored) { }
                    c.disconnect();
                    double mbps = t > 0 ? bytes * 8.0 / (t / 1e9) / 1e6 : 0;
                    out = new JSONObject().put("mbps", Math.round(mbps * 10) / 10.0).put("bytes", bytes).toString();
                } catch (Throwable e) {
                    out = "{\"err\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
                }
                events.emit("nfkS", id, out);
            }
        });
    }

    // ---------- Bilder verkleinern und zwischenspeichern ----------
    private File imgDir() {
        File d = new File(ctx.getCacheDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Antwort für https://nfk.img/?w=300&u=<Bild-Adresse> */
    WebResourceResponse image(String full) {
        try {
            String q = full.substring(full.indexOf('?') + 1);
            Map<String, String> p = new HashMap<String, String>();
            for (String kv : q.split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) p.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            String src = p.get("u");
            int w = 300;
            try { w = Integer.parseInt(p.get("w")); } catch (Exception ignored) { }
            w = Math.max(64, Math.min(1920, w));
            if (src == null || !src.startsWith("http")) return notFound();
            File f = new File(imgDir(), Integer.toHexString((src + "|" + w).hashCode()) + "_" + Integer.toHexString(src.length() * 31 + w) + ".jpg");
            if (f.exists() && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis());
                return ok(new FileInputStream(f));
            }
            HttpURLConnection c = open(src, null, 8000);
            if (c.getResponseCode() >= 400) { c.disconnect(); return notFound(); }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream(128 * 1024);
            byte[] b = new byte[32 * 1024];
            int n;
            while ((n = in.read(b)) > 0) { raw.write(b, 0, n); if (raw.size() > 15 * 1024 * 1024) break; }
            in.close();
            c.disconnect();
            byte[] data = raw.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return notFound();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bm == null) return notFound();
            if (bm.getWidth() > w * 1.15) {
                Bitmap s = Bitmap.createScaledBitmap(bm, w, Math.max(1, bm.getHeight() * w / bm.getWidth()), true);
                if (s != bm) bm.recycle();
                bm = s;
            }
            ByteArrayOutputStream jpg = new ByteArrayOutputStream(64 * 1024);
            bm.compress(Bitmap.CompressFormat.JPEG, 82, jpg);
            bm.recycle();
            byte[] out = jpg.toByteArray();
            try {
                File tmp = new File(f.getPath() + ".t");
                FileOutputStream fo = new FileOutputStream(tmp);
                fo.write(out);
                fo.close();
                tmp.renameTo(f);
                if (++writes % 60 == 0) trim();
            } catch (Exception ignored) { }
            return ok(new ByteArrayInputStream(out));
        } catch (Throwable t) {
            return notFound();
        }
    }

    private static WebResourceResponse ok(InputStream in) {
        WebResourceResponse r = new WebResourceResponse("image/jpeg", null, in);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "max-age=604800");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    /** Älteste Bilder löschen, wenn der Zwischenspeicher zu groß wird. */
    void trim() {
        File[] fs = imgDir().listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= IMG_MAX) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (File f : fs) {
            if (total <= IMG_MAX * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    long imgSize() {
        File[] fs = imgDir().listFiles();
        long t = 0;
        if (fs != null) for (File f : fs) t += f.length();
        return t;
    }

    void clearImages() {
        File[] fs = imgDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
