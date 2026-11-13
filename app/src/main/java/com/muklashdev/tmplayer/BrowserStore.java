package com.muklashdev.tmplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny JSON-file store for the built-in browser: history + bookmarks,
 * kept in the app's private files. Original code, no database needed
 * at this scale.
 */
final class BrowserStore {

    static final class Entry {
        String title;
        String url;
        long time;
    }

    private static final int MAX_HISTORY = 500;

    private BrowserStore() {}

    private static File file(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name);
    }

    private static List<Entry> read(Context ctx, String name) {
        List<Entry> out = new ArrayList<>();
        try {
            File f = file(ctx, name);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            in.close();
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.title = o.optString("t");
                e.url = o.optString("u");
                e.time = o.optLong("w");
                if (!e.url.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void write(Context ctx, String name, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("t", e.title == null ? "" : e.title);
                o.put("u", e.url);
                o.put("w", e.time);
                arr.put(o);
            }
            FileOutputStream out = new FileOutputStream(file(ctx, name));
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ history

    static List<Entry> history(Context ctx) {
        return read(ctx, "browser_history.json");
    }

    static void addHistory(Context ctx, String title, String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        List<Entry> list = history(ctx);
        if (!list.isEmpty() && url.equals(list.get(0).url)) {
            if (title != null && !title.isEmpty()) {
                list.get(0).title = title;
                write(ctx, "browser_history.json", list);
            }
            return;
        }
        Entry e = new Entry();
        e.title = (title == null || title.isEmpty()) ? url : title;
        e.url = url;
        e.time = System.currentTimeMillis();
        list.add(0, e);
        while (list.size() > MAX_HISTORY) list.remove(list.size() - 1);
        write(ctx, "browser_history.json", list);
    }

    static void clearHistory(Context ctx) {
        write(ctx, "browser_history.json", new ArrayList<>());
    }

    // ------------------------------------------------------------ bookmarks

    static List<Entry> bookmarks(Context ctx) {
        return read(ctx, "browser_bookmarks.json");
    }

    static boolean isBookmarked(Context ctx, String url) {
        if (url == null) return false;
        for (Entry e : bookmarks(ctx)) if (url.equals(e.url)) return true;
        return false;
    }

    /** Returns true if the page is bookmarked after the toggle. */
    static boolean toggleBookmark(Context ctx, String title, String url) {
        List<Entry> list = bookmarks(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (url.equals(list.get(i).url)) {
                list.remove(i);
                write(ctx, "browser_bookmarks.json", list);
                return false;
            }
        }
        Entry e = new Entry();
        e.title = (title == null || title.isEmpty()) ? url : title;
        e.url = url;
        e.time = System.currentTimeMillis();
        list.add(0, e);
        write(ctx, "browser_bookmarks.json", list);
        return true;
    }

    static void removeBookmark(Context ctx, String url) {
        List<Entry> list = bookmarks(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (url.equals(list.get(i).url)) list.remove(i);
        }
        write(ctx, "browser_bookmarks.json", list);
    }

    static void removeHistoryItem(Context ctx, String url) {
        List<Entry> list = history(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (url.equals(list.get(i).url)) list.remove(i);
        }
        write(ctx, "browser_history.json", list);
    }
}
