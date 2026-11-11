package com.muklashdev.tmplayer;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Private vault: videos copied into the app's private storage, where no
 * gallery or other app can see them, behind a PIN.
 */
public final class Vault {

    private Vault() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "vault");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ PIN

    public static String hashPin(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(("tm-player::" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(pin.hashCode());
        }
    }

    // ---------------------------------------------------------------- files

    public static List<VideoItem> list(Context ctx) {
        List<VideoItem> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile()) continue;
            VideoItem v = new VideoItem();
            v.fromVault = true;
            v.filePath = f.getAbsolutePath();
            v.uriStr = Uri.fromFile(f).toString();
            String n = f.getName();
            int us = n.indexOf('_');
            v.name = us > 0 && n.substring(0, us).matches("\\d+") ? n.substring(us + 1) : n;
            v.size = f.length();
            v.dateModified = f.lastModified() / 1000;
            v.folderPath = "vault";
            v.folderName = "Private Vault";
            // duration + resolution (cheap, one pass)
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (d != null) v.duration = Long.parseLong(d);
                if (w != null) v.width = Integer.parseInt(w);
                if (h != null) v.height = Integer.parseInt(h);
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            out.add(v);
        }
        out.sort((a, b) -> Long.compare(b.dateModified, a.dateModified));
        return out;
    }

    /** Copies the video into the vault. Returns the vault file, or null. */
    public static File copyInto(Context ctx, VideoItem v) {
        try {
            String safe = v.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File dest = new File(dir(ctx), System.currentTimeMillis() + "_" + safe);
            try (InputStream in = openSource(ctx, v);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) return null;
                copy(in, out);
            }
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream openSource(Context ctx, VideoItem v) throws Exception {
        if (v.fromVault && v.filePath != null) return new FileInputStream(v.filePath);
        return ctx.getContentResolver().openInputStream(Uri.parse(v.uriStr));
    }

    /** Copies a vault file back to the public Movies folder. */
    public static boolean exportBack(Context ctx, VideoItem v) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, v.name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/*");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/TM Player");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                if (uri == null) return false;
                try (InputStream in = new FileInputStream(v.filePath);
                     OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    copy(in, out);
                }
                cv.clear();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
                return true;
            }
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder = new File(movies, "TM Player");
            //noinspection ResultOfMethodCallIgnored
            folder.mkdirs();
            File dest = new File(folder, v.name);
            try (InputStream in = new FileInputStream(v.filePath);
                 OutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            }
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{dest.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean remove(VideoItem v) {
        try {
            return v.filePath != null && new File(v.filePath).delete();
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
