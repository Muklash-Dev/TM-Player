package com.muklashdev.tmplayer;

import java.util.ArrayList;
import java.util.List;

/** The list the user tapped into, shared with PlayerActivity. */
public final class PlayQueue {
    public static List<VideoItem> items = new ArrayList<>();
    public static int index = 0;

    public static void set(List<VideoItem> list, int start) {
        items = new ArrayList<>(list);
        index = Math.max(0, Math.min(start, items.size() - 1));
    }

    public static VideoItem current() {
        if (items.isEmpty()) return null;
        return items.get(Math.max(0, Math.min(index, items.size() - 1)));
    }

    public static boolean hasNext() { return index < items.size() - 1; }
    public static boolean hasPrev() { return index > 0; }
}
