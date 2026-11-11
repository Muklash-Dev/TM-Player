package com.muklashdev.tmplayer;

/** One video, from MediaStore, a picked folder, or the private vault. */
public final class VideoItem {
    public long id;
    public String uriStr;      // content:// uri (or file:// for vault)
    public String name;
    public long duration;      // ms, 0 if unknown
    public long size;          // bytes, 0 if unknown
    public int width;
    public int height;
    public String folderPath;  // grouping key
    public String folderName;
    public long dateModified;  // seconds
    public boolean fromVault;
    public String filePath;    // vault only

    public String quality() {
        int p = Math.min(width, height);
        if (p >= 2000) return "4K";
        if (p >= 1080) return "1080p";
        if (p >= 720) return "720p";
        if (p >= 480) return "480p";
        if (width > 0) return "SD";
        return "";
    }

    public String resolution() {
        if (width <= 0 || height <= 0) return "Unknown";
        return width + " × " + height;
    }
}
