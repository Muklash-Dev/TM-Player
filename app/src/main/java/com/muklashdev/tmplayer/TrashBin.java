package com.muklashdev.tmplayer;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Recycle Bin: "deleted" videos are first copied into the app's private
 * trash folder. From here they can be restored to the gallery or erased
 * for good — nothing is lost by accident.
 */
public final class TrashBin {

    private static final String FILE = "tm_trash";
    private static final String US = "\u0003";
    private static final String RS = "\u0002";

    public static final class Entry {
        public String storedName;   // file name inside the trash folder
        public String origName;
        public long size;
        public long deletedMs;
    }

    private TrashBin() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "trash");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        String raw = sp(ctx).getString("entries", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String e : raw.split(RS)) {
            String[] p = e.split(US, -1);
            if (p.length == 4) {
                Entry en = new Entry();
                en.storedName = p[0];
                en.origName = p[1];
                try { en.size = Long.parseLong(p[2]); } catch (Exception ignored) {}
                try { en.deletedMs = Long.parseLong(p[3]); } catch (Exception ignored) {}
                out.add(en);
            }
        }
        out.sort((a, b) -> Long.compare(b.deletedMs, a.deletedMs));
        return out;
    }

    private static void save(Context ctx, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(RS);
            Entry e = entries.get(i);
            sb.append(e.storedName).append(US).append(e.origName).append(US)
                    .append(e.size).append(US).append(e.deletedMs);
        }
        sp(ctx).edit().putString("entries", sb.toString()).apply();
    }

    /** Copies the video's bytes into the trash. Returns the entry or null. */
    public static Entry add(Context ctx, VideoItem v) {
        try {
            String safe = v.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String stored = System.currentTimeMillis() + "_" + safe;
            File dest = new File(dir(ctx), stored);
            InputStream in;
            if (v.fromVault && v.filePath != null) in = new FileInputStream(v.filePath);
            else in = ctx.getContentResolver().openInputStream(Uri.parse(v.uriStr));
            if (in == null) return null;
            try (InputStream i2 = in; OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = i2.read(buf)) > 0) out.write(buf, 0, n);
            }
            Entry e = new Entry();
            e.storedName = stored;
            e.origName = v.name;
            e.size = dest.length();
            e.deletedMs = System.currentTimeMillis();
            List<Entry> all = list(ctx);
            all.add(e);
            save(ctx, all);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Restores a trashed video back into the public Movies folder. */
    public static boolean restore(Context ctx, Entry e) {
        File src = new File(dir(ctx), e.storedName);
        if (!src.exists()) return false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, e.origName);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/*");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                if (uri == null) return false;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                cv.clear();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
            } else {
                File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                File dest = new File(movies, e.origName);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                android.media.MediaScannerConnection.scanFile(ctx,
                        new String[]{dest.getAbsolutePath()}, null, null);
            }
            deleteForever(ctx, e);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static void deleteForever(Context ctx, Entry e) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(ctx), e.storedName).delete();
        List<Entry> all = list(ctx);
        all.removeIf(x -> x.storedName.equals(e.storedName));
        save(ctx, all);
    }

    public static void empty(Context ctx) {
        for (Entry e : list(ctx)) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(ctx), e.storedName).delete();
        }
        save(ctx, new ArrayList<>());
    }

    public static long totalSize(Context ctx) {
        long s = 0;
        for (Entry e : list(ctx)) s += e.size;
        return s;
    }
}
