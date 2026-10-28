package tv.netefeliks.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nativer Player (Media3/ExoPlayer): spielt 4K/HEVC, MKV und mehrere Tonspuren über den Hardware-Decoder des Fire TV.
 * Menü-Taste (☰): Sprache & Qualität wählen. Live: hoch/runter = Senderwechsel. Zahnrad im Player: Tonspur & Untertitel.
 */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private JSONArray items;      // Versionen des aktuellen Titels/Senders (sortiert: Wunschsprache, dann beste Qualität)
    private int idx;              // aktuelle Version
    private JSONArray chans;      // Live: Senderliste zum Umschalten
    private int ci;
    private boolean live;
    private String id;
    private boolean triedAlt;
    private boolean done;
    private int seekMs = 10000;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        String[] langs = new String[0];
        String[] subLangs = new String[0];
        boolean subsOn = false;
        long start = 0;
        try {
            JSONObject cfg = new JSONObject(getIntent().getStringExtra("cfg"));
            id = cfg.optString("id");
            live = cfg.optBoolean("live");
            items = cfg.getJSONArray("items");
            idx = Math.max(0, Math.min(cfg.optInt("index", 0), items.length() - 1));
            chans = cfg.optJSONArray("chans");
            ci = cfg.optInt("chanIndex", 0);
            start = cfg.optLong("start", 0);
            seekMs = Math.max(5, cfg.optInt("seek", 10)) * 1000;
            subsOn = cfg.optBoolean("subsOn", false);
            JSONArray sl = cfg.optJSONArray("subs");
            if (sl != null) {
                subLangs = new String[sl.length()];
                for (int i = 0; i < sl.length(); i++) subLangs[i] = sl.getString(i);
            }
            JSONArray l = cfg.optJSONArray("langs");
            if (l != null) {
                langs = new String[l.length()];
                for (int i = 0; i < l.length(); i++) langs[i] = l.getString(i);
            }
        } catch (Exception e) {
            finish();
            return;
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Netefeliks/2 (Linux; Android) ExoPlayer")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        // Tonspur automatisch in der Wunschsprache des Profils
        TrackSelectionParameters params = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguages(langs)
                .setPreferredTextLanguages(subLangs)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
                .build();
        player.setTrackSelectionParameters(params);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { fallback(); }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED && !live) finishWith(true);
            }
        });

        view = new PlayerView(this);
        view.setPlayer(player);
        view.setKeepScreenOn(true);
        view.setShowSubtitleButton(true);
        view.setControllerShowTimeoutMs(4000);
        setContentView(view);
        view.requestFocus();

        load(start);
        if (live && chans != null && chans.length() > 1) toast("Hoch/Runter: Sender wechseln · ☰ Menü: Ton & Qualität");
        else toast("☰ Menü-Taste: Tonspur, Untertitel & Qualität");
    }

    private JSONObject cur() { return items.optJSONObject(idx); }

    private void load(long posMs) {
        JSONObject it = cur();
        if (it == null) { finishWith(false); return; }
        triedAlt = false;
        player.setMediaItem(MediaItem.fromUri(it.optString("url")), Math.max(0, posMs));
        player.prepare();
        player.play();
    }

    /** Wenn ein Stream nicht geht: erst Ersatz-Adresse, dann nächstschlechtere Qualität derselben Sprache, dann andere Version. */
    private void fallback() {
        JSONObject it = cur();
        long pos = live ? 0 : Math.max(0, player.getCurrentPosition());
        if (it != null && !triedAlt && it.optString("alt").length() > 0) {
            triedAlt = true;
            player.setMediaItem(MediaItem.fromUri(it.optString("alt")), pos);
            player.prepare();
            player.play();
            return;
        }
        String lang = it != null ? it.optString("lang") : "";
        int next = -1;
        for (int i = idx + 1; i < items.length(); i++) {
            if (lang.equals(items.optJSONObject(i).optString("lang"))) { next = i; break; }
        }
        if (next < 0 && idx + 1 < items.length()) next = idx + 1;
        if (next >= 0) {
            toast(it.optString("label") + " geht hier nicht – wechsle zu " + items.optJSONObject(next).optString("label"));
            idx = next;
            load(pos);
        } else {
            toast("Dieser Stream lässt sich gerade nicht abspielen.");
            finishWith(false);
        }
    }

    // ---------- Menü während der Wiedergabe: Tonspur, Untertitel, Version ----------
    private static class Opt { Tracks.Group g; int i; String label; boolean sel; }

    private static String lang(Format f) {
        String l = f.language;
        String name = (l == null || l.length() == 0 || "und".equals(l)) ? "Unbekannt" : new Locale(l).getDisplayLanguage(Locale.GERMAN);
        if (name == null || name.length() == 0) name = l;
        StringBuilder b = new StringBuilder(name);
        if (f.label != null && f.label.length() > 0 && !f.label.equalsIgnoreCase(name)) b.append(" – ").append(f.label);
        if (f.channelCount >= 6) b.append(" (5.1)");
        return b.toString();
    }

    private List<Opt> tracks(int type) {
        List<Opt> out = new ArrayList<Opt>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Opt o = new Opt(); o.g = g; o.i = i; o.label = lang(g.getTrackFormat(i)); o.sel = g.isTrackSelected(i);
                out.add(o);
            }
        }
        return out;
    }

    private String curLabel(List<Opt> l, String none) {
        for (Opt o : l) if (o.sel) return o.label;
        return none;
    }

    private void showMenu() {
        final List<Opt> audio = tracks(C.TRACK_TYPE_AUDIO);
        final List<Opt> text = tracks(C.TRACK_TYPE_TEXT);
        final List<String> rows = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        if (audio.size() > 1) { rows.add("Tonspur: " + curLabel(audio, "Standard")); acts.add(1); }
        else if (audio.size() == 1) { rows.add("Tonspur: " + audio.get(0).label + " (einzige)"); acts.add(0); }
        rows.add("Untertitel: " + (text.isEmpty() ? "keine vorhanden" : curLabel(text, "Aus"))); acts.add(text.isEmpty() ? 0 : 2);
        if (items.length() > 1) { rows.add("Version: " + cur().optString("label")); acts.add(3); }
        new AlertDialog.Builder(this)
                .setTitle("Wiedergabe")
                .setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int a = acts.get(which);
                        if (a == 1) pickTrack(audio, false);
                        else if (a == 2) pickTrack(text, true);
                        else if (a == 3) showVersions();
                    }
                })
                .show();
    }

    private void pickTrack(final List<Opt> list, final boolean subs) {
        final List<String> labels = new ArrayList<String>();
        int checked = subs ? 0 : -1;
        if (subs) labels.add("Aus");
        for (int i = 0; i < list.size(); i++) {
            labels.add(list.get(i).label);
            if (list.get(i).sel) checked = subs ? i + 1 : i;
        }
        new AlertDialog.Builder(this)
                .setTitle(subs ? "Untertitel" : "Tonspur")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                        if (subs && which == 0) {
                            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                        } else {
                            Opt o = list.get(subs ? which - 1 : which);
                            if (subs) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                            b.setOverrideForType(new TrackSelectionOverride(o.g.getMediaTrackGroup(), o.i));
                        }
                        player.setTrackSelectionParameters(b.build());
                        toast(labels.get(which));
                    }
                })
                .show();
    }

    private void showVersions() {
        if (items.length() < 2) { toast("Nur eine Version verfügbar – Tonspuren über das Zahnrad im Player"); return; }
        String[] labels = new String[items.length()];
        for (int i = 0; i < items.length(); i++) labels[i] = items.optJSONObject(i).optString("label");
        new AlertDialog.Builder(this)
                .setTitle("Sprache & Qualität")
                .setSingleChoiceItems(labels, idx, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which != idx) {
                            long pos = live ? 0 : player.getCurrentPosition();
                            idx = which;
                            load(pos);
                            toast(labels[which]);
                        }
                    }
                })
                .show();
    }

    private void switchChan(int d) {
        if (chans == null || chans.length() < 2) return;
        ci = (ci + d + chans.length()) % chans.length();
        JSONObject c = chans.optJSONObject(ci);
        JSONArray ci2 = c.optJSONArray("items");
        if (ci2 == null || ci2.length() == 0) return;
        items = ci2;
        idx = 0;
        id = c.optString("id");
        toast(c.optString("title"));
        load(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_CAPTIONS) { showMenu(); return true; }
            boolean ctl = view.isControllerFullyVisible();
            if (live && !ctl && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP)) { switchChan(-1); return true; }
            if (live && !ctl && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN)) { switchChan(1); return true; }
            if (!live && !ctl && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                long p = player.getCurrentPosition() + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -seekMs : seekMs);
                player.seekTo(Math.max(0, p));
                view.showController();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void finishWith(boolean ended) {
        if (done) return;
        done = true;
        try {
            JSONObject r = new JSONObject();
            long dur = player != null ? player.getDuration() : C.TIME_UNSET;
            r.put("id", id);
            r.put("chanId", id);
            r.put("pos", player != null ? Math.max(0, player.getCurrentPosition()) : 0);
            r.put("dur", dur == C.TIME_UNSET ? 0 : dur);
            r.put("ended", ended);
            r.put("idx", idx);
            setResult(RESULT_OK, new Intent().putExtra("res", r.toString()));
        } catch (Exception ignored) { }
        finish();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override public void onBackPressed() { finishWith(false); }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }

    @Override protected void onResume() { super.onResume(); if (player != null && !done) player.play(); }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
