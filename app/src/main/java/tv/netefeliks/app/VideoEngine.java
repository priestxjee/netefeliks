package tv.netefeliks.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.View;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import android.net.Uri;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import android.view.TextureView;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.CaptionStyleCompat;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.PixelCopy;
import java.io.File;
import java.io.FileOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Echter Android-Player (Media3/ExoPlayer) unter der durchsichtigen App-Oberfläche.
 * Spielt 4K/HEVC, MKV und mehrere Tonspuren über den Hardware-Decoder.
 * Die Oberfläche (Steuerleiste, Menü „Sprache & Audio") bleibt in der App; sie steuert diesen Player über window.NFK.
 */
class VideoEngine {
    interface Events { void emit(String event, String arg); }

    /** Audio-Renderer mit verschiebbarer Uhr: Plus = Ton später, Minus = Ton früher (Bild folgt der verschobenen Uhr). */
    static class SyncAudioRenderer extends MediaCodecAudioRenderer {
        volatile long offsetUs;
        SyncAudioRenderer(Context c, MediaCodecAdapter.Factory f, MediaCodecSelector s, boolean fallback,
                          Handler h, AudioRendererEventListener l, AudioSink sink) {
            super(c, f, s, fallback, h, l, sink);
        }
        @Override public long getPositionUs() { return super.getPositionUs() + offsetUs; }
    }

    private final Activity act;
    private final SurfaceView surface;
    private final AspectRatioFrameLayout frame;
    private float videoRatio = 16f / 9f;
    private int aspectMode = 0;
    private final SubtitleView subs;
    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private SyncAudioRenderer audio;
    private long delayMs;
    private DefaultMediaSourceFactory sources;
    private boolean mixPending;
    private String[] mixLangs = new String[0];
    private volatile String state = "{}";
    // Großer Ton-Versatz (z. B. 2 Minuten, wenn der Ton aus einer anderen Fassung kommt): ein Stream wird vorne gekürzt
    private long clipV = 0, clipA = 0;
    private JSONObject lastCfg;
    private boolean lastMix;
    // Mini-Fenster: eigenes TextureView (lässt sich zuverlässig verschieben und verkleinern)
    private FrameLayout miniBox;
    private AspectRatioFrameLayout miniAr;
    private TextureView miniTex;
    private boolean miniOn;
    // „Weiterschauen“-Bild: letztes Bild beim Beenden speichern
    private String thumbKey;
    private int openGen = 0;
    private volatile String tracks = "{\"audio\":[],\"text\":[]}";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            try {
                JSONObject s = new JSONObject();
                long dur = player.getDuration();
                s.put("pos", Math.max(0, player.getCurrentPosition() - delayMs + clipV));
                s.put("dur", dur == C.TIME_UNSET ? 0 : dur);
                s.put("playing", player.isPlaying());
                s.put("buf", player.getPlaybackState() == Player.STATE_BUFFERING);
                s.put("delay", delayMs + clipV - clipA);
                s.put("bw", DefaultBandwidthMeter.getSingletonInstance(act).getBitrateEstimate());
                s.put("vh", player.getVideoFormat() != null ? player.getVideoFormat().height : 0);
                state = s.toString();
            } catch (Exception ignored) { }
            ui.postDelayed(this, 250);
        }
    };

    VideoEngine(Activity act, AspectRatioFrameLayout frame, SurfaceView surface, SubtitleView subs, Events events) {
        this.act = act;
        this.frame = frame;
        this.surface = surface;
        this.subs = subs;
        this.events = events;
    }

    private void ensure() {
        if (player != null) return;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(act) {
            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode, MediaCodecSelector selector,
                                               boolean enableDecoderFallback, AudioSink audioSink, Handler eventHandler,
                                               AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
                audio = new SyncAudioRenderer(context, getCodecAdapterFactory(), selector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink);
                audio.offsetUs = delayMs * 1000;
                out.add(audio);
            }
        };
        renderers.setEnableDecoderFallback(true);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Netefeliks/3 (Linux; Android) ExoPlayer")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        // DefaultDataSource: Streams vom Server (http) und vorbereitete Tonspuren vom Gerät (file://)
        sources = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(act, http));
        // Kleinerer Puffer als üblich: spart auf dem Fire TV viel Arbeitsspeicher (4K!) – sonst muss Android die Oberfläche beenden
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12000, 30000, 1500, 3000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        player = new ExoPlayer.Builder(act, renderers)
                .setMediaSourceFactory(sources)
                .setLoadControl(load)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                events.emit("error", String.valueOf(error.getMessage()));
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED) events.emit("ended", "");
            }
            @Override public void onCues(CueGroup cueGroup) { subs.setCues(cueGroup.cues); }
            @Override public void onRenderedFirstFrame() { awaitFrame = false; }
            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) { videoRatio = v.width * v.pixelWidthHeightRatio / v.height; applyAspect(); if (miniAr != null) miniAr.setAspectRatio(videoRatio); }
            }
            @Override public void onTracksChanged(Tracks t) { if (mixPending) applyMix(); buildTracks(); events.emit("tracks", ""); }
        });
    }

    // ---------- Aufrufe aus der App (kommen von einem Hintergrund-Thread, deshalb auf den Haupt-Thread legen) ----------

    void open(final String json) {
        ui.post(new Runnable() { @Override public void run() { openMain(json); } });
    }

    private void openMain(String json) {
        try {
            JSONObject c = new JSONObject(json);
            ensure();
            openGen++;
            lastCfg = c;
            miniOff();
            if (frame != null) frame.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            if (c.has("mini") && player != null) { try { miniApply(c.getJSONObject("mini")); } catch (Exception ignored) { } c.remove("mini"); }
            String[] langs = arr(c.optJSONArray("langs"));
            String[] subLangs = arr(c.optJSONArray("subs"));
            boolean subsOn = c.optBoolean("subsOn", false);
            TrackSelectionParameters p = player.getTrackSelectionParameters().buildUpon()
                    .clearOverrides()
                    .setPreferredAudioLanguages(langs)
                    .setPreferredTextLanguages(subLangs)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
                    .build();
            player.setTrackSelectionParameters(p);
            String audioUrl = c.optString("audioUrl", "");
            lastMix = audioUrl.length() > 0;
            long d = c.optLong("delay", 0);
            // ab 3 s Versatz (nur bei Ton aus anderer Fassung): ganze Sekunden durch Kürzen, Rest über die Uhr
            long big = lastMix && Math.abs(d) >= 3000 ? (d / 1000) * 1000 : 0;
            clipV = big > 0 ? big : 0;
            clipA = big < 0 ? -big : 0;
            setDelayMain(d - big);
            aspectMode = c.optInt("aspect", 0);
            applyAspect();
            float size = (float) c.optDouble("subSize", 0.0533);
            subs.setFractionalTextSize(size);
            JSONObject st = c.optJSONObject("subStyle");
            if (st != null) applySubStyle(st);
            subs.setCues(new ArrayList<androidx.media3.common.text.Cue>());
            surface.setVisibility(View.VISIBLE);
            subs.setVisibility(miniOn ? View.GONE : View.VISIBLE);
            long start = Math.max(0, c.optLong("start", 0) - clipV);
            MediaItem main = item(c.getString("url"), c.optString("subUrl", ""), c.optString("subLang", ""));
            if (audioUrl.length() > 0) {
                // Bild aus Fassung 1 + Ton aus Fassung 2: beide Streams laden und zusammenführen
                MediaSource pic = sources.createMediaSource(main);
                MediaSource snd = sources.createMediaSource(MediaItem.fromUri(audioUrl));
                if (clipV > 0) pic = new ClippingMediaSource(pic, clipV * 1000, C.TIME_END_OF_SOURCE);
                if (clipA > 0) snd = new ClippingMediaSource(snd, clipA * 1000, C.TIME_END_OF_SOURCE);
                mixPending = true;
                mixLangs = langs;
                player.setMediaSource(new MergingMediaSource(true, true, pic, snd), start);
            } else {
                mixPending = false;
                player.setMediaItem(main, start);
            }
            player.prepare();
            player.play();
            ui.removeCallbacks(poll);
            ui.post(poll);
        } catch (Exception e) {
            events.emit("error", String.valueOf(e.getMessage()));
        }
    }

    /** Stream + optional nachgeladene Untertitel-Datei (SRT). */
    private static MediaItem item(String url, String subUrl, String subLang) {
        if (subUrl == null || subUrl.length() == 0) return MediaItem.fromUri(url);
        List<MediaItem.SubtitleConfiguration> l = new ArrayList<MediaItem.SubtitleConfiguration>();
        l.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(subLang == null || subLang.length() == 0 ? null : subLang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build());
        return new MediaItem.Builder().setUri(url).setSubtitleConfigurations(l).build();
    }

    /** Bildformat: 0 Original, 1 Füllen (beschneiden), 2 Strecken, 3 = 16:9, 4 = 4:3, 5 = 21:9 */
    private void applyAspect() {
        if (frame == null) return;
        switch (aspectMode) {
            case 1: frame.setAspectRatio(videoRatio); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
            case 2: frame.setAspectRatio(videoRatio); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
            case 3: frame.setAspectRatio(16f / 9f); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case 4: frame.setAspectRatio(4f / 3f); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            case 5: frame.setAspectRatio(21f / 9f); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
            default: frame.setAspectRatio(videoRatio); frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
    }

    /** Mini-Fenster (Live-TV läuft weiter, während man Sender aussucht).
     *  Lage als Anteil des Bildschirms (fx, fy, fw, fh von 0 bis 1) – unabhängig von der Pixeldichte der Oberfläche. Leer = Vollbild. */
    void mini(final String json) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (frame == null || player == null) return;
                try {
                    if (json == null || json.length() == 0) { miniOff(); return; }
                    JSONObject c = new JSONObject(json);
                    if (c.optBoolean("full")) { if (c.optBoolean("anim")) miniGrow(); else miniOff(); return; }
                    miniApply(c);
                } catch (Exception ignored) { }
            }
        });
    }

    private int[] miniRect;
    private boolean awaitFrame;
    private int swapGen;

    /** Nach dem Wechsel Mini → Vollbild muss ein Bild kommen. Sonst (schwarzes Bild, nur Ton): neu verbinden. */
    private void watchFrame(final int g, final int attempt) {
        if (attempt == 0) awaitFrame = true;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (g != swapGen || miniOn || player == null || lastCfg == null) return;
            // Ansicht erzwingen: großes Bild sichtbar, Mini-Fenster weg
            if (miniBox != null && miniBox.getVisibility() == View.VISIBLE) { miniBox.animate().cancel(); miniBox.setVisibility(View.GONE); }
            if (frame.getVisibility() != View.VISIBLE) frame.setVisibility(View.VISIBLE);
            if (surface.getVisibility() != View.VISIBLE) surface.setVisibility(View.VISIBLE);
            boolean valid = surface.getHolder().getSurface() != null && surface.getHolder().getSurface().isValid();
            events.emit("log", "Vollbild-Prüfung " + attempt + ": Bild " + (awaitFrame ? "fehlt" : "kommt") + ", Fläche " + (valid ? "ok" : "nicht bereit")
                    + ", " + surface.getWidth() + "x" + surface.getHeight() + ", Zustand " + player.getPlaybackState());
            if (player.getVideoFormat() == null) return;                    // nur Ton (z. B. Radio): nichts zu tun
            if (player.getPlaybackState() != Player.STATE_READY || !player.getPlayWhenReady()) {
                if (attempt < 8) watchFrame(g, attempt + 1);
                return;
            }
            if (awaitFrame && attempt < 1) {
                player.clearVideoSurface();
                player.setVideoSurfaceView(surface);
                watchFrame(g, attempt + 1);
                return;
            }
            if (awaitFrame || !valid) { reopenFull("kein Bild nach dem Umschalten"); return; }
            // Bild wird angeblich gezeigt – nachsehen, ob es wirklich nicht nur schwarz ist
            if (Build.VERSION.SDK_INT < 24 || surface.getWidth() <= 0) return;
            try {
                final Bitmap b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
                PixelCopy.request(surface, b, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int r) {
                        int max = 0;
                        if (r == PixelCopy.SUCCESS) for (int y = 2; y < 34; y += 3) for (int x = 2; x < 62; x += 3) {
                            int px = b.getPixel(x, y); int l = Math.max(Math.max((px >> 16) & 255, (px >> 8) & 255), px & 255); if (l > max) max = l; }
                        b.recycle();
                        if (g != swapGen || miniOn) return;
                        events.emit("log", "Vollbild-Bildprobe: " + (r == PixelCopy.SUCCESS ? "Helligkeit " + max : "nicht möglich (" + r + ")"));
                        if (r == PixelCopy.SUCCESS && max < 12) reopenFull("Bild schwarz");
                    }
                }, ui);
            } catch (Exception ignored) { }
        } }, attempt == 0 ? 1500 : 1200);
    }

    private void reopenFull(String why) {
        try {
            long pos = Math.max(0, player.getCurrentPosition() - delayMs + clipV);
            JSONObject c = new JSONObject(lastCfg.toString());
            c.remove("mini");
            c.put("start", pos);
            events.emit("log", "Bild neu verbunden (" + why + ")");
            openMain(c.toString());
        } catch (Exception ignored) { }
    }

    /** Mini-Fenster an diese Stelle legen. Mit "anim" schrumpft das Bild aus dem Vollbild hinein. */
    private void miniApply(JSONObject c) {
        ViewGroup root = (ViewGroup) frame.getParent();
        int[] r = rect(c, root);
        // gleiche Stelle wie schon: nichts tun (die App prüft regelmäßig nach)
        if (miniOn && miniBox != null && miniBox.getVisibility() == View.VISIBLE && miniRect != null && java.util.Arrays.equals(r, miniRect)) return;
        if (miniBox == null) {
            miniBox = new FrameLayout(act);
            miniBox.setBackgroundColor(0xFF000000);
            miniAr = new AspectRatioFrameLayout(act);
            miniAr.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            miniTex = new TextureView(act);
            miniAr.addView(miniTex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            miniBox.addView(miniAr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            root.addView(miniBox, root.indexOfChild(frame) + 1);
        }
        boolean shrink = c.optBoolean("anim") && !miniOn && frame.getVisibility() == View.VISIBLE && r[2] > 0 && r[3] > 0;
        miniAr.setAspectRatio(videoRatio);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, r[2]), Math.max(1, r[3]), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = r[0];
        lp.topMargin = r[1];
        miniBox.animate().cancel();
        miniBox.setLayoutParams(lp);
        miniRect = r;
        miniBox.setVisibility(View.VISIBLE);
        if (!miniOn) { player.setVideoTextureView(miniTex); miniOn = true; }
        frame.setVisibility(View.INVISIBLE);
        subs.setVisibility(View.GONE);
        if (shrink) {
            float W = root.getWidth(), H = root.getHeight();
            miniBox.setPivotX(0); miniBox.setPivotY(0);
            miniBox.setScaleX(W / r[2]); miniBox.setScaleY(H / r[3]);
            miniBox.setTranslationX(-r[0]); miniBox.setTranslationY(-r[1]);
            miniBox.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        } else {
            miniBox.setScaleX(1f); miniBox.setScaleY(1f); miniBox.setTranslationX(0f); miniBox.setTranslationY(0f);
        }
    }

    /** Mini-Fenster wächst zum Vollbild, danach übernimmt die normale Bildfläche. */
    private void miniGrow() {
        if (!miniOn || miniBox == null || miniBox.getVisibility() != View.VISIBLE || miniRect == null) { miniOff(); return; }
        ViewGroup root = (ViewGroup) frame.getParent();
        float W = root.getWidth(), H = root.getHeight();
        int[] r = miniRect;
        if (W <= 0 || H <= 0 || r[2] <= 0 || r[3] <= 0) { miniOff(); return; }
        miniBox.animate().cancel();
        miniBox.setPivotX(0); miniBox.setPivotY(0);
        miniBox.animate().scaleX(W / r[2]).scaleY(H / r[3]).translationX(-r[0]).translationY(-r[1]).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(new Runnable() { @Override public void run() { if (miniOn) miniOff(); } }).start();
        // falls die Animation nicht zu Ende läuft: spätestens nach 0,4 s umschalten
        ui.postDelayed(new Runnable() { @Override public void run() { if (miniOn && miniBox != null && miniBox.getScaleX() != 1f) miniOff(); } }, 400);
    }

    /** Rechteck in Bildschirm-Pixeln: bevorzugt Anteile (fx…), sonst alte Pixelangaben. */
    static int[] rect(JSONObject c, View root) {
        int W = root.getWidth(), H = root.getHeight();
        if (c.has("fw") && W > 0 && H > 0) {
            return new int[]{ (int) Math.round(c.optDouble("fx", 0) * W), (int) Math.round(c.optDouble("fy", 0) * H),
                    (int) Math.round(c.optDouble("fw", 1) * W), (int) Math.round(c.optDouble("fh", 1) * H) };
        }
        return new int[]{ c.optInt("x", 0), c.optInt("y", 0), c.optInt("w", 1), c.optInt("h", 1) };
    }

    private void miniOff() {
        boolean swap = miniOn && player != null;
        miniOn = false;
        miniRect = null;
        // erst die große Bildfläche sichtbar machen, dann das Video dorthin umleiten
        if (frame != null) {
            frame.setVisibility(View.VISIBLE);
            frame.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        if (swap) { player.setVideoSurfaceView(surface); watchFrame(++swapGen, 0); }
        if (miniBox != null) {
            miniBox.animate().cancel();
            miniBox.setVisibility(View.GONE);
            miniBox.setScaleX(1f); miniBox.setScaleY(1f); miniBox.setTranslationX(0f); miniBox.setTranslationY(0f);
        }
        if (player != null && lastCfg != null) subs.setVisibility(View.VISIBLE);
    }

    /** Ton verschieben während der Wiedergabe. Große Sprünge (ab 3 s, Ton aus anderer Fassung) laden den Stream an der gleichen Stelle neu. */
    private void changeDelay(long d) {
        if (player == null) return;
        long big = lastMix && Math.abs(d) >= 3000 ? (d / 1000) * 1000 : 0;
        if (lastCfg != null && (big > 0 ? big : 0) == clipV && (big < 0 ? -big : 0) == clipA) { setDelayMain(d - big); return; }
        if (lastCfg == null) { setDelayMain(d); return; }
        try {
            long pos = Math.max(0, player.getCurrentPosition() - delayMs + clipV);
            JSONObject c = new JSONObject(lastCfg.toString());
            c.put("delay", d);
            c.put("start", pos);
            boolean wasMini = miniOn;
            openMain(c.toString());
            if (wasMini) miniOff();
        } catch (Exception e) { setDelayMain(d); }
    }

    /** Untertitel-Aussehen vom Nutzer: Farbe, Hintergrund, Umrandung/Schatten, Schrift, Größe, Position. */
    void subStyle(final String json) {
        ui.post(new Runnable() {
            @Override public void run() {
                try { applySubStyle(new JSONObject(json)); } catch (Exception e) { }
            }
        });
    }

    private static int col(JSONObject s, String k, int d) {
        try { String v = s.optString(k, ""); return v.length() > 0 ? Color.parseColor(v) : d; } catch (Exception e) { return d; }
    }

    private void applySubStyle(JSONObject s) {
        try {
            int fg = col(s, "fg", Color.WHITE);
            int bg = col(s, "bg", Color.TRANSPARENT);
            int win = col(s, "win", Color.TRANSPARENT);
            int edge = s.optInt("edge", CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW);
            int edgeColor = col(s, "edgeColor", Color.BLACK);
            Typeface tf = Typeface.create(s.optString("font", "sans-serif"), s.optBoolean("bold", false) ? Typeface.BOLD : Typeface.NORMAL);
            subs.setApplyEmbeddedStyles(false);
            subs.setApplyEmbeddedFontSizes(false);
            subs.setStyle(new CaptionStyleCompat(fg, bg, win, edge, edgeColor, tf));
            subs.setFractionalTextSize((float) s.optDouble("size", 0.0533));
            subs.setBottomPaddingFraction((float) s.optDouble("pos", 0.08));
        } catch (Exception e) { }
    }

    void cmd(final String c, final double arg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                if ("play".equals(c)) player.play();
                else if ("pause".equals(c)) player.pause();
                else if ("seekTo".equals(c)) player.seekTo(Math.max(0, (long) arg + delayMs - clipV));
                else if ("delay".equals(c)) changeDelay((long) arg);
                else if ("aspect".equals(c)) { aspectMode = (int) arg; applyAspect(); }
                else if ("stop".equals(c)) stopWithThumb();
            }
        });
    }

    void select(final String type, final int gi, final int ti) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                boolean text = "text".equals(type);
                if (text && gi < 0) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                } else {
                    List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
                    if (gi < 0 || gi >= groups.size()) return;
                    if (text) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                    b.setOverrideForType(new TrackSelectionOverride(groups.get(gi).getMediaTrackGroup(), ti));
                }
                player.setTrackSelectionParameters(b.build());
            }
        });
    }

    /** Bild nur aus Stream 0 (Präfix „0:“), Ton nur aus Stream 1 (Präfix „1:“) – Wunschsprache bevorzugt. */
    private void applyMix() {
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        Tracks.Group vid = null, aud = null;
        int vt = 0, at = 0, best = 999;
        for (Tracks.Group g : groups) {
            String id = g.getMediaTrackGroup().id;
            if (g.getType() == C.TRACK_TYPE_VIDEO && id.startsWith("0:") && vid == null) {
                for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) { vid = g; vt = i; break; }
            }
            if (g.getType() == C.TRACK_TYPE_AUDIO && id.startsWith("1:")) {
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    String l = g.getTrackFormat(i).language;
                    int rank = 500;
                    for (int k = 0; k < mixLangs.length; k++) if (l != null && l.startsWith(mixLangs[k])) { rank = k; break; }
                    if (aud == null || rank < best) { aud = g; at = i; best = rank; }
                }
            }
        }
        if (vid == null || aud == null) return;   // noch nicht alles geladen – beim nächsten Mal
        mixPending = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(vid.getMediaTrackGroup(), vt))
                .setOverrideForType(new TrackSelectionOverride(aud.getMediaTrackGroup(), at))
                .build());
    }

    String state() { return state; }
    String tracks() { return tracks; }

    private void setDelayMain(long ms) {
        delayMs = Math.max(-10000, Math.min(10000, ms));
        if (audio != null) audio.offsetUs = delayMs * 1000;
    }

    void thumbKey(final String k) {
        ui.post(new Runnable() { @Override public void run() {
            thumbKey = k == null || k.length() == 0 ? null : k.replaceAll("[^A-Za-z0-9_-]", "");
        } });
    }

    static File thumbDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "thumbs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Vor dem Beenden das aktuelle Bild abgreifen (für die Karte „Weiterschauen“), dann stoppen. */
    private void stopWithThumb() {
        final String k = thumbKey;
        thumbKey = null;
        if (k == null || player == null || Build.VERSION.SDK_INT < 24 || miniOn || surface.getVisibility() != View.VISIBLE
                || surface.getWidth() <= 0 || player.getVideoFormat() == null) { stopMain(); return; }
        try {
            final int gen = openGen;
            final boolean[] done = { false };
            final Runnable fin = new Runnable() { @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                if (gen == openGen) stopMain();
            } };
            int h = Math.max(120, Math.round(480f / Math.max(0.5f, videoRatio)));
            final Bitmap bmp = Bitmap.createBitmap(480, Math.min(480, h), Bitmap.Config.ARGB_8888);
            player.pause();
            PixelCopy.request(surface, bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int r) {
                    if (r == PixelCopy.SUCCESS) {
                        try {
                            File d = thumbDir(act);
                            File[] old = d.listFiles();
                            if (old != null && old.length > 80) {
                                java.util.Arrays.sort(old, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
                                for (int i = 0; i < old.length - 60; i++) old[i].delete();
                            }
                            FileOutputStream fo = new FileOutputStream(new File(d, k + ".jpg"));
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                            fo.close();
                        } catch (Exception ignored) { }
                    }
                    bmp.recycle();
                    fin.run();
                }
            }, ui);
            ui.postDelayed(fin, 900);
        } catch (Throwable t) { stopMain(); }
    }

    private void stopMain() {
        ui.removeCallbacks(poll);
        miniOff();
        lastCfg = null;
        clipV = clipA = 0;
        if (player != null) { player.stop(); player.clearMediaItems(); }
        subs.setCues(new ArrayList<androidx.media3.common.text.Cue>());
        surface.setVisibility(View.GONE);
        subs.setVisibility(View.GONE);
        state = "{}";
        if (frame != null) frame.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    void pause() { ui.post(new Runnable() { @Override public void run() { if (player != null) player.pause(); } }); }

    void release() {
        ui.removeCallbacks(poll);
        if (player != null) { player.release(); player = null; }
    }

    private void buildTracks() {
        try {
            JSONArray a = new JSONArray(), t = new JSONArray();
            List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
            for (int gi = 0; gi < groups.size(); gi++) {
                Tracks.Group g = groups.get(gi);
                int type = g.getType();
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
                for (int ti = 0; ti < g.length; ti++) {
                    if (!g.isTrackSupported(ti)) continue;
                    Format f = g.getTrackFormat(ti);
                    JSONObject o = new JSONObject();
                    o.put("g", gi);
                    o.put("t", ti);
                    o.put("lang", f.language == null ? "" : f.language);
                    o.put("label", label(f));
                    o.put("sel", g.isTrackSelected(ti));
                    o.put("ch", f.channelCount);
                    String gid = g.getMediaTrackGroup().id;
                    o.put("src", gid.startsWith("1:") ? 1 : 0);
                    (type == C.TRACK_TYPE_AUDIO ? a : t).put(o);
                }
            }
            JSONObject r = new JSONObject();
            r.put("audio", a);
            r.put("text", t);
            tracks = r.toString();
        } catch (Exception ignored) { }
    }

    private static String label(Format f) {
        String l = f.language;
        String name = (l == null || l.length() == 0 || "und".equals(l)) ? "Unbekannt" : new Locale(l).getDisplayLanguage(Locale.GERMAN);
        if (name == null || name.length() == 0) name = l;
        StringBuilder b = new StringBuilder(name);
        if (f.label != null && f.label.length() > 0 && !f.label.equalsIgnoreCase(name)) b.append(" – ").append(f.label);
        if (f.channelCount >= 6) b.append(" · 5.1");
        return b.toString();
    }

    private static String[] arr(JSONArray a) {
        if (a == null) return new String[0];
        String[] r = new String[a.length()];
        for (int i = 0; i < a.length(); i++) r[i] = a.optString(i);
        return r;
    }
}
