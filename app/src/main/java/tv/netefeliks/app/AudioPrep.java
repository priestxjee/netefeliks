package tv.netefeliks.app;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * „Ton vorbereiten": liest eine Sprachfassung einmal vom Server, behält nur die Tonspur und speichert sie auf dem Gerät.
 * So kann der Film später mit dem besten Bild (eine Verbindung) und diesem Ton (aus dem Speicher) laufen –
 * auch wenn das Abo nur eine gleichzeitige Verbindung erlaubt.
 * Unterstützt: AC3, E-AC3, AAC und MP3 (die üblichen Formate in IPTV-Filmen).
 */
class AudioPrep {
    interface Events { void emit(String event, String key, String arg); }

    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private final Context ctx;
    private final Events events;
    private final String ua;
    private volatile String running = null;
    private volatile boolean cancel = false;
    /** So viele vorbereitete Tonspuren behalten; die älteste wird automatisch gelöscht. */
    private volatile int keep = 2;

    void setKeep(int n) { keep = Math.max(1, Math.min(3, n)); cleanup(keep); }

    AudioPrep(Context ctx, String ua, Events events) {
        this.ctx = ctx;
        this.ua = ua;
        this.events = events;
    }

    private File dir() {
        File d = new File(ctx.getFilesDir(), "audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String key) {
        return Integer.toHexString(key.hashCode()) + "_" + key.replaceAll("[^A-Za-z0-9]", "").replaceAll("^(.{0,24}).*$", "$1");
    }

    boolean busy() { return running != null; }

    long free() { return new StatFs(ctx.getFilesDir().getPath()).getAvailableBytes(); }

    void cancel() { cancel = true; }

    void start(final String key, final String url, final String langsCsv) {
        if (running != null) { events.emit("error", key, "Es wird schon ein Ton vorbereitet."); return; }
        running = key;
        cancel = false;
        new Thread(new Runnable() {
            @Override public void run() {
                try { prepare(key, url, langsCsv); }
                catch (Throwable t) { events.emit("error", key, String.valueOf(t.getMessage())); }
                finally { running = null; }
            }
        }).start();
    }

    private void prepare(String key, String url, String langsCsv) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", ua);
        ex.setDataSource(url, headers);

        // Tonspur wählen: Wunschsprache zuerst, sonst die erste unterstützte
        String[] langs = langsCsv == null || langsCsv.length() == 0 ? new String[0] : langsCsv.split(",");
        int track = -1, best = 999;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null || !supported(mime)) continue;
            String lang = f.containsKey(MediaFormat.KEY_LANGUAGE) ? f.getString(MediaFormat.KEY_LANGUAGE) : "";
            int rank = 500;
            for (int k = 0; k < langs.length; k++) if (lang != null && lang.startsWith(langs[k])) { rank = k; break; }
            if (track < 0 || rank < best) { track = i; best = rank; fmt = f; }
        }
        if (track < 0) {
            ex.release();
            throw new Exception("Das Tonformat dieser Fassung wird nicht unterstützt (z. B. DTS).");
        }
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;

        // Platz prüfen (grob: 700 kbit/s * Länge) und alte Tonspuren aufräumen
        cleanup(keep - 1);
        long need = durUs > 0 ? durUs / 1000000L * 90000L : 300L * 1024 * 1024;
        StatFs st = new StatFs(ctx.getFilesDir().getPath());
        if (st.getAvailableBytes() < need + 200L * 1024 * 1024) {
            ex.release();
            throw new Exception("Zu wenig Speicher auf dem Fire TV für diese Tonspur.");
        }

        ex.selectTrack(track);
        String ext = ext(mime);
        File tmp = new File(dir(), safe(key) + ".part");
        File out = new File(dir(), safe(key) + "." + ext);
        OutputStream os = new FileOutputStream(tmp);
        ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
        byte[] arr = new byte[2 * 1024 * 1024];
        boolean adts = "audio/mp4a-latm".equals(mime);
        int[] adtsInfo = adts ? adtsInfo(fmt) : null;
        long firstUs = -1;
        int lastPct = -1;
        try {
            while (!cancel) {
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                long t = ex.getSampleTime();
                if (firstUs < 0) firstUs = Math.max(0, t);
                buf.get(arr, 0, n);
                if (adts) os.write(adtsHeader(adtsInfo, n));
                os.write(arr, 0, n);
                ex.advance();
                if (durUs > 0) {
                    int pct = (int) Math.min(99, t * 100 / durUs);
                    if (pct != lastPct) { lastPct = pct; events.emit("progress", key, String.valueOf(pct)); }
                }
            }
        } finally {
            os.close();
            ex.release();
        }
        if (cancel) { tmp.delete(); events.emit("cancel", key, ""); return; }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("Speichern fehlgeschlagen.");
        JSONObject r = new JSONObject();
        r.put("path", out.getAbsolutePath());
        r.put("offsetMs", Math.max(0, firstUs) / 1000);
        r.put("mime", mime);
        r.put("size", out.length());
        events.emit("done", key, r.toString());
    }

    private static boolean supported(String mime) {
        return "audio/ac3".equals(mime) || "audio/eac3".equals(mime) || "audio/mp4a-latm".equals(mime) || "audio/mpeg".equals(mime);
    }

    private static String ext(String mime) {
        if ("audio/ac3".equals(mime)) return "ac3";
        if ("audio/eac3".equals(mime)) return "eac3";
        if ("audio/mpeg".equals(mime)) return "mp3";
        return "aac";
    }

    /** [Profil, Frequenz-Index, Kanal-Konfiguration] für ADTS-Köpfe aus dem AAC-Format. */
    private static int[] adtsInfo(MediaFormat f) {
        int profile = 2;
        if (f.containsKey("csd-0")) {
            ByteBuffer csd = f.getByteBuffer("csd-0");
            if (csd != null && csd.remaining() >= 1) {
                int aot = (csd.get(csd.position()) & 0xFF) >> 3;
                if (aot >= 1 && aot <= 4) profile = aot;
            }
        }
        int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
        int idx = 3;
        for (int i = 0; i < RATES.length; i++) if (RATES[i] == rate) { idx = i; break; }
        int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        if (ch == 8) ch = 7;
        if (ch > 7) ch = 2;
        return new int[]{profile, idx, ch};
    }

    private static byte[] adtsHeader(int[] i, int payload) {
        int len = payload + 7;
        int profile = i[0], freq = i[1], ch = i[2];
        return new byte[]{
                (byte) 0xFF, (byte) 0xF1,
                (byte) (((profile - 1) << 6) + (freq << 2) + (ch >> 2)),
                (byte) (((ch & 3) << 6) + (len >> 11)),
                (byte) ((len & 0x7FF) >> 3),
                (byte) (((len & 7) << 5) + 0x1F),
                (byte) 0xFC};
    }

    /** Liste der vorbereiteten Tonspuren (neueste zuerst). */
    String list() {
        JSONArray a = new JSONArray();
        File[] fs = dir().listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File f : fs) {
                if (f.getName().endsWith(".part")) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("path", f.getAbsolutePath());
                    o.put("size", f.length());
                    a.put(o);
                } catch (Exception ignored) { }
            }
        }
        return a.toString();
    }

    void delete(String path) {
        File f = new File(path);
        if (f.getParentFile() != null && f.getParentFile().equals(dir())) f.delete();
    }

    void deleteAll() { cleanup(0); }

    /** Nur die neuesten {@code keep} Tonspuren behalten. */
    private void cleanup(int keep) {
        File[] fs = dir().listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        int kept = 0;
        for (File f : fs) {
            if (f.getName().endsWith(".part")) { f.delete(); continue; }
            if (kept < keep) { kept++; continue; }
            f.delete();
        }
    }
}
