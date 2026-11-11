package com.muklashdev.tmplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Size;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds videos on EVERY mounted storage volume — internal storage, SD card
 * and USB pendrives — through MediaStore, plus a folder-tree walk for
 * anything MediaStore has not indexed yet.
 */
public final class MediaLib {

    public static final class FolderInfo {
        public String path;
        public String name;
        public int count;
        public long totalSize;
    }

    public static final class StorageRoot {
        public String label;        // e.g. "Internal storage", "SD card", "USB drive"
        public String kind;         // Internal / SD card / USB
        public String volumeName;   // MediaStore volume name, may be null
        public String path;         // filesystem path, may be null
    }

    private MediaLib() {}

    // ------------------------------------------------------------------ scan

    public static List<VideoItem> scanAllVolumes(Context ctx) {
        List<VideoItem> out = new ArrayList<>();
        try {
            for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
                out.addAll(scanVolume(ctx, vol));
            }
        } catch (Exception e) {
            out.addAll(scanVolume(ctx, MediaStore.VOLUME_EXTERNAL));
        }
        return out;
    }

    public static List<VideoItem> scanVolume(Context ctx, String volumeName) {
        List<VideoItem> out = new ArrayList<>();
        Uri uri = MediaStore.Video.Media.getContentUri(volumeName);
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DATA
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, proj, null, null,
                    MediaStore.Video.Media.DATE_MODIFIED + " DESC");
            if (c == null) return out;
            int iId = c.getColumnIndex(MediaStore.Video.Media._ID);
            int iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            int iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE);
            int iW = c.getColumnIndex(MediaStore.Video.Media.WIDTH);
            int iH = c.getColumnIndex(MediaStore.Video.Media.HEIGHT);
            int iDate = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
            int iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            int iData = c.getColumnIndex(MediaStore.Video.Media.DATA);
            while (c.moveToNext()) {
                VideoItem v = new VideoItem();
                v.id = iId >= 0 ? c.getLong(iId) : 0;
                v.uriStr = Uri.withAppendedPath(uri, String.valueOf(v.id)).toString();
                v.name = iName >= 0 ? c.getString(iName) : "Video";
                v.duration = iDur >= 0 ? c.getLong(iDur) : 0;
                v.size = iSize >= 0 ? c.getLong(iSize) : 0;
                v.width = iW >= 0 ? c.getInt(iW) : 0;
                v.height = iH >= 0 ? c.getInt(iH) : 0;
                v.dateModified = iDate >= 0 ? c.getLong(iDate) : 0;
                String data = iData >= 0 ? c.getString(iData) : null;
                String rel = iRel >= 0 ? c.getString(iRel) : null;
                if (data != null && data.contains("/")) {
                    v.folderPath = data.substring(0, data.lastIndexOf('/'));
                    v.folderName = new File(v.folderPath).getName();
                } else if (rel != null && !rel.isEmpty()) {
                    String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                    v.folderPath = volumeName + "/" + r;
                    int slash = r.lastIndexOf('/');
                    v.folderName = slash >= 0 ? r.substring(slash + 1) : r;
                } else {
                    v.folderPath = volumeName;
                    v.folderName = volumeName;
                }
                out.add(v);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public static List<FolderInfo> groupFolders(List<VideoItem> videos) {
        Map<String, FolderInfo> map = new LinkedHashMap<>();
        for (VideoItem v : videos) {
            FolderInfo f = map.get(v.folderPath);
            if (f == null) {
                f = new FolderInfo();
                f.path = v.folderPath;
                f.name = v.folderName;
                map.put(f.path, f);
            }
            f.count++;
            f.totalSize += Math.max(0, v.size);
        }
        List<FolderInfo> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return out;
    }

    public static List<VideoItem> inFolder(List<VideoItem> videos, String folderPath) {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : videos) if (folderPath.equals(v.folderPath)) out.add(v);
        return out;
    }

    public static void sort(List<VideoItem> list, int mode) {
        Comparator<VideoItem> cmp;
        switch (mode) {
            case 1: cmp = (a, b) -> Long.compare(a.dateModified, b.dateModified); break;
            case 2: cmp = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name); break;
            case 3: cmp = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(b.name, a.name); break;
            case 4: cmp = (a, b) -> Long.compare(b.size, a.size); break;
            case 5: cmp = (a, b) -> Long.compare(a.size, b.size); break;
            case 6: cmp = (a, b) -> Long.compare(b.duration, a.duration); break;
            case 7: cmp = (a, b) -> Long.compare(a.duration, b.duration); break;
            default: cmp = (a, b) -> Long.compare(b.dateModified, a.dateModified); break;
        }
        Collections.sort(list, cmp);
    }

    // ------------------------------------------------------------- storage

    public static List<StorageRoot> storageRoots(Context ctx) {
        List<StorageRoot> out = new ArrayList<>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return out;
            for (StorageVolume vol : sm.getStorageVolumes()) {
                StorageRoot r = new StorageRoot();
                r.label = vol.getDescription(ctx);
                if (vol.isPrimary()) r.kind = "Internal";
                else if (vol.isRemovable()) r.kind = guessRemovableKind(vol);
                else r.kind = "Storage";
                if (Build.VERSION.SDK_INT >= 30) {
                    r.volumeName = vol.getMediaStoreVolumeName();
                    File d = vol.getDirectory();
                    r.path = d != null ? d.getAbsolutePath() : null;
                } else if (vol.isPrimary()) {
                    r.volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
                    r.path = Environment.getExternalStorageDirectory().getAbsolutePath();
                }
                out.add(r);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String guessRemovableKind(StorageVolume vol) {
        try {
            String uuid = vol.getUuid();
            // USB volumes usually carry a short FAT uuid like "1234-ABCD";
            // SD cards too — the description is the best hint available.
            String desc = vol.getDescription(null);
            if (desc != null && desc.toLowerCase().contains("usb")) return "USB";
            if (desc != null && desc.toLowerCase().contains("sd")) return "SD card";
            return uuid != null ? "SD / USB" : "SD card";
        } catch (Exception e) {
            return "SD / USB";
        }
    }

    // ------------------------------------------------- picked folder (SAF)

    /** Recursively lists videos under a user-picked folder (any storage). */
    public static List<VideoItem> scanTree(Context ctx, Uri treeUri) {
        List<VideoItem> out = new ArrayList<>();
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        walkTree(ctx, treeUri, treeId, out, 0);
        return out;
    }

    private static void walkTree(Context ctx, Uri treeUri, String docId,
                                 List<VideoItem> out, int depth) {
        if (depth > 12) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(children, proj, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(ctx, treeUri, id, out, depth + 1);
                } else if (isVideo(name, mime)) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    VideoItem v = new VideoItem();
                    v.uriStr = docUri.toString();
                    v.name = name;
                    v.size = c.isNull(3) ? 0 : c.getLong(3);
                    v.dateModified = c.isNull(4) ? 0 : c.getLong(4) / 1000;
                    v.folderPath = "picked";
                    v.folderName = "Picked folder";
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean isVideo(String name, String mime) {
        if (mime != null && mime.startsWith("video/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".3gp")
                || n.endsWith(".ts") || n.endsWith(".m4v") || n.endsWith(".flv");
    }

    // ------------------------------------------------------------ thumbnails

    public static Bitmap loadThumb(Context ctx, VideoItem v, int wPx, int hPx) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (v.fromVault && v.filePath != null) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(v.filePath);
                    long tUs = 1_000_000;
                    try {
                        int tp = new Prefs(ctx).thumbPos();
                        if (tp > 0 && v.duration > 0) {
                            double fr = tp == 1 ? 0.10 : tp == 2 ? 0.25 : 0.50;
                            tUs = (long) (v.duration * 1000L * fr);
                        }
                    } catch (Exception ignored) {}
                    Bitmap b = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null) return b;
                } finally {
                    r.release();
                }
            }
            Uri uri = Uri.parse(v.uriStr);
            if (Build.VERSION.SDK_INT >= 29) {
                return cr.loadThumbnail(uri, new Size(wPx, hPx), null);
            }
            @SuppressWarnings("deprecation")
            Bitmap b = MediaStore.Video.Thumbnails.getThumbnail(cr, v.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- audio

    public static List<AudioItem> scanAllAudio(Context ctx) {
        List<AudioItem> out = new ArrayList<>();
        try {
            for (String vol : MediaStore.getExternalVolumeNames(ctx)) {
                Uri uri = MediaStore.Audio.Media.getContentUri(vol);
                String[] proj = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        MediaStore.Audio.Media.ALBUM
                };
                Cursor c = null;
                try {
                    c = ctx.getContentResolver().query(uri, proj,
                            MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                            MediaStore.Audio.Media.TITLE + " ASC");
                    if (c == null) continue;
                    while (c.moveToNext()) {
                        AudioItem a = new AudioItem();
                        a.id = c.getLong(0);
                        a.uriStr = Uri.withAppendedPath(uri, String.valueOf(a.id)).toString();
                        a.title = c.getString(1);
                        a.artist = c.getString(2);
                        a.duration = c.isNull(3) ? 0 : c.getLong(3);
                        a.size = c.isNull(4) ? 0 : c.getLong(4);
                        String rel = c.isNull(5) ? null : c.getString(5);
                        if (rel != null && !rel.isEmpty()) {
                            String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                            int s = r.lastIndexOf('/');
                            a.folderName = s >= 0 ? r.substring(s + 1) : r;
                        }
                        a.album = c.isNull(6) ? null : c.getString(6);
                        if (a.title == null || a.title.isEmpty()) a.title = "Unknown title";
                        if (a.artist == null) a.artist = "";
                        out.add(a);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------------------------------------------------------------- delete

    public enum DeleteResult { DELETED, NEEDS_CONSENT, FAILED }

    /** Tries to delete a MediaStore video; consent flow handled by caller. */
    public static DeleteResult delete(Context ctx, VideoItem v) {
        try {
            int rows = ctx.getContentResolver().delete(Uri.parse(v.uriStr), null, null);
            return rows > 0 ? DeleteResult.DELETED : DeleteResult.FAILED;
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 29
                    && e instanceof android.app.RecoverableSecurityException) {
                return DeleteResult.NEEDS_CONSENT;
            }
            return DeleteResult.FAILED;
        }
    }
}
