package com.muklashdev.tmplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings for TM Player, in one place. */
public final class Prefs {
    private static final String FILE = "tm_player_prefs";

    public static final int[] ACCENTS = {
            0xFF19C8E8, // cyan (logo)
            0xFF8B7CF6, // violet
            0xFF3DBFA8, // teal (design sheet)
            0xFFFB923C, // orange
            0xFFF472B6, // pink
            0xFF4ADE80  // green
    };
    public static final String[] ACCENT_NAMES = {
            "Cyan", "Violet", "Teal", "Orange", "Pink", "Green"
    };
    public static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f};
    public static final String[] RESIZE_NAMES = {"Fit", "Fill", "Stretch"};
    public static final String[] SORT_NAMES = {
            "Date: newest first", "Date: oldest first",
            "Name: A–Z", "Name: Z–A",
            "Size: largest first", "Size: smallest first",
            "Duration: longest first", "Duration: shortest first"};

    // ---- home Quick Settings: which info fields are shown on video rows ----
    public boolean fldDuration() { return sp.getBoolean("fld_duration", true); }
    public void setFldDuration(boolean b) { sp.edit().putBoolean("fld_duration", b).apply(); }
    public boolean fldFolderDuration() { return sp.getBoolean("fld_folder_duration", true); }
    public void setFldFolderDuration(boolean b) { sp.edit().putBoolean("fld_folder_duration", b).apply(); }
    public boolean fldExtension() { return sp.getBoolean("fld_extension", false); }
    public void setFldExtension(boolean b) { sp.edit().putBoolean("fld_extension", b).apply(); }
    public boolean fldPath() { return sp.getBoolean("fld_path", false); }
    public void setFldPath(boolean b) { sp.edit().putBoolean("fld_path", b).apply(); }
    public boolean fldProgress() { return sp.getBoolean("fld_progress", true); }
    public void setFldProgress(boolean b) { sp.edit().putBoolean("fld_progress", b).apply(); }
    public boolean fldResolution() { return sp.getBoolean("fld_resolution", false); }
    public void setFldResolution(boolean b) { sp.edit().putBoolean("fld_resolution", b).apply(); }
    public boolean fldSize() { return sp.getBoolean("fld_size", true); }
    public void setFldSize(boolean b) { sp.edit().putBoolean("fld_size", b).apply(); }
    public boolean fldThumbnail() { return sp.getBoolean("fld_thumbnail", true); }
    public void setFldThumbnail(boolean b) { sp.edit().putBoolean("fld_thumbnail", b).apply(); }

    // ---- Settings categories (Next Player-style), build 9 ----
    /** 0 = System default, 1 = Light, 2 = Dark. Migrates the old dark boolean. */
    public int themeMode() {
        if (!sp.contains("theme_mode")) return sp.getBoolean("dark", true) ? 2 : 1;
        return sp.getInt("theme_mode", 0);
    }
    public void setThemeMode(int m) { sp.edit().putInt("theme_mode", m).apply(); }
    public boolean isDark(android.content.Context ctx) {
        int m = themeMode();
        if (m == 2) return true;
        if (m == 1) return false;
        int ui = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    public boolean highContrast() { return sp.getBoolean("high_contrast", false); }
    public void setHighContrast(boolean b) { sp.edit().putBoolean("high_contrast", b).apply(); }
    public boolean dynamicColor() { return sp.getBoolean("dynamic_color", false); }
    public void setDynamicColor(boolean b) { sp.edit().putBoolean("dynamic_color", b).apply(); }
    /** Accent with Material You dynamic color applied when enabled (Android 12+). */
    public int accent(android.content.Context ctx) {
        if (dynamicColor() && android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(android.R.color.system_accent1_300);
            } catch (Exception ignored) {}
        }
        return accent();
    }

    public boolean markLastPlayed() { return sp.getBoolean("mark_last_played", true); }
    public void setMarkLastPlayed(boolean b) { sp.edit().putBoolean("mark_last_played", b).apply(); }
    public int newDays() { return sp.getInt("new_days", 7); }
    public void setNewDays(int d) { sp.edit().putInt("new_days", d).apply(); }
    public boolean watchHistory() { return sp.getBoolean("watch_history", true); }
    public void setWatchHistory(boolean b) { sp.edit().putBoolean("watch_history", b).apply(); }
    /** 0 = first frame, 1 = 10%, 2 = 25%, 3 = middle. */
    public int thumbPos() { return sp.getInt("thumb_pos", 0); }
    public void setThumbPos(int p) { sp.edit().putInt("thumb_pos", p).apply(); }

    public int ctrlTimeout() { return sp.getInt("ctrl_timeout", 4); }
    public void setCtrlTimeout(int s) { sp.edit().putInt("ctrl_timeout", s).apply(); }
    public boolean rememberBrightness() { return sp.getBoolean("remember_brightness", false); }
    public void setRememberBrightness(boolean b) { sp.edit().putBoolean("remember_brightness", b).apply(); }
    public float savedBrightness() { return sp.getFloat("saved_brightness", -1f); }
    public void setSavedBrightness(float f) { sp.edit().putFloat("saved_brightness", f).apply(); }

    public boolean audioFocus() { return sp.getBoolean("audio_focus", true); }
    public void setAudioFocus(boolean b) { sp.edit().putBoolean("audio_focus", b).apply(); }
    public boolean pauseDisconnect() { return sp.getBoolean("pause_disconnect", true); }
    public void setPauseDisconnect(boolean b) { sp.edit().putBoolean("pause_disconnect", b).apply(); }
    public boolean sysVolPanel() { return sp.getBoolean("sys_vol_panel", true); }
    public void setSysVolPanel(boolean b) { sp.edit().putBoolean("sys_vol_panel", b).apply(); }
    public boolean volBoost() { return sp.getBoolean("vol_boost", false); }
    public void setVolBoost(boolean b) { sp.edit().putBoolean("vol_boost", b).apply(); }

    /** Subtitle text size in sp (12–32). Migrates the old small/normal/large choice. */
    public int subTextSize() {
        if (!sp.contains("sub_text_size")) {
            int old = subtitleSize();
            return old == 0 ? 14 : old == 2 ? 21 : 17;
        }
        return sp.getInt("sub_text_size", 17);
    }
    public void setSubTextSize(int s) { sp.edit().putInt("sub_text_size", s).apply(); }
    public boolean subBold() { return sp.getBoolean("sub_bold", true); }
    public void setSubBold(boolean b) { sp.edit().putBoolean("sub_bold", b).apply(); }
    public boolean subBg() { return sp.getBoolean("sub_bg", false); }
    public void setSubBg(boolean b) { sp.edit().putBoolean("sub_bg", b).apply(); }
    /** 0 = Default, 1 = Sans, 2 = Serif, 3 = Monospace. */
    public int subFont() { return sp.getInt("sub_font", 0); }
    public void setSubFont(int f) { sp.edit().putInt("sub_font", f).apply(); }

    public void clearPositions() {
        android.content.SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("pos_")) e.remove(k);
        }
        e.apply();
    }
    public void resetAll() { sp.edit().clear().apply(); }

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int accentIndex() { return sp.getInt("accent", 2); }
    public void setAccentIndex(int i) { sp.edit().putInt("accent", i).remove("accent_argb").apply(); }
    public int accent() { int c = sp.getInt("accent_argb", 0); return c != 0 ? c : ACCENTS[Math.max(0, Math.min(accentIndex(), ACCENTS.length - 1))]; }
    public void setAccentColor(int argb) { sp.edit().putInt("accent_argb", argb).apply(); }
    /** Home icon style: 0 Aero Neon (outline on card), 1 Material You (filled), 2 Flat Minimal. */
    public int iconPack() { return sp.getInt("icon_pack", 0); }
    public void setIconPack(int i) { sp.edit().putInt("icon_pack", i).apply(); }

    public boolean darkTheme() { return sp.getBoolean("dark", true); }
    public void setDarkTheme(boolean d) { sp.edit().putBoolean("dark", d).apply(); }

    /** 0 small, 1 medium, 2 large */
    public int textSize() { return sp.getInt("text_size", 1); }
    public void setTextSize(int t) { sp.edit().putInt("text_size", t).apply(); }
    public float textScale() {
        switch (textSize()) { case 0: return 0.88f; case 2: return 1.16f; default: return 1f; }
    }

    public float defaultSpeed() { return sp.getFloat("def_speed", 1.0f); }
    public void setDefaultSpeed(float s) { sp.edit().putFloat("def_speed", s).apply(); }

    /** 0 fit, 1 fill, 2 stretch */
    public int defaultResize() { return sp.getInt("def_resize", 0); }
    public void setDefaultResize(int r) { sp.edit().putInt("def_resize", r).apply(); }

    /** 0 newest, 1 name, 2 duration, 3 size */
    public int sort() { return sp.getInt("sort", 0); }
    public void setSort(int s) { sp.edit().putInt("sort", s).apply(); }

    public int gridCols() { return sp.getInt("grid_cols", 2); }
    public void setGridCols(int c) { sp.edit().putInt("grid_cols", c).apply(); }

    public boolean listMode() { return sp.getBoolean("list_mode", false); }
    public void setListMode(boolean l) { sp.edit().putBoolean("list_mode", l).apply(); }

    // ---- resume positions ----
    public long resumePos(String key) { return sp.getLong("pos_" + key, 0); }
    public void setResumePos(String key, long pos) {
        if (pos <= 0) sp.edit().remove("pos_" + key).apply();
        else sp.edit().putLong("pos_" + key, pos).apply();
    }
    public void clearHistory() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("pos_")) e.remove(k);
        e.apply();
    }

    // ---- vault PIN ----
    public String pinHash() { return sp.getString("vault_pin", null); }
    public void setPinHash(String h) { sp.edit().putString("vault_pin", h).apply(); }
    // ---- player behaviour (PLAYit-style Video settings) ----
    public boolean showName() { return sp.getBoolean("show_name", true); }
    public void setShowName(boolean b) { sp.edit().putBoolean("show_name", b).apply(); }
    public int orientation() { return sp.getInt("orientation", 0); } // 0 auto, 1 landscape, 2 portrait
    public void setOrientation(int o) { sp.edit().putInt("orientation", o).apply(); }
    public boolean orientLock() { return sp.getBoolean("orient_lock", false); }
    public void setOrientLock(boolean b) { sp.edit().putBoolean("orient_lock", b).apply(); }
    public boolean fixedRatio() { return sp.getBoolean("fixed_ratio", true); }
    public void setFixedRatio(boolean b) { sp.edit().putBoolean("fixed_ratio", b).apply(); }
    public boolean showStatus() { return sp.getBoolean("show_status", false); }
    public void setShowStatus(boolean b) { sp.edit().putBoolean("show_status", b).apply(); }
    public boolean resumeOn() { return sp.getBoolean("resume_on", true); }
    public void setResumeOn(boolean b) { sp.edit().putBoolean("resume_on", b).apply(); }
    public boolean gesturesOn() { return sp.getBoolean("gestures_on", true); }
    public void setGesturesOn(boolean b) { sp.edit().putBoolean("gestures_on", b).apply(); }
    public boolean continuous() { return sp.getBoolean("continuous", true); }
    public void setContinuous(boolean b) { sp.edit().putBoolean("continuous", b).apply(); }
    public boolean doubleTapOn() { return sp.getBoolean("dtap_on", true); }
    public void setDoubleTapOn(boolean b) { sp.edit().putBoolean("dtap_on", b).apply(); }
    public int doubleTapSeconds() { return sp.getInt("dtap_sec", 10); }
    public void setDoubleTapSeconds(int s) { sp.edit().putInt("dtap_sec", s).apply(); }
    public boolean longPressOn() { return sp.getBoolean("lp_on", true); }
    public void setLongPressOn(boolean b) { sp.edit().putBoolean("lp_on", b).apply(); }
    public float longPressSpeed() { return sp.getFloat("lp_speed", 2.0f); }
    public void setLongPressSpeed(float f) { sp.edit().putFloat("lp_speed", f).apply(); }
    public boolean longPressVibrate() { return sp.getBoolean("lp_vib", true); }
    public void setLongPressVibrate(boolean b) { sp.edit().putBoolean("lp_vib", b).apply(); }
    public boolean autoPip() { return sp.getBoolean("auto_pip", false); }
    public void setAutoPip(boolean b) { sp.edit().putBoolean("auto_pip", b).apply(); }
    public int subtitleSize() { return sp.getInt("sub_size", 1); } // 0 small, 1 normal, 2 large
    public void setSubtitleSize(int s) { sp.edit().putInt("sub_size", s).apply(); }

    // ---- photo theme + hidden folders ----
    public String customBg() { return sp.getString("custom_bg", ""); }
    public void setCustomBg(String uri) { sp.edit().putString("custom_bg", uri).apply(); }
    public java.util.Set<String> hiddenFolders() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String raw = sp.getString("hidden_folders", "");
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\u0002")) if (!p.isEmpty()) out.add(p);
        }
        return out;
    }
    public void setHiddenFolders(java.util.Set<String> s) {
        sp.edit().putString("hidden_folders", String.join("\u0002", s)).apply();
    }

    public boolean fingerprintUnlock() { return sp.getBoolean("vault_fp", true); }
    public void setFingerprintUnlock(boolean b) { sp.edit().putBoolean("vault_fp", b).apply(); }

    // ---- extra vault unlock methods (password / pattern) ----
    public String pwdHash() { return sp.getString("vault_pwd", null); }
    public void setPwdHash(String h) {
        if (h == null) sp.edit().remove("vault_pwd").apply();
        else sp.edit().putString("vault_pwd", h).apply();
    }
    public String patternHash() { return sp.getString("vault_pattern", null); }
    public void setPatternHash(String h) {
        if (h == null) sp.edit().remove("vault_pattern").apply();
        else sp.edit().putString("vault_pattern", h).apply();
    }
    // ---- AI assistant (bring-your-own-key; keys stay on this device) ----
    public int assistantProvider() { return sp.getInt("ai_provider", 0); }
    public void setAssistantProvider(int p) { sp.edit().putInt("ai_provider", p).apply(); }
    public String assistantKey(int provider) {
        return sp.getString(provider == 0 ? "ai_key_gemini" : "ai_key_oai", "");
    }
    public void setAssistantKey(int provider, String k) {
        sp.edit().putString(provider == 0 ? "ai_key_gemini" : "ai_key_oai", k).apply();
    }
    public String assistantModel(int provider) {
        return sp.getString(provider == 0 ? "ai_model_gemini" : "ai_model_oai",
                provider == 0 ? "gemini-2.5-flash" : "gpt-4o-mini");
    }
    public void setAssistantModel(int provider, String m) {
        sp.edit().putString(provider == 0 ? "ai_model_gemini" : "ai_model_oai", m).apply();
    }
    public String assistantBaseUrl() { return sp.getString("ai_base_url", "https://api.openai.com/v1"); }
    // generic custom-AI credentials (any OpenAI-compatible endpoint)
    public String assistantKey() { return sp.getString("ai_key_custom", ""); }
    public void setAssistantKey(String k) { sp.edit().putString("ai_key_custom", k).apply(); }
    public String assistantModel() { return sp.getString("ai_model_custom", "gpt-4o-mini"); }
    public boolean updateAuto() { return sp.getBoolean("update_auto", true); }
    public void setUpdateAuto(boolean b) { sp.edit().putBoolean("update_auto", b).apply(); }
    public long lastUpdateCheck() { return sp.getLong("update_last_check", 0); }
    public void setLastUpdateCheck(long t) { sp.edit().putLong("update_last_check", t).apply(); }

    /** User-added AI Hub tiles, JSON array of {"n":name,"u":url}. */
    public String assistantHubCustom() { return sp.getString("ai_hub_custom", "[]"); }
    public void setAssistantHubCustom(String json) { sp.edit().putString("ai_hub_custom", json).apply(); }
    public boolean assistantSpeak() { return sp.getBoolean("ai_speak", false); }
    public void setAssistantSpeak(boolean b) { sp.edit().putBoolean("ai_speak", b).apply(); }
    public void setAssistantModel(String m) { sp.edit().putString("ai_model_custom", m).apply(); }
    public void setAssistantBaseUrl(String u) { sp.edit().putString("ai_base_url", u).apply(); }

    /** Last-used vault unlock method: 0 PIN, 1 password, 2 pattern, 3 biometric. */
    public int lockMethod() { return sp.getInt("vault_method", 0); }
    public void setLockMethod(int m) { sp.edit().putInt("vault_method", m).apply(); }

    // ---- watch history (newest first, "uri<US>name<US>timeMs" joined by <RS>) ----
    private static final String US = "\u0003";
    private static final String RS = "\u0002";

    public java.util.List<String[]> history() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp.getString("history", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String e : raw.split(RS)) {
            String[] p = e.split(US, -1);
            if (p.length == 3) out.add(p);
        }
        return out;
    }

    public void addHistory(String uri, String name) {
        java.util.List<String[]> cur = history();
        cur.removeIf(p -> p[0].equals(uri));
        cur.add(0, new String[]{uri, name, String.valueOf(System.currentTimeMillis())});
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(cur.size(), 12); i++) {
            if (i > 0) sb.append(RS);
            sb.append(cur.get(i)[0]).append(US).append(cur.get(i)[1]).append(US).append(cur.get(i)[2]);
        }
        sp.edit().putString("history", sb.toString()).apply();
    }

    public void clearHistoryEntries() { sp.edit().remove("history").apply(); }

    // ---- playlists ----
    public java.util.List<String> playlistNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = sp.getString("pl_names", "");
        if (raw != null && !raw.isEmpty()) {
            for (String n : raw.split(RS)) if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public void createPlaylist(String name) {
        java.util.List<String> names = playlistNames();
        if (names.contains(name)) return;
        names.add(name);
        sp.edit().putString("pl_names", String.join(RS, names)).apply();
    }

    public void deletePlaylist(String name) {
        java.util.List<String> names = playlistNames();
        names.remove(name);
        sp.edit().putString("pl_names", String.join(RS, names))
                .remove("pl_" + name).apply();
    }

    /** Entries as [uri, name] pairs, in play order. */
    public java.util.List<String[]> playlistItems(String name) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp.getString("pl_" + name, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String e : raw.split(RS)) {
            String[] p = e.split(US, -1);
            if (p.length == 2) out.add(p);
        }
        return out;
    }

    public void addToPlaylist(String name, String uri, String videoName) {
        java.util.List<String[]> items = playlistItems(name);
        for (String[] p : items) if (p[0].equals(uri)) return; // no duplicates
        items.add(new String[]{uri, videoName});
        savePlaylistItems(name, items);
    }

    public void removeFromPlaylist(String name, String uri) {
        java.util.List<String[]> items = playlistItems(name);
        items.removeIf(p -> p[0].equals(uri));
        savePlaylistItems(name, items);
    }

    private void savePlaylistItems(String name, java.util.List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(RS);
            sb.append(items.get(i)[0]).append(US).append(items.get(i)[1]);
        }
        sp.edit().putString("pl_" + name, sb.toString()).apply();
    }

    // ---- audio playlists (same shape, pla_ prefix) ----
    public java.util.List<String> audioPlaylistNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = sp.getString("pla_names", "");
        if (raw != null && !raw.isEmpty()) {
            for (String n : raw.split(RS)) if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public void createAudioPlaylist(String name) {
        java.util.List<String> names = audioPlaylistNames();
        if (names.contains(name)) return;
        names.add(name);
        sp.edit().putString("pla_names", String.join(RS, names)).apply();
    }

    public void deleteAudioPlaylist(String name) {
        java.util.List<String> names = audioPlaylistNames();
        names.remove(name);
        sp.edit().putString("pla_names", String.join(RS, names))
                .remove("pla_" + name).apply();
    }

    public java.util.List<String[]> audioPlaylistItems(String name) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp.getString("pla_" + name, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String e : raw.split(RS)) {
            String[] p = e.split(US, -1);
            if (p.length == 2) out.add(p);
        }
        return out;
    }

    public void addToAudioPlaylist(String name, String uri, String title) {
        java.util.List<String[]> items = audioPlaylistItems(name);
        for (String[] p : items) if (p[0].equals(uri)) return;
        items.add(new String[]{uri, title});
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(RS);
            sb.append(items.get(i)[0]).append(US).append(items.get(i)[1]);
        }
        sp.edit().putString("pla_" + name, sb.toString()).apply();
    }

    public void removeFromAudioPlaylist(String name, String uri) {
        java.util.List<String[]> items = audioPlaylistItems(name);
        items.removeIf(p -> p[0].equals(uri));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(RS);
            sb.append(items.get(i)[0]).append(US).append(items.get(i)[1]);
        }
        sp.edit().putString("pla_" + name, sb.toString()).apply();
    }

    // ---- formatting helpers ----
    public static String fmtSpeedShort(float f) {
        return (f == (int) f ? ((int) f) + ".0" : String.valueOf(f)) + "x";
    }

    public static String fmtDuration(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + ":" + String.format("%02d", m) + ":" + String.format("%02d", sec);
        return m + ":" + String.format("%02d", sec);
    }

    public static String fmtSize(long bytes) {
        if (bytes <= 0) return "—";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
