package tv.netefeliks.app;

import android.app.Activity;
import android.view.Gravity;
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

/**
 * Trailer-Vorschau mit dem echten Android-Player (die Web-Oberfläche des Fire TV zeigt eingebettete Videos oft nur mit Ton).
 * Das Video liegt genau an der Stelle des Bildes unter der durchsichtigen App-Oberfläche; Verläufe und Text liegen darüber.
 */
class TrailerView {
    interface Events { void emit(String ev); }

    private final Activity act;
    private final FrameLayout root;
    private final Events events;
    private FrameLayout box;
    private AspectRatioFrameLayout ar;
    private TextureView tex;
    private ExoPlayer player;
    // YouTube-Trailer: eigene, kleine (nicht durchsichtige) WebView – dort zeigt der Fire TV das Bild zuverlässig
    private WebView yt;

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
            ar.setResizeMode(fit ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            box.setBackgroundColor(fit ? 0xFF000000 : 0x00000000);
            box.setVisibility(View.VISIBLE);
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
                        if (v.width > 0 && v.height > 0) ar.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height);
                    }
                    @Override public void onRenderedFirstFrame() { events.emit("first"); }
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

    private void openYt(JSONObject c, boolean fit, int[] r) throws Exception {
        if (player != null) { player.release(); player = null; }
        ar.setVisibility(View.GONE);
        if (yt == null) {
            yt = new WebView(act);
            yt.setBackgroundColor(0xFF000000);
            yt.setFocusable(false);
            yt.setFocusableInTouchMode(false);
            WebSettings s = yt.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            yt.setWebChromeClient(new WebChromeClient());
            yt.setWebViewClient(new WebViewClient());
            yt.addJavascriptInterface(new Object() {
                @JavascriptInterface public void ev(final String e) { act.runOnUiThread(new Runnable() { @Override public void run() { events.emit(e); } }); }
            }, "NT");
            box.addView(yt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        yt.setVisibility(View.VISIBLE);
        String id = c.getString("yt").replaceAll("[^A-Za-z0-9_-]", "");
        boolean mute = c.optBoolean("mute", true);
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,iframe{position:absolute;border:0}</style></head><body><div id=p></div>"
                + "<script>var FIT=" + (fit ? "true" : "false") + ";function fit(){var f=document.querySelector('iframe');if(!f)return;var W=innerWidth,H=innerHeight,w=W,h=W*9/16;if(FIT?h>H:h<H){h=H;w=H*16/9}f.style.width=w+'px';f.style.height=h+'px';f.style.left=(W-w)/2+'px';f.style.top=(H-h)/2+'px'}addEventListener('resize',fit);"
                + "function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'" + id + "',host:'https://www.youtube-nocookie.com',playerVars:{autoplay:1,mute:" + (mute ? 1 : 0) + ",controls:0,playsinline:1,rel:0,modestbranding:1,iv_load_policy:3,fs:0,disablekb:1,loop:1,playlist:'" + id + "'},"
                + "events:{onReady:function(e){fit();try{e.target.playVideo()}catch(x){}},onStateChange:function(e){if(e.data===1)NT.ev('first');if(e.data===0){try{e.target.playVideo()}catch(x){}}},onError:function(e){NT.ev('error:youtube '+e.data)}}});setTimeout(fit,50)}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
        yt.loadDataWithBaseURL("https://www.netefeliks.app/", html, "text/html", "utf-8", null);
        events.emit("start yt " + r[2] + "x" + r[3]);
    }

    private void stopMain() {
        if (yt != null) { yt.loadUrl("about:blank"); yt.setVisibility(View.GONE); }
        if (player != null) { player.release(); player = null; }   // Arbeitsspeicher sofort freigeben
        if (box != null) box.setVisibility(View.GONE);
    }
}
