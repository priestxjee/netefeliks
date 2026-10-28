package tv.netefeliks.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
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
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.SubtitleView;

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
    private volatile String tracks = "{\"audio\":[],\"text\":[]}";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            try {
                JSONObject s = new JSONObject();
                long dur = player.getDuration();
                s.put("pos", Math.max(0, player.getCurrentPosition() - delayMs));
                s.put("dur", dur == C.TIME_UNSET ? 0 : dur);
                s.put("playing", player.isPlaying());
                s.put("buf", player.getPlaybackState() == Player.STATE_BUFFERING);
                s.put("delay", delayMs);
                s.put("bw", DefaultBandwidthMeter.getSingletonInstance(act).getBitrateEstimate());
                s.put("vh", player.getVideoFormat() != null ? player.getVideoFormat().height : 0);
                state = s.toString();
            } catch (Exception ignored) { }
            ui.postDelayed(this, 250);
        }
    };

    VideoEngine(Activity act, SurfaceView surface, SubtitleView subs, Events events) {
        this.act = act;
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
        player = new ExoPlayer.Builder(act, renderers)
                .setMediaSourceFactory(sources)
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
            setDelayMain(c.optLong("delay", 0));
            float size = (float) c.optDouble("subSize", 0.0533);
            subs.setFractionalTextSize(size);
            subs.setCues(new ArrayList<androidx.media3.common.text.Cue>());
            surface.setVisibility(View.VISIBLE);
            subs.setVisibility(View.VISIBLE);
            long start = Math.max(0, c.optLong("start", 0));
            String audioUrl = c.optString("audioUrl", "");
            if (audioUrl.length() > 0) {
                // Bild aus Fassung 1 + Ton aus Fassung 2: beide Streams laden und zusammenführen
                MediaSource pic = sources.createMediaSource(MediaItem.fromUri(c.getString("url")));
                MediaSource snd = sources.createMediaSource(MediaItem.fromUri(audioUrl));
                mixPending = true;
                mixLangs = langs;
                player.setMediaSource(new MergingMediaSource(true, true, pic, snd), start);
            } else {
                mixPending = false;
                player.setMediaItem(MediaItem.fromUri(c.getString("url")), start);
            }
            player.prepare();
            player.play();
            ui.removeCallbacks(poll);
            ui.post(poll);
        } catch (Exception e) {
            events.emit("error", String.valueOf(e.getMessage()));
        }
    }

    void cmd(final String c, final double arg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                if ("play".equals(c)) player.play();
                else if ("pause".equals(c)) player.pause();
                else if ("seekTo".equals(c)) player.seekTo(Math.max(0, (long) arg + delayMs));
                else if ("delay".equals(c)) setDelayMain((long) arg);
                else if ("stop".equals(c)) stopMain();
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
        delayMs = Math.max(-5000, Math.min(5000, ms));
        if (audio != null) audio.offsetUs = delayMs * 1000;
    }

    private void stopMain() {
        ui.removeCallbacks(poll);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        subs.setCues(new ArrayList<androidx.media3.common.text.Cue>());
        surface.setVisibility(View.GONE);
        subs.setVisibility(View.GONE);
        state = "{}";
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
