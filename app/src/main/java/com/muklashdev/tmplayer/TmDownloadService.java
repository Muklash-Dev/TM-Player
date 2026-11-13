package com.muklashdev.tmplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foreground service that owns browser downloads, so they keep running when
 * the user leaves the browser or the app. Supports pause / resume (HTTP
 * Range against a kept partial file), cancel, and survives process death:
 * unfinished tasks are persisted and come back as Paused.
 */
public class TmDownloadService extends Service {

    public static class Task {
        public final String url;
        public String name, mime, ua, referer;
        public volatile long total = -1, done = 0;
        public volatile long startMs = System.currentTimeMillis();
        public volatile boolean pauseReq, cancelReq, running;
        public volatile HttpURLConnection conn;
        public File temp;
        Task(String u) { url = u; }
        public double speedBps() {
            long el = System.currentTimeMillis() - startMs;
            return el <= 0 ? 0 : done * 1000.0 / el;
        }
    }

    public static final ConcurrentHashMap<String, Task> ACTIVE = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, Task> PAUSED = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, BrowserActivity.DohFetch.Callback> CBS =
            new ConcurrentHashMap<>();
    public static volatile long lastChangeMs = 0;

    static final int NOTIF_ID = 7001;
    static final String CHANNEL = "tm_downloads";
    static TmDownloadService instance;
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean tickerOn;

    // ------------------------------------------------------------ static API
    public static void enqueue(Context ctx, String url, String nameHint, String ua,
            String referer, BrowserActivity.DohFetch.Callback cb) {
        Context app = ctx.getApplicationContext();
        loadPersisted(app);
        if (ACTIVE.containsKey(url)) {
            if (cb != null) cb.onMsg("Already downloading");
            return;
        }
        Task t = PAUSED.remove(url);
        if (t == null) {
            t = new Task(url);
            t.name = nameHint != null && !nameHint.isEmpty()
                    ? nameHint : BrowserActivity.filenameFromUrl(url);
            t.ua = ua;
            t.referer = referer;
            t.temp = tempFor(app, url);
        } else {
            if (ua != null) t.ua = ua;
            if (referer != null && !referer.isEmpty()) t.referer = referer;
            t.done = t.temp != null && t.temp.exists() ? t.temp.length() : 0;
        }
        t.pauseReq = false;
        t.cancelReq = false;
        t.startMs = System.currentTimeMillis();
        ACTIVE.put(url, t);
        if (cb != null) CBS.put(url, cb);
        savePersisted(app);
        if (cb != null) cb.onMsg("⬇ Downloading: " + t.name);
        Intent i = new Intent(app, TmDownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {}
    }

    public static void pause(String url) {
        Task t = ACTIVE.get(url);
        if (t != null) t.pauseReq = true;
    }

    public static void cancel(String url) {
        Task t = ACTIVE.get(url);
        if (t != null) {
            t.cancelReq = true;
            try { if (t.conn != null) t.conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    public static void deletePaused(Context ctx, String url) {
        Task t = PAUSED.remove(url);
        if (t != null && t.temp != null) {
            try { t.temp.delete(); } catch (Exception ignored) {}
        }
        CBS.remove(url);
        savePersisted(ctx.getApplicationContext());
    }

    // ------------------------------------------------------------ persistence
    static File tasksFile(Context ctx) {
        return new File(ctx.getFilesDir(), "download_tasks.json");
    }

    static File tempDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "tmp_dl");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File tempFor(Context ctx, String url) {
        return new File(tempDir(ctx), "dl_" + (url.hashCode() & 0xffffffffL) + ".part");
    }

    static synchronized void savePersisted(Context ctx) {
        lastChangeMs = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray();
            for (Task t : ACTIVE.values()) arr.put(taskJson(t));
            for (Task t : PAUSED.values()) if (!ACTIVE.containsKey(t.url)) arr.put(taskJson(t));
            File f = tasksFile(ctx);
            FileOutputStream out = new FileOutputStream(f);
            out.write(arr.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {}
    }

    static JSONObject taskJson(Task t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("url", t.url);
        o.put("name", t.name == null ? "" : t.name);
        o.put("mime", t.mime == null ? "" : t.mime);
        o.put("ua", t.ua == null ? "" : t.ua);
        o.put("referer", t.referer == null ? "" : t.referer);
        o.put("total", t.total);
        return o;
    }

    public static synchronized void loadPersisted(Context ctx) {
        try {
            File f = tasksFile(ctx);
            if (!f.exists()) return;
            String s = BrowserActivity.DohFetch.readAll(new FileInputStream(f));
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url");
                if (url.isEmpty() || ACTIVE.containsKey(url) || PAUSED.containsKey(url)) continue;
                File temp = tempFor(ctx, url);
                if (!temp.exists()) continue; // nothing downloaded — nothing to resume
                Task t = new Task(url);
                t.name = o.optString("name");
                t.mime = o.optString("mime");
                if (t.mime != null && t.mime.isEmpty()) t.mime = null;
                t.ua = o.optString("ua");
                t.referer = o.optString("referer");
                t.total = o.optLong("total", -1);
                t.temp = temp;
                t.done = temp.length();
                PAUSED.put(url, t);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ service
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        loadPersisted(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(NOTIF_ID, buildNotification()); } catch (Exception ignored) {}
        pump();
        if (ACTIVE.isEmpty()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startTicker();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        tickerOn = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    void pump() {
        for (Task t : ACTIVE.values())
            if (!t.running) runTask(t);
    }

    void startTicker() {
        if (tickerOn) return;
        tickerOn = true;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!tickerOn) return;
                if (ACTIVE.isEmpty()) {
                    tickerOn = false;
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIF_ID, buildNotification());
                } catch (Exception ignored) {}
                ui.postDelayed(this, 900);
            }
        });
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Browser download progress");
                nm.createNotificationChannel(ch);
            }
        }
    }

    Notification buildNotification() {
        Task first = null;
        for (Task t : ACTIVE.values()) { first = t; break; }
        Intent open = new Intent(this, BrowserActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        String text;
        int pct = -1;
        if (first != null) {
            text = first.name + (ACTIVE.size() > 1 ? "  (+" + (ACTIVE.size() - 1) + " more)" : "");
            if (first.total > 0) pct = (int) (first.done * 100 / first.total);
        } else {
            text = "Preparing…";
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("TM Browser — downloading")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    void msg(Task t, String m) {
        BrowserActivity.DohFetch.Callback cb = CBS.get(t.url);
        if (cb != null) {
            try { cb.onMsg(m); return; } catch (Exception ignored) {}
        }
        ui.post(() -> Toast.makeText(getApplicationContext(), m, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------ engine
    void runTask(final Task t) {
        t.running = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                long have = t.temp != null && t.temp.exists() ? t.temp.length() : 0;
                t.done = have;
                HttpURLConnection c;
                try {
                    c = BrowserActivity.DohFetch.openDirect(t.url, t.ua, t.referer, have);
                } catch (UnknownHostException dnsFail) {
                    c = BrowserActivity.DohFetch.open(t.url, t.referer, have);
                }
                t.conn = c;
                int code = c.getResponseCode();
                boolean append;
                if (code == 206) {
                    append = have > 0;
                    long len = c.getContentLengthLong();
                    t.total = len >= 0 ? have + len : -1;
                } else if (code == 200) {
                    append = false;
                    t.total = c.getContentLengthLong();
                } else {
                    throw new java.io.IOException("HTTP " + code);
                }
                if (t.mime == null) {
                    String m = c.getContentType();
                    if (m != null) t.mime = m.split(";")[0].trim();
                }
                if (t.name == null || t.name.isEmpty() || t.name.equals("download"))
                    t.name = BrowserActivity.filenameFromUrl(t.url);
                if (!BrowserActivity.DohFetch.hasExtension(t.name) && t.mime != null) {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(t.mime);
                    if (ext != null) t.name = t.name + "." + ext;
                }
                savePersisted(app);
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(t.temp, append);
                if (!append) t.done = 0;
                byte[] buf = new byte[65536];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        if (t.cancelReq || t.pauseReq) break;
                        out.write(buf, 0, n);
                        t.done += n;
                    }
                    out.flush();
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }
                t.conn = null;
                if (t.cancelReq) {
                    if (t.temp != null) t.temp.delete();
                    ACTIVE.remove(t.url);
                    PAUSED.remove(t.url);
                    CBS.remove(t.url);
                    savePersisted(app);
                    msg(t, "Download cancelled: " + t.name);
                    return;
                }
                if (t.pauseReq) {
                    t.pauseReq = false;
                    ACTIVE.remove(t.url);
                    PAUSED.put(t.url, t);
                    savePersisted(app);
                    msg(t, "⏸ Paused: " + t.name);
                    return;
                }
                if (t.total > 0 && t.done < t.total) {
                    ACTIVE.remove(t.url);
                    PAUSED.put(t.url, t);
                    savePersisted(app);
                    msg(t, "Download stopped — tap Resume to continue: " + t.name);
                    return;
                }
                FileInputStream fin = new FileInputStream(t.temp);
                try {
                    BrowserActivity.saveStream(app, fin, t.name, t.mime);
                } finally {
                    try { fin.close(); } catch (Exception ignored) {}
                }
                if (t.temp != null) t.temp.delete();
                ACTIVE.remove(t.url);
                PAUSED.remove(t.url);
                CBS.remove(t.url);
                savePersisted(app);
                msg(t, "⬇ Saved: " + t.name);
            } catch (Exception e) {
                t.conn = null;
                if (t.cancelReq) {
                    if (t.temp != null) t.temp.delete();
                    ACTIVE.remove(t.url);
                    PAUSED.remove(t.url);
                    CBS.remove(t.url);
                    savePersisted(app);
                    msg(t, "Download cancelled: " + t.name);
                } else {
                    ACTIVE.remove(t.url);
                    boolean partial = t.temp != null && t.temp.exists() && t.temp.length() > 0;
                    if (partial) PAUSED.put(t.url, t);
                    savePersisted(app);
                    msg(t, "Download failed: " + e.getMessage()
                            + (partial ? " — tap Resume to retry" : ""));
                }
            } finally {
                t.running = false;
                if (instance == this) ui.post(() -> {
                    if (ACTIVE.isEmpty()) {
                        stopForeground(true);
                        stopSelf();
                    }
                });
            }
        }).start();
    }
}
