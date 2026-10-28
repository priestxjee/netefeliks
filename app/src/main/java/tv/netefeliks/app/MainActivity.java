package tv.netefeliks.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.media3.ui.SubtitleView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Netefeliks für Fire TV: Vollbild-WebView mit der App aus den Assets.
 * Updates: Neue App-Oberflächen (index.html) werden direkt in der App gespeichert und ersetzen die eingebaute Version.
 * Neue APKs werden heruntergeladen und über den System-Installer installiert.
 */
public class MainActivity extends Activity {
    /** Bei Änderungen an diesem Java-Code um 1 erhöhen und native-version.txt anpassen. */
    static final int NATIVE_VERSION = 6;
    static final int REQ_PLAY = 42;
    static final String APP_URL = "file:///android_asset/index.html";
    static final String ACTION_INSTALL = "tv.netefeliks.app.INSTALL_STATUS";

    private WebView web;
    private VideoEngine video;
    private AudioPrep prep;
    private String ua = "";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("netefeliks", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        web = new WebView(this);
        // Durchsichtig, damit das Video des echten Players darunter sichtbar ist (die App malt ihren Hintergrund selbst)
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        // Erlaubt der App, deinen IPTV-Server direkt abzufragen (im normalen Browser gesperrt)
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(s.getUserAgentString() + " Netefeliks/" + installedVersion());
        ua = s.getUserAgentString();
        prep = new AudioPrep(this, ua, new AudioPrep.Events() {
            @Override public void emit(String ev, String key, String arg) {
                js("window.nfkA&&nfkA(" + org.json.JSONObject.quote(ev) + "," + org.json.JSONObject.quote(key) + "," + org.json.JSONObject.quote(arg == null ? "" : arg) + ")");
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // Liefert die heruntergeladene neuere Oberfläche unter derselben Adresse aus,
            // damit Profile, Verlauf und Zugangsdaten (localStorage) erhalten bleiben.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (req.isForMainFrame() && APP_URL.equals(req.getUrl().toString())) {
                    File f = webFile();
                    if (f.exists() && prefs.getInt("webVersion", 0) > installedVersion()) {
                        try {
                            return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f));
                        } catch (Exception e) { /* eingebaute Version verwenden */ }
                    }
                }
                // YouTube-Trailer: Die eingebettete Wiedergabe braucht eine Herkunftsangabe (Referer),
                // die eine lokal geladene App nicht mitschickt – deshalb holt die App diese eine Seite selbst.
                String u = req.getUrl().toString();
                if (!req.isForMainFrame() && (u.startsWith("https://www.youtube-nocookie.com/embed/") || u.startsWith("https://www.youtube.com/embed/"))) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setRequestProperty("Referer", "https://netefeliks.app/");
                        c.setRequestProperty("User-Agent", ua);
                        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(15000);
                        if (c.getResponseCode() == 200) return new WebResourceResponse("text/html", "utf-8", c.getInputStream());
                    } catch (Exception e) { /* normal laden */ }
                }
                return null;
            }
        });
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.addJavascriptInterface(new Bridge(), "NFK");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(13, 11, 11));
        SurfaceView surface = new SurfaceView(this);
        surface.setVisibility(View.GONE);
        SubtitleView subs = new SubtitleView(this);
        subs.setVisibility(View.GONE);
        ViewGroup.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(surface, full);
        root.addView(subs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        video = new VideoEngine(this, surface, subs, new VideoEngine.Events() {
            @Override public void emit(String ev, String arg) {
                js("window.nfkV&&nfkV(" + org.json.JSONObject.quote(ev) + "," + org.json.JSONObject.quote(arg == null ? "" : arg) + ")");
            }
        });
        web.requestFocus();
        web.loadUrl(APP_URL);
    }

    File webFile() { return new File(getFilesDir(), "web-index.html"); }

    int installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override public void run() { web.evaluateJavascript(code, null); }
        });
    }

    /** Schnittstelle für die App-Oberfläche (window.NFK). */
    class Bridge {
        @JavascriptInterface public int nativeVersion() { return NATIVE_VERSION; }

        @JavascriptInterface public int webVersion() {
            return Math.max(prefs.getInt("webVersion", 0), installedVersion());
        }

        /** Speichert eine neue Oberfläche und lädt sie sofort. */
        @JavascriptInterface public boolean saveWeb(String html, int version) {
            try {
                File tmp = new File(getFilesDir(), "web-index.tmp");
                OutputStream out = new FileOutputStream(tmp);
                out.write(html.getBytes("UTF-8"));
                out.close();
                if (!tmp.renameTo(webFile())) return false;
                prefs.edit().putInt("webVersion", version).apply();
                runOnUiThread(new Runnable() {
                    @Override public void run() { web.clearCache(false); web.loadUrl(APP_URL); }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // ---------- Eingebetteter Player (unter der App-Oberfläche) ----------
        @JavascriptInterface public void vOpen(String cfg) { video.open(cfg); }
        @JavascriptInterface public void vCmd(String c, double arg) { video.cmd(c, arg); }
        @JavascriptInterface public String vState() { return video.state(); }
        @JavascriptInterface public String vTracks() { return video.tracks(); }
        @JavascriptInterface public void vSelect(String type, int g, int t) { video.select(type, g, t); }

        // ---------- Ton vorbereiten (für Abos mit nur einer Verbindung) ----------
        @JavascriptInterface public void aPrep(String key, String url, String langs) { prep.start(key, url, langs); }
        @JavascriptInterface public void aCancel() { prep.cancel(); }
        @JavascriptInterface public boolean aBusy() { return prep.busy(); }
        @JavascriptInterface public String aList() { return prep.list(); }
        @JavascriptInterface public void aDelete(String path) { prep.delete(path); }
        @JavascriptInterface public void aDeleteAll() { prep.deleteAll(); }
        @JavascriptInterface public double aFree() { return (double) prep.free(); }
        @JavascriptInterface public void aKeep(int n) { prep.setKeep(n); }

        /** Startet den nativen Vollbild-Player (ältere Variante). */
        @JavascriptInterface public void play(final String cfg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    startActivityForResult(new Intent(MainActivity.this, PlayerActivity.class).putExtra("cfg", cfg), REQ_PLAY);
                }
            });
        }

        /** Lädt eine neue APK und öffnet den Installations-Dialog. */
        @JavascriptInterface public void installApk(final String url) {
            new Thread(new Runnable() {
                @Override public void run() { downloadAndInstall(url); }
            }).start();
        }
    }

    void downloadAndInstall(String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            js("window.nfkUpd&&nfkUpd('permission')");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        HttpURLConnection con = null;
        PackageInstaller.Session session = null;
        try {
            // GitHub leitet auf einen anderen Server weiter – Weiterleitungen selbst folgen
            URL u = new URL(url);
            for (int i = 0; i < 6; i++) {
                con = (HttpURLConnection) u.openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(20000);
                con.setReadTimeout(30000);
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    u = new URL(u, con.getHeaderField("Location"));
                    con.disconnect();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                break;
            }
            long len = con.getContentLength();
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (len > 0) params.setSize(len);
            int id = pi.createSession(params);
            session = pi.openSession(id);
            InputStream in = con.getInputStream();
            OutputStream out = session.openWrite("netefeliks.apk", 0, len > 0 ? len : -1);
            byte[] buf = new byte[65536];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (len > 0) {
                    int p = (int) (done * 100 / len);
                    if (p != last) { last = p; js("window.nfkUpd&&nfkUpd('progress'," + p + ")"); }
                }
            }
            session.fsync(out);
            out.close();
            in.close();
            Intent status = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            PendingIntent pend = PendingIntent.getActivity(this, 7, status, flags);
            session.commit(pend.getIntentSender());
            session.close();
            js("window.nfkUpd&&nfkUpd('confirm')");
        } catch (Exception e) {
            if (session != null) session.abandon();
            js("window.nfkUpd&&nfkUpd('error'," + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + ")");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PLAY) return;
        String r = data != null ? data.getStringExtra("res") : null;
        if (r == null) r = "{}";
        js("window.nfkPlayed&&nfkPlayed(" + org.json.JSONObject.quote(r) + ")");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !ACTION_INSTALL.equals(intent.getAction())) return;
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            js("window.nfkUpd&&nfkUpd('error'," + org.json.JSONObject.quote(msg == null ? "Installation abgebrochen" : msg) + ")");
        }
    }

    @Override
    public void onBackPressed() {
        // Zurück-Taste der Fernbedienung an die App geben; nur auf der Startseite wird die App verlassen
        web.evaluateJavascript("(window.nfkBack && window.nfkBack()) ? '1' : '0'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (!"\"1\"".equals(v)) finish();
            }
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); if (video != null) video.pause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { if (video != null) video.release(); web.destroy(); super.onDestroy(); }
}
