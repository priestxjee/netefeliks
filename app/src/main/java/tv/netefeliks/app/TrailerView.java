package tv.netefeliks.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.json.JSONObject;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Trailer-Vorschau mit dem echten Android-Player (die Web-Oberfläche des Fire TV zeigt eingebettete Videos oft nur mit Ton).
 * Das Video liegt genau an der Stelle des Bildes unter der durchsichtigen App-Oberfläche; Verläufe und Text liegen darüber.
 * Viele Kino-Trailer haben schwarze Balken im Video (Breitbild 2,39:1 in 16:9) – die werden erkannt und weggezoomt.
 */
class TrailerView {
    interface Events { void emit(String ev); }

    private static final long[] SAMPLES = {1200, 2500, 4000, 6000, 9000, 13000, 18000, 25000};

    private final Activity act;
    private final FrameLayout root;
    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout box;
    private AspectRatioFrameLayout ar;
    private TextureView tex;
    private ExoPlayer player;
    // YouTube-Trailer: eigene, kleine (nicht durchsichtige) WebView – dort zeigt der Fire TV das Bild zuverlässig
    private WebView yt;
    private int gen;            // jede neue Wiedergabe bekommt eine eigene Nummer (alte Messungen verfallen)
    private boolean fitMode, sampling;
    private float vidAr = 16f / 9f, exoBar;
    private final ArrayList<Float> exoSamples = new ArrayList<>();

    TrailerView(Activity act, FrameLayout root, Events events) {
        this.act = act;
        this.root = root;
        this.events = events;
    }

    void open(final String json) {
        act.runOnUiThread(new Runnable() { @Override public void run() { openMain(json); } });
    }

    void stop() {
        act.runOnUiThread(new Runnable() { @Override public void run() { stopMain(); } });
    }

    private void openMain(String json) {
        try {
            JSONObject c = new JSONObject(json);
            gen++;
            sampling = false;
            if (box == null) {
                box = new FrameLayout(act);
                ar = new AspectRatioFrameLayout(act);
                ar.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                tex = new TextureView(act);
                ar.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                box.addView(ar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                // direkt unter die App-Oberfläche (die WebView ist das oberste Element)
                root.addView(box, Math.max(0, root.getChildCount() - 1));
            }
            int[] r = VideoEngine.rect(c, root);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, r[2]), Math.max(1, r[3]), Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = r[0];
            lp.topMargin = r[1];
            box.setLayoutParams(lp);
            boolean fit = c.optBoolean("fit", false);
            fitMode = fit;
            ar.setResizeMode(fit ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            box.setBackgroundColor(fit ? 0xFF000000 : 0x00000000);
            box.setVisibility(View.VISIBLE);
            exoBar = 0;
            exoSamples.clear();
            tex.setScaleY(1f);
            if (c.has("yt")) { openYt(c, fit, r); return; }
            if (yt != null) { yt.setVisibility(View.GONE); yt.loadUrl("about:blank"); }
            ar.setVisibility(View.VISIBLE);
            if (player == null) {
                player = new ExoPlayer.Builder(act)
                        .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(4000, 12000, 800, 1500)
                                .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
                        .build();
                player.setVideoTextureView(tex);
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.addListener(new Player.Listener() {
                    @Override public void onVideoSizeChanged(VideoSize v) {
                        if (v.width > 0 && v.height > 0) { vidAr = v.width * v.pixelWidthHeightRatio / v.height; applyExoBar(); }
                    }
                    @Override public void onRenderedFirstFrame() {
                        events.emit("first");
                        if (!fitMode && !sampling) { sampling = true; sampleExo(gen, 0); }
                    }
                    @Override public void onPlayerError(PlaybackException e) { events.emit("error:" + e.getErrorCodeName() + " " + e.getMessage()); stopMain(); }
                    @Override public void onPlaybackStateChanged(int st) { if (st == Player.STATE_READY) events.emit("ready"); }
                });
            }
            player.setVolume(c.optBoolean("mute", true) ? 0f : 1f);
            player.setMediaItem(MediaItem.fromUri(c.getString("url")));
            player.prepare();
            player.play();
            events.emit("start " + r[0] + "," + r[1] + " " + r[2] + "x" + r[3] + " von " + root.getWidth() + "x" + root.getHeight());
        } catch (Exception e) {
            events.emit("error:" + e);
        }
    }

    // ---------- Erkennung schwarzer Balken ----------

    private static int lum(int p) { return (((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255)) / 10; }

    private static boolean blackRow(Bitmap b, int y, int x0, int x1) {
        int step = Math.max(1, (x1 - x0) / 40), max = 0, sum = 0, n = 0;
        for (int x = x0; x < x1; x += step) { int l = lum(b.getPixel(x, y)); if (l > max) max = l; sum += l; n++; }
        return n > 0 && max < 40 && sum / n < 20;
    }

    /** Anteil schwarzer Zeilen vom oberen (oder unteren) Rand; -1 wenn das Bild selbst fast ganz dunkel ist (z. B. Studio-Logo). */
    static float edgeBar(Bitmap b, float fx0, float fx1, boolean top) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 8 || h < 8) return -1;
        int x0 = (int) (w * fx0), x1 = Math.max(x0 + 1, (int) (w * fx1));
        boolean content = false;
        int step = Math.max(1, (x1 - x0) / 40);
        for (int y = (int) (h * 0.35f); y < h * 0.65f && !content; y += Math.max(1, h / 30))
            for (int x = x0; x < x1; x += step) if (lum(b.getPixel(x, y)) > 60) { content = true; break; }
        if (!content) return -1;
        int n = 0;
        while (n < h / 2) { int y = top ? n : h - 1 - n; if (!blackRow(b, y, x0, x1)) break; n++; }
        float f = n / (float) h;
        return f > 0.35f ? -1 : f;
    }

    /** Kleinster Wert, wenn mindestens zwei Proben übereinstimmen (dunkle Szenen täuschen sonst größere Balken vor). */
    private static float agreed(ArrayList<Float> s) {
        if (s.size() < 2) return -1;
        ArrayList<Float> c = new ArrayList<>(s);
        Collections.sort(c);
        if (c.get(1) - c.get(0) > 0.025f) return -1;
        float f = c.get(0);
        return f < 0.02f ? 0f : Math.min(f, 0.2f);
    }

    private void sampleExo(final int g, final int i) {
        if (i >= SAMPLES.length) return;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (g != gen || player == null || tex == null || fitMode) return;
            try {
                Bitmap b = tex.getBitmap(192, Math.max(48, Math.round(192 / Math.max(0.5f, vidAr))));
                if (b != null) {
                    float t = edgeBar(b, 0.05f, 0.95f, true), d = edgeBar(b, 0.05f, 0.95f, false);
                    b.recycle();
                    if (t >= 0 && d >= 0) {
                        exoSamples.add(Math.min(t, d));
                        float f = agreed(exoSamples);
                        if (f >= 0 && Math.abs(f - exoBar) > 0.012f) { exoBar = f; applyExoBar(); events.emit("log:Trailer-Balken erkannt: " + Math.round(f * 100) + " %"); }
                    }
                }
            } catch (Exception ignored) { }
            sampleExo(g, i + 1);
        } }, i == 0 ? SAMPLES[0] : SAMPLES[i] - SAMPLES[i - 1]);
    }

    private void applyExoBar() {
        if (ar == null || tex == null) return;
        float k = fitMode ? 1f : 1f - 2f * exoBar;
        ar.setAspectRatio(vidAr / k);   // Fläche ohne Balken füllt das Fenster …
        tex.setScaleY(1f / k);          // … und die Balken liegen außerhalb (werden abgeschnitten)
    }

    private void sampleYt(final int g, final int i) {
        if (i >= SAMPLES.length || Build.VERSION.SDK_INT < 26) return;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (g != gen || yt == null || box == null || box.getVisibility() != View.VISIBLE || fitMode) return;
            captureYt(g);
            sampleYt(g, i + 1);
        } }, i == 0 ? SAMPLES[0] : SAMPLES[i] - SAMPLES[i - 1]);
    }

    @android.annotation.TargetApi(26)
    private void captureYt(final int g) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            int w = box.getWidth(), h = box.getHeight();
            if (w < 40 || h < 40) return;
            int[] loc = new int[2];
            box.getLocationInWindow(loc);
            final Bitmap b = Bitmap.createBitmap(240, Math.max(40, Math.min(300, Math.round(240f * h / w))), Bitmap.Config.ARGB_8888);
            PixelCopy.request(act.getWindow(), new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), b, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int res) {
                    try {
                        // nur der rechte, obere Teil: links und unten liegen Text und Verläufe der App darüber
                        float t = res == PixelCopy.SUCCESS ? edgeBar(b, 0.55f, 0.97f, true) : -1;
                        if (t >= 0 && g == gen && yt != null) yt.evaluateJavascript("window.bar&&bar(" + t + ")", null);
                    } catch (Exception ignored) { }
                    b.recycle();
                }
            }, ui);
        } catch (Exception ignored) { }
    }

    /** Vorschaubild von YouTube laden und daran erkennen, ob das Video Balken hat (Anteil je Seite, 0 = keine). */
    private static float thumbBar(String id) {
        for (String n : new String[]{"maxresdefault", "hq720"}) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/" + n + ".jpg").openConnection();
                con.setConnectTimeout(1500);
                con.setReadTimeout(1500);
                if (con.getResponseCode() != 200) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap b;
                try (InputStream in = con.getInputStream()) { b = BitmapFactory.decodeStream(in, null, o); }
                if (b == null) continue;
                // Platzhalterbild „kein Vorschaubild“ (120×90) ignorieren
                if (b.getWidth() < 200) { b.recycle(); continue; }
                float t = edgeBar(b, 0.05f, 0.95f, true), d = edgeBar(b, 0.05f, 0.95f, false);
                b.recycle();
                if (t < 0 || d < 0) return 0;
                float f = Math.min(t, d);
                return f > 0.03f && f < 0.16f ? f : 0;
            } catch (Exception ignored) {
            } finally { if (con != null) con.disconnect(); }
        }
        return 0;
    }

    private void openYt(final JSONObject c, final boolean fit, final int[] r) throws Exception {
        if (player != null) { player.release(); player = null; }
        ar.setVisibility(View.GONE);
        if (yt == null) {
            yt = new WebView(act);
            yt.setBackgroundColor(0xFF000000);
            yt.setFocusable(false);
            yt.setFocusableInTouchMode(false);
            box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            WebSettings s = yt.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            yt.setWebChromeClient(new WebChromeClient());
            yt.setWebViewClient(new WebViewClient());
            yt.addJavascriptInterface(new Object() {
                @JavascriptInterface public void ev(final String e) { act.runOnUiThread(new Runnable() { @Override public void run() {
                    events.emit(e);
                    if ("first".equals(e) && !fitMode && !sampling) { sampling = true; sampleYt(gen, 0); }
                } }); }
            }, "NT");
            box.addView(yt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        yt.setVisibility(View.VISIBLE);
        yt.loadUrl("about:blank");
        final String id = c.getString("yt").replaceAll("[^A-Za-z0-9_-]", "");
        final boolean mute = c.optBoolean("mute", true);
        final int g = gen;
        if (fit) { loadYt(id, mute, true, 0f); }
        else {
            // zuerst kurz das Vorschaubild prüfen (max. ~1,5 s), damit der Trailer gleich richtig gezoomt startet
            new Thread(new Runnable() { @Override public void run() {
                final float f = thumbBar(id);
                ui.post(new Runnable() { @Override public void run() { if (g == gen && yt != null) loadYt(id, mute, false, f); } });
            } }).start();
        }
        events.emit("start yt " + r[2] + "x" + r[3]);
        keepAppFocus();
    }

    private void loadYt(String id, boolean mute, boolean fit, float bar0) {
        if (bar0 > 0) events.emit("log:Trailer-Balken laut Vorschaubild: " + Math.round(bar0 * 100) + " %");
        // B = Balken je Seite im 16:9-Bild. Ohne Vollbild wird so gezoomt, dass nur echtes Bild das Fenster füllt.
        String js = "var FIT=" + (fit ? "true" : "false") + ",B=" + bar0 + ",T=0,FH=1,E=[],UB=1;"
                + "function fit(){var f=document.querySelector('iframe');if(!f)return;var W=innerWidth,H=innerHeight,w,h,t;"
                + "if(FIT){w=W;h=W*9/16;if(h>H){h=H;w=H*16/9}t=(H-h)/2}"
                + "else{var ca=16/9/(1-2*B);w=Math.max(W,H*ca);h=w*9/16;var ch=w/ca;t=-(h-ch)/2-(ch-H)*0.18}"
                + "T=t;FH=h;f.style.width=w+'px';f.style.height=h+'px';f.style.left=(W-w)/2+'px';f.style.top=t+'px'}"
                // Messung vom Gerät: sichtbarer schwarzer Streifen oben (Anteil der Fensterhöhe)
                + "function bar(v){if(FIT)return;var px=v*innerHeight,c=(px-T)/FH;if(px<3){UB=Math.min(UB,-T/FH)}else{E.push(c)}"
                + "var n=-1;if(E.length>1){var s=E.slice().sort(function(a,b){return a-b});if(s[1]-s[0]<=0.025)n=s[0]}"
                + "if(n>=0)n=Math.min(n,UB);else if(UB<B)n=UB;if(n<0)return;n=n<0.02?0:Math.min(n,0.2);"
                + "if(Math.abs(n-B)>0.012){B=n;fit();NT.ev('log:Trailer-Balken angepasst: '+Math.round(n*100)+' %')}}"
                + "addEventListener('resize',fit);";
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,iframe{position:absolute;border:0}</style></head><body><div id=p></div>"
                + "<script>" + js
                + "function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'" + id + "',host:'https://www.youtube-nocookie.com',playerVars:{autoplay:1,mute:" + (mute ? 1 : 0) + ",controls:0,playsinline:1,rel:0,modestbranding:1,iv_load_policy:3,fs:0,disablekb:1,loop:1,playlist:'" + id + "'},"
                + "events:{onReady:function(e){fit();try{e.target.playVideo()}catch(x){}},onStateChange:function(e){if(e.data===1)NT.ev('first');if(e.data===0){try{e.target.playVideo()}catch(x){}}},onError:function(e){NT.ev('error:youtube '+e.data)}}});setTimeout(fit,50)}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
        yt.loadDataWithBaseURL("https://www.netefeliks.app/", html, "text/html", "utf-8", null);
    }

    /** Die Fernbedienung muss immer bei der App-Oberfläche bleiben (oberstes Element), nie beim Trailer-Fenster. */
    private void keepAppFocus() {
        final View top = root.getChildAt(root.getChildCount() - 1);
        if (top == null || top == box) return;
        if (!top.hasFocus()) top.requestFocus();
        top.postDelayed(new Runnable() { @Override public void run() { if (!top.hasFocus()) top.requestFocus(); } }, 800);
        top.postDelayed(new Runnable() { @Override public void run() { if (!top.hasFocus()) top.requestFocus(); } }, 2500);
    }

    private void stopMain() {
        gen++;
        sampling = false;
        if (yt != null) { yt.loadUrl("about:blank"); yt.setVisibility(View.GONE); }
        if (player != null) { player.release(); player = null; }   // Arbeitsspeicher sofort freigeben
        if (box != null) keepAppFocus();
        if (box != null) box.setVisibility(View.GONE);
    }
}
