package com.muklashdev.tmplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TM Player home, PLAYit-style layout:
 * bottom tabs Video / Music / Vault / Me; the Video section carries its own
 * top tabs (Video / Folder / Playlist) and the Me page is a feature hub.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 100;
    private static final int REQ_PERM_AUDIO = 104;
    private static final int REQ_BG = 103;
    private static final int REQ_TREE = 101;
    private static final int REQ_DELETE = 102;

    private static List<VideoItem> cacheAll;
    private static List<AudioItem> cacheAudio;

    private Prefs prefs;
    private int cBg, cCard, cCard2, cText, cDim, cAccent;

    private FrameLayout content;
    private EditText searchBox;
    private TextView headerAction, titleText, subtitleText;
    private final View[] navItems = new View[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabels = new TextView[4];

    private int currentTab = 0;                 // 0 video, 1 music, 2 vault, 3 me
    private int videoSub = 0;                   // 0 videos, 1 folders, 2 playlists
    private int musicSub = 0;                   // 0 all, 1 playlist, 2 folder, 3 album, 4 artist
    private String musicGroup = null;           // open music group / playlist name
    private String mePage = "home";             // home, settings, history, trash, converter
    private int settingsSection = 0;            // 0 root, 1..5 sections

    private List<VideoItem> allVideos = new ArrayList<>();
    private List<AudioItem> allAudio = new ArrayList<>();
    private List<VideoItem> baseList = new ArrayList<>();
    private String baseTitle = null;
    private String currentPlaylist = null;
    private VideoAdapter gridAdapter;
    private VideoAdapter listAdapter;

    private boolean vaultUnlocked = false;
    private final StringBuilder pinBuffer = new StringBuilder();
    private String pinFirst = null;
    private boolean pinConfirmStep = false;
    private TextView pinTitle;
    private final TextView[] pinDots = new TextView[6];

    private VideoItem pendingDelete;
    private boolean pendingAfterVault;
    private TrashBin.Entry pendingTrashEntry;

    private LinearLayout miniPlayer;
    private TextView miniTitle, miniArtist;
    private ImageButton miniPlay;

    private final ExecutorService exec = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setTheme(prefs.isDark(this) ? R.style.AppThemeDark : R.style.AppThemeLight);
        setContentView(R.layout.activity_main);
        Edge.pad(findViewById(R.id.main_root));
        computeColors();

        content = findViewById(R.id.content);
        searchBox = findViewById(R.id.search_box);
        headerAction = findViewById(R.id.header_action);
        titleText = findViewById(R.id.title_text);
        subtitleText = findViewById(R.id.subtitle_text);
        int[] navIds = {R.id.nav_videos, R.id.nav_music, R.id.nav_vault, R.id.nav_me};
        for (int i = 0; i < 4; i++) {
            navItems[i] = findViewById(navIds[i]);
            navIcons[i] = (ImageView) ((ViewGroup) navItems[i]).getChildAt(0);
            navLabels[i] = (TextView) ((ViewGroup) navItems[i]).getChildAt(1);
            final int tab = i;
            navItems[i].setOnClickListener(v -> showTab(tab));
        }
        styleChrome();
        applyCustomBg();

        miniPlayer = findViewById(R.id.mini_player);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        miniPlay = findViewById(R.id.mini_play);
        miniPlayer.setBackgroundColor(cCard);
        miniTitle.setTextColor(cText);
        miniArtist.setTextColor(cDim);
        ((ImageView) ((ViewGroup) miniPlayer).getChildAt(0)).setColorFilter(cAccent);
        miniPlay.setColorFilter(cText);
        ((ImageButton) findViewById(R.id.mini_next)).setColorFilter(cText);
        miniPlay.setOnClickListener(v -> MusicPlayer.get(this).toggle());
        findViewById(R.id.mini_next).setOnClickListener(v -> MusicPlayer.get(this).next());
        miniPlayer.setOnClickListener(v -> startActivity(new Intent(this, MusicActivity.class)));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (currentTab == 0 && videoSub == 0) applyFilter();
                else if (currentTab == 1) showMusic();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        headerAction.setOnClickListener(v -> onHeaderAction());

        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            VideoItem v = new VideoItem();
            v.uriStr = in.getData().toString();
            v.name = displayName(in.getData());
            List<VideoItem> single = new ArrayList<>();
            single.add(v);
            PlayQueue.set(single, 0);
            startActivity(new Intent(this, PlayerActivity.class));
        }

        if (hasMediaPermission()) {
            loadLibrary();
            if (!hasAudioPermission()) {
                requestPermissions(new String[]{audioPermission()}, REQ_PERM_AUDIO);
            }
        } else {
            requestPermissions(new String[]{mediaPermission(), audioPermission()}, REQ_PERM);
            showMessage("Allow media access to see your library.");
        }
        showTab(0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        vaultUnlocked = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicPlayer.get(this).setListener(new MusicPlayer.Listener() {
            @Override public void onTrackChanged() { ui.post(() -> refreshMini()); }
            @Override public void onPlayStateChanged() { ui.post(() -> refreshMini()); }
            @Override public void onProgress(int posMs, int durMs) {}
        });
        refreshMini();
    }

    private void refreshMini() {
        MusicPlayer mp = MusicPlayer.get(this);
        AudioItem a = mp.current();
        if (a == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(a.title);
        miniArtist.setText(a.artist == null || a.artist.isEmpty() ? "TM Player Music" : a.artist);
        miniPlay.setImageResource(mp.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERM) {
            boolean videoOk = false;
            for (int i = 0; i < perms.length; i++) {
                if (mediaPermission().equals(perms[i])
                        && results.length > i && results[i] == PackageManager.PERMISSION_GRANTED) {
                    videoOk = true;
                }
            }
            if (videoOk) loadLibrary();
            else showMessage("Video access denied — TM Player cannot show your library.");
        } else if (req == REQ_PERM_AUDIO) {
            loadLibrary();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_BG && result == RESULT_OK && data != null && data.getData() != null) {
            Uri bg = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(bg,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.setCustomBg(bg.toString());
            toast("Photo theme set");
            recreate();
            return;
        }
        if (req == REQ_TREE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            showMessage("Scanning folder…");
            exec.execute(() -> {
                List<VideoItem> found = MediaLib.scanTree(this, tree);
                ui.post(() -> {
                    baseList = found;
                    baseTitle = "Picked folder";
                    currentPlaylist = null;
                    showVideoGrid(false);
                    toast(found.size() + " videos found");
                });
            });
        }
        if (req == REQ_DELETE && pendingDelete != null) {
            if (result == RESULT_OK) {
                allVideos.remove(pendingDelete);
                cacheAll = allVideos;
                toast(pendingTrashEntry != null ? "Moved to Recycle Bin" : "Original deleted");
            } else {
                if (pendingTrashEntry != null) {
                    TrashBin.deleteForever(this, pendingTrashEntry);
                    toast("Kept the original — bin copy removed");
                } else {
                    toast(pendingAfterVault ? "Kept the original too" : "Delete cancelled");
                }
            }
            pendingDelete = null;
            pendingAfterVault = false;
            pendingTrashEntry = null;
            refreshGridData();
        }
    }

    // ------------------------------------------------------------ theming

    private void computeColors() {
        boolean d = prefs.isDark(this);
        boolean hc = d && prefs.highContrast();
        cBg = hc ? 0xFF000000 : d ? 0xFF070B14 : 0xFFF4F7FB;
        cCard = hc ? 0xFF0B0B0B : d ? 0xFF101828 : 0xFFFFFFFF;
        cCard2 = hc ? 0xFF161616 : d ? 0xFF182236 : 0xFFE8EEF6;
        cText = d ? 0xFFF2F6FC : 0xFF0B1220;
        cDim = d ? 0xFF9DADC6 : 0xFF42536B;
        cAccent = prefs.accent(this);
    }

    private void styleChrome() {
        findViewById(R.id.bottom_nav).setBackgroundColor(cCard);
        titleText.setTextColor(cText);
        subtitleText.setTextColor(cDim);
        headerAction.setTextColor(cAccent);
        Drawable bg = getDrawable(R.drawable.card_bg).mutate();
        bg.setTint(cCard2);
        searchBox.setBackground(bg);
        searchBox.setTextColor(cText);
        searchBox.setHintTextColor(cDim);
        updateNavStyles();
    }

    private void updateNavStyles() {
        for (int i = 0; i < 4; i++) {
            int col = i == currentTab ? cAccent : cDim;
            navIcons[i].setColorFilter(col);
            navLabels[i].setTextColor(col);
        }
        searchBox.setVisibility(currentTab <= 1 ? View.VISIBLE : View.GONE);
        searchBox.setHint(currentTab == 1 ? "Search music…" : "Search videos…");
        headerAction.setText(currentTab == 2 ? (vaultUnlocked ? "Lock" : "") : "");
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ------------------------------------------------------------ library

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasMediaPermission() {
        return checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Library view: all videos minus the folders hidden in Settings → Library. */
    private List<VideoItem> lib() {
        java.util.Set<String> hidden = prefs.hiddenFolders();
        if (hidden.isEmpty()) return allVideos;
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : allVideos) {
            if (!hidden.contains(v.folderPath)) out.add(v);
        }
        return out;
    }

    private void loadLibrary() {
        if (cacheAll != null) {
            allVideos = cacheAll;
            allAudio = cacheAudio != null ? cacheAudio : new ArrayList<>();
            baseList = lib();
            if (currentTab == 0) showVideoSection();
            else if (currentTab == 1) showMusic();
            return;
        }
        showMessage("Scanning storage — internal, SD card and USB…");
        exec.execute(() -> {
            List<VideoItem> found = MediaLib.scanAllVolumes(this);
            MediaLib.sort(found, prefs.sort());
            List<AudioItem> audio = MediaLib.scanAllAudio(this);
            ui.post(() -> {
                cacheAll = found;
                cacheAudio = audio;
                allVideos = found;
                allAudio = audio;
                baseList = found;
                baseTitle = null;
                if (currentTab == 0) showVideoSection();
                else if (currentTab == 1) showMusic();
            });
        });
    }

    private void rescan() {
        cacheAll = null;
        cacheAudio = null;
        loadLibrary();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "Video";
    }

    // ------------------------------------------------------------ tabs

    private void showTab(int tab) {
        if (tab != 2) vaultUnlocked = false;
        currentTab = tab;
        updateNavStyles();
        switch (tab) {
            case 0: showVideoSection(); break;
            case 1: showMusic(); break;
            case 2: showVault(); break;
            case 3: mePage = "home"; showMe(); break;
        }
    }

    // ------------------------------------------------------------ Quick Settings (home shortcut sheet)

    private static int sortFieldOf(int sort) { // 0 Title, 1 Duration, 2 Date, 3 Size
        switch (sort) {
            case 2: case 3: return 0;
            case 6: case 7: return 1;
            case 4: case 5: return 3;
            default: return 2;
        }
    }

    private static boolean sortAscOf(int sort) {
        return sort == 1 || sort == 2 || sort == 5 || sort == 7;
    }

    private static int sortCombined(int field, boolean asc) {
        switch (field) {
            case 0: return asc ? 2 : 3;  // Title
            case 1: return asc ? 7 : 6;  // Duration
            case 3: return asc ? 5 : 4;  // Size
            default: return asc ? 1 : 0; // Date
        }
    }

    private void openQuickSettings() {
        final int[] qView = {videoSub == 3 ? 0 : videoSub == 1 ? 1 : 2}; // 0 Tree, 1 Folders, 2 Videos
        final boolean[] qList = {prefs.listMode()};
        final int[] qField = {sortFieldOf(prefs.sort())};
        final boolean[] qAsc = {sortAscOf(prefs.sort())};
        final boolean[] qF = {prefs.fldDuration(), prefs.fldFolderDuration(), prefs.fldExtension(),
                prefs.fldPath(), prefs.fldProgress(), prefs.fldResolution(), prefs.fldSize(),
                prefs.fldThumbnail()};
        final String[] fieldNames = {"Duration", "Folder duration", "Extension", "Path",
                "Played progress", "Resolution", "Size", "Thumbnail"};
        final String[][] dirLabels = {{"A–Z", "Z–A"}, {"Shortest first", "Longest first"},
                {"Oldest first", "Newest first"}, {"Smallest first", "Largest first"}};
        final AlertDialog[] dlg = new AlertDialog[1];

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(18), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            col.removeAllViews();
            col.addView(bigText("Quick Settings"));
            col.addView(sectionLabel("Media view mode"));
            col.addView(segmented(new String[]{"Tree", "Folders", "Videos"}, qView[0],
                    i -> { qView[0] = i; render[0].run(); }, false));
            col.addView(sectionLabel("Media Layout"));
            col.addView(segmented(new String[]{"List", "Grid"}, qList[0] ? 0 : 1,
                    i -> { qList[0] = i == 0; render[0].run(); }, false));
            col.addView(sectionLabel("Sort"));
            String[] glyphs = {"T", "◷", "▦", "⇅"};
            String[] names = {"Title", "Duration", "Date", "Size"};
            LinearLayout circles = new LinearLayout(this);
            circles.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 4; i++) {
                final int fi = i;
                LinearLayout cellL = new LinearLayout(this);
                cellL.setOrientation(LinearLayout.VERTICAL);
                cellL.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView circ = new TextView(this);
                circ.setText(glyphs[i]);
                circ.setTextSize(i == 0 ? 19f : 17f);
                circ.setTypeface(null, android.graphics.Typeface.BOLD);
                circ.setGravity(Gravity.CENTER);
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                boolean sel = qField[0] == i;
                gd.setColor(sel ? cAccent : cCard2);
                circ.setBackground(gd);
                circ.setTextColor(sel ? 0xFFFFFFFF : cText);
                cellL.addView(circ, new LinearLayout.LayoutParams(dp(50), dp(50)));
                TextView lab = new TextView(this);
                lab.setText(names[i]);
                lab.setTextSize(11.5f);
                lab.setTextColor(sel ? cAccent : cDim);
                lab.setPadding(0, dp(5), 0, 0);
                cellL.addView(lab);
                cellL.setOnClickListener(v -> { qField[0] = fi; render[0].run(); });
                circles.addView(cellL, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            col.addView(circles);
            col.addView(segmented(dirLabels[qField[0]], qAsc[0] ? 0 : 1,
                    i -> { qAsc[0] = i == 0; render[0].run(); }, false));
            col.addView(sectionLabel("Fields"));
            for (int r = 0; r < 4; r++) {
                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setPadding(0, dp(4), 0, dp(4));
                for (int c = 0; c < 2; c++) {
                    final int idx = r * 2 + c;
                    boolean on = qF[idx];
                    TextView chip = new TextView(this);
                    chip.setText((on ? "✓ " : "○ ") + fieldNames[idx]);
                    chip.setTextSize(13.5f);
                    chip.setTextColor(on ? cText : cDim);
                    chip.setPadding(dp(13), dp(9), dp(13), dp(9));
                    GradientDrawable cg = new GradientDrawable();
                    cg.setCornerRadius(dp(20));
                    if (on) {
                        cg.setColor((cAccent & 0x00FFFFFF) | 0x33000000);
                        cg.setStroke(dp(1), cAccent);
                    } else {
                        cg.setColor(cCard2);
                    }
                    chip.setBackground(cg);
                    chip.setOnClickListener(v -> { qF[idx] = !qF[idx]; render[0].run(); });
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    if (c == 0) clp.rightMargin = dp(8);
                    rowL.addView(chip, clp);
                }
                col.addView(rowL);
            }
            col.addView(row(0, "Rescan library", "Look for new videos and music again", "›",
                    v -> {
                        if (dlg[0] != null) dlg[0].dismiss();
                        cacheAll = null;
                        cacheAudio = null;
                        loadLibrary();
                    }));
        };
        render[0].run();

        dlg[0] = new AlertDialog.Builder(this)
                .setView(sv)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    prefs.setListMode(qList[0]);
                    videoSub = qView[0] == 0 ? 3 : qView[0] == 1 ? 1 : 0;
                    prefs.setSort(sortCombined(qField[0], qAsc[0]));
                    MediaLib.sort(allVideos, prefs.sort());
                    prefs.setFldDuration(qF[0]);
                    prefs.setFldFolderDuration(qF[1]);
                    prefs.setFldExtension(qF[2]);
                    prefs.setFldPath(qF[3]);
                    prefs.setFldProgress(qF[4]);
                    prefs.setFldResolution(qF[5]);
                    prefs.setFldSize(qF[6]);
                    prefs.setFldThumbnail(qF[7]);
                    baseList = lib();
                    baseTitle = null;
                    currentPlaylist = null;
                    showVideoSection();
                    toast("Quick Settings applied");
                })
                .create();
        dlg[0].show();
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < Prefs.SORT_NAMES.length; i++) {
            menu.getMenu().add(0, i, i, (prefs.sort() == i ? "✓ " : "") + Prefs.SORT_NAMES[i]);
        }
        menu.getMenu().add(0, 10, 10, prefs.listMode() ? "View: Grid (side by side)" : "View: List (rows)");
        menu.getMenu().add(0, 11, 11, "Grid columns: " + prefs.gridCols());
        menu.getMenu().add(0, 12, 12, "Rescan library");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < Prefs.SORT_NAMES.length) {
                prefs.setSort(id);
                MediaLib.sort(allVideos, id);
                baseList = lib();
                baseTitle = null;
                currentPlaylist = null;
                videoSub = 0;
                showVideoSection();
            } else if (id == 10) {
                prefs.setListMode(!prefs.listMode());
                showVideoSection();
            } else if (id == 11) {
                prefs.setGridCols(prefs.gridCols() == 2 ? 3 : 2);
                showVideoSection();
            } else if (id == 12) {
                rescan();
            }
            return true;
        });
        menu.show();
    }

    private void onHeaderAction() {
        if (currentTab == 2 && vaultUnlocked) {
            vaultUnlocked = false;
            showVault();
            updateNavStyles();
        }
    }

    private void showMessage(String msg) {
        content.removeAllViews();
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(cDim);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(32), dp(32), dp(32), dp(32));
        t.setTextSize(14.5f);
        content.addView(t);
    }

    private View segmented(String[] labels, int selected, OnPick onPick, boolean scrollable) {
        LinearLayout rowL = new LinearLayout(this);
        rowL.setOrientation(LinearLayout.HORIZONTAL);
        rowL.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.graphics.Paint segPaint = new android.graphics.Paint();
        segPaint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f, getResources().getDisplayMetrics()));
        segPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(6), dp(10), dp(6), 0);
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(14.5f);
            t.setMaxLines(1);
            if (i == selected) {
                t.setTextColor(cAccent);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                t.setTextColor(cDim);
            }
            item.addView(t);
            View bar = new View(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(30), dp(3));
            blp.topMargin = dp(7);
            bar.setLayoutParams(blp);
            bar.setBackgroundColor(i == selected ? cAccent : 0x00000000);
            item.addView(bar);
            final int idx = i;
            item.setOnClickListener(v -> onPick.pick(idx));
            if (scrollable) {
                int w = (int) segPaint.measureText(labels[i]) + dp(24);
                rowL.addView(item, new LinearLayout.LayoutParams(
                        w, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                rowL.addView(item, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
        }
        if (scrollable) {
            rowL.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(rowL);
            return hs;
        }
        return rowL;
    }

    // ------------------------------------------------------------ video section

    private void showVideoSection() {
        switch (videoSub) {
            case 1: showFolders(); break;
            case 2: showPlaylists(); break;
            case 3: showTree(); break;
            default:
                baseList = lib();
                baseTitle = null;
                currentPlaylist = null;
                showVideoGrid(true);
                break;
        }
    }

    private View videoHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        View seg = segmented(new String[]{"Video", "Folder", "Playlist"}, videoSub, i -> {
            videoSub = i;
            baseTitle = null;
            currentPlaylist = null;
            showVideoSection();
        }, false);
        bar.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView sortBtn = new TextView(this);
        sortBtn.setText("⇅");
        sortBtn.setTextColor(cText);
        sortBtn.setTextSize(20f);
        sortBtn.setPadding(dp(12), dp(8), dp(6), dp(8));
        sortBtn.setOnClickListener(v -> openQuickSettings());
        bar.addView(sortBtn);
        TextView viewBtn = new TextView(this);
        viewBtn.setText(prefs.listMode() ? "⊞" : "☰");
        viewBtn.setTextColor(cText);
        viewBtn.setTextSize(19f);
        viewBtn.setPadding(dp(6), dp(8), dp(14), dp(8));
        viewBtn.setOnClickListener(v -> {
            prefs.setListMode(!prefs.listMode());
            showVideoSection();
        });
        bar.addView(viewBtn);
        return bar;
    }

    private List<VideoItem> displayed() {
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : baseList) {
            if (q.isEmpty() || v.name.toLowerCase(Locale.ROOT).contains(q)) out.add(v);
        }
        return out;
    }

    private void applyFilter() {
        List<VideoItem> d = displayed();
        if (gridAdapter != null) { gridAdapter.setItems(d); gridAdapter.notifyDataSetChanged(); }
        if (listAdapter != null) { listAdapter.setItems(d); listAdapter.notifyDataSetChanged(); }
    }

    private void refreshGridData() {
        if (currentTab == 0) applyFilter();
    }

    private void showVideoGrid(boolean withHeader) {
        content.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (withHeader) wrap.addView(videoHeader());
        if (baseTitle != null) {
            TextView back = new TextView(this);
            back.setText("←  " + baseTitle + "   (" + baseList.size() + " videos)");
            back.setTextColor(cAccent);
            back.setTextSize(14.5f);
            back.setPadding(dp(16), dp(12), dp(16), dp(4));
            back.setOnClickListener(v -> {
                currentPlaylist = null;
                baseTitle = null;
                showVideoSection();
            });
            wrap.addView(back);
        }
        if (withHeader && baseTitle == null) addHistoryStrip(wrap);
        if (baseList.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(hasMediaPermission()
                    ? "No videos found yet.\nPlug in a pendrive or SD card and tap Rescan (Sort menu)."
                    : "Allow video access to see your library.");
            t.setTextColor(cDim);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(32), dp(40), dp(32), dp(32));
            wrap.addView(t);
            content.addView(wrap);
            return;
        }
        if (prefs.listMode()) {
            ListView lv = new ListView(this);
            lv.setDivider(null);
            boolean group = baseTitle == null && (prefs.sort() == 0 || prefs.sort() == 1);
            listAdapter = new VideoAdapter(displayed(), true, group);
            lv.setAdapter(listAdapter);
            lv.setOnItemClickListener((p, v, pos, id) -> {
                VideoItem it = listAdapter.flatVideo(pos);
                if (it != null) playFrom(listAdapter.items, Math.max(0, listAdapter.items.indexOf(it)));
            });
            lv.setOnItemLongClickListener((p, v, pos, id) -> {
                VideoItem it = listAdapter.flatVideo(pos);
                if (it != null) { showVideoActions(it); return true; }
                return false;
            });
            wrap.addView(lv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            GridView grid = new GridView(this);
            grid.setNumColumns(prefs.gridCols());
            grid.setPadding(dp(6), dp(6), dp(6), dp(6));
            grid.setHorizontalSpacing(dp(4));
            grid.setVerticalSpacing(dp(4));
            gridAdapter = new VideoAdapter(displayed(), false, false);
            grid.setAdapter(gridAdapter);
            grid.setOnItemClickListener((p, v, pos, id) -> playFrom(gridAdapter.items, pos));
            grid.setOnItemLongClickListener((p, v, pos, id) -> {
                showVideoActions(gridAdapter.items.get(pos));
                return true;
            });
            wrap.addView(grid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        content.addView(wrap);
    }

    private void addHistoryStrip(LinearLayout wrap) {
        List<String[]> hist = prefs.history();
        if (hist.isEmpty()) return;
        LinearLayout hrowHead = new LinearLayout(this);
        hrowHead.setOrientation(LinearLayout.HORIZONTAL);
        hrowHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView hl = new TextView(this);
        hl.setText("History");
        hl.setTextColor(cText);
        hl.setTextSize(14.5f);
        hl.setTypeface(null, android.graphics.Typeface.BOLD);
        hl.setPadding(dp(14), dp(8), dp(14), dp(2));
        hrowHead.addView(hl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView clearBtn = new ImageView(this);
        clearBtn.setImageResource(R.drawable.ic_trash);
        clearBtn.setColorFilter(cDim);
        clearBtn.setPadding(dp(10), dp(6), dp(14), dp(6));
        clearBtn.setOnClickListener(v -> confirm("Clear history?",
                "Watch history and resume positions will be cleared.",
                () -> {
                    prefs.clearHistory();
                    prefs.clearHistoryEntries();
                    toast("History cleared");
                    showVideoSection();
                }));
        hrowHead.addView(clearBtn, new LinearLayout.LayoutParams(dp(38), dp(32)));
        wrap.addView(hrowHead);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout hrow = new LinearLayout(this);
        hrow.setOrientation(LinearLayout.HORIZONTAL);
        hrow.setPadding(dp(10), dp(2), dp(10), dp(8));
        for (String[] e : hist) {
            String hUri = e[0], hName = e[1];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setBackgroundColor(0xFF1A2438);
            card.addView(th, new LinearLayout.LayoutParams(dp(128), dp(72)));
            TextView nm = new TextView(this);
            nm.setText(hName);
            nm.setTextColor(cDim);
            nm.setTextSize(10.5f);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(nm, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(10);
            card.setLayoutParams(clp);
            for (VideoItem v : allVideos) {
                if (v.uriStr.equals(hUri)) { loadThumbInto(th, v); break; }
            }
            card.setOnClickListener(x -> playHistoryEntry(hUri, hName));
            hrow.addView(card);
        }
        hs.addView(hrow);
        wrap.addView(hs);
    }

    private void playHistoryEntry(String uri, String name) {
        for (int i = 0; i < allVideos.size(); i++) {
            if (allVideos.get(i).uriStr.equals(uri)) {
                playFrom(allVideos, i);
                return;
            }
        }
        VideoItem single = new VideoItem();
        single.uriStr = uri;
        single.name = name;
        List<VideoItem> one = new ArrayList<>();
        one.add(single);
        playFrom(one, 0);
    }

    private void playFrom(List<VideoItem> list, int pos) {
        PlayQueue.set(list, pos);
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private final class VideoAdapter extends BaseAdapter {
        List<VideoItem> items;
        final boolean listMode;
        final boolean groupByMonth;
        final String lastUri;
        final long newCutoffSec;
        List<Object> flat = new ArrayList<>(); // String month headers + VideoItems (list mode)

        VideoAdapter(List<VideoItem> items, boolean listMode, boolean groupByMonth) {
            this.items = items;
            this.listMode = listMode;
            this.groupByMonth = groupByMonth;
            java.util.List<String[]> h = prefs.history();
            this.lastUri = prefs.markLastPlayed() && !h.isEmpty() ? h.get(0)[0] : null;
            this.newCutoffSec = prefs.newDays() > 0
                    ? System.currentTimeMillis() / 1000 - prefs.newDays() * 86400L
                    : Long.MAX_VALUE;
            rebuildFlat();
        }

        void setItems(List<VideoItem> it) { items = it; rebuildFlat(); }

        private void rebuildFlat() {
            flat = new ArrayList<>();
            if (!listMode) return;
            String lastMonth = null;
            for (VideoItem v : items) {
                if (groupByMonth && v.dateModified > 0) {
                    String m = monthLabel(v.dateModified);
                    if (!m.equals(lastMonth)) {
                        flat.add(m);
                        lastMonth = m;
                    }
                }
                flat.add(v);
            }
        }

        VideoItem flatVideo(int pos) {
            if (!listMode) return pos < items.size() ? items.get(pos) : null;
            Object o = pos < flat.size() ? flat.get(pos) : null;
            return o instanceof VideoItem ? (VideoItem) o : null;
        }

        @Override public int getCount() { return listMode ? flat.size() : items.size(); }
        @Override public Object getItem(int i) { return listMode ? flat.get(i) : items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) {
            return listMode && flat.get(pos) instanceof String ? 0 : 1;
        }

        private String monthLabel(long seconds) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(seconds * 1000);
            String m = new SimpleDateFormat("MMM", Locale.ENGLISH).format(c.getTime());
            int year = c.get(java.util.Calendar.YEAR);
            int nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            return year == nowYear ? m : m + " " + year;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            float scale = prefs.textScale();
            if (listMode) {
                Object o = flat.get(pos);
                if (o instanceof String) {
                    TextView hdr = convert instanceof TextView && "hdr".equals(convert.getTag())
                            ? (TextView) convert : new TextView(MainActivity.this);
                    hdr.setTag("hdr");
                    hdr.setText((String) o);
                    hdr.setTextColor(cText);
                    hdr.setTextSize(15f);
                    hdr.setTypeface(null, android.graphics.Typeface.BOLD);
                    hdr.setPadding(dp(14), dp(14), dp(14), dp(4));
                    return hdr;
                }
                VideoItem v = (VideoItem) o;
                View row = convert != null && !(convert instanceof TextView) ? convert
                        : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_video_row, parent, false);
                ImageView thumb = row.findViewById(R.id.row_thumb);
                TextView qual = row.findViewById(R.id.row_quality);
                TextView dur = row.findViewById(R.id.row_duration);
                TextView title = row.findViewById(R.id.row_title);
                TextView meta = row.findViewById(R.id.row_meta);
                TextView chip = row.findViewById(R.id.row_chip);
                TextView more = row.findViewById(R.id.row_more);
                title.setText(v.name);
                title.setTextColor(cText);
                title.setTextSize(14.5f * scale);
                title.setVisibility(prefs.showName() ? View.VISIBLE : View.GONE);
                qual.setText(v.quality());
                qual.setVisibility(v.quality().isEmpty() ? View.GONE : View.VISIBLE);
                dur.setText(Prefs.fmtDuration(v.duration));
                dur.setVisibility(prefs.fldDuration() ? View.VISIBLE : View.GONE);
                String metaTxt = metaFor(v, true);
                if (lastUri != null && lastUri.equals(v.uriStr)) {
                    metaTxt = "▶ Last played  •  " + metaTxt;
                }
                meta.setText(metaTxt);
                meta.setTextColor(cDim);
                bindProgress(row.findViewById(R.id.row_progress_bg),
                        row.findViewById(R.id.row_progress), v);
                TextView newB = row.findViewById(R.id.row_new);
                boolean isNew = v.dateModified > newCutoffSec;
                newB.setVisibility(isNew ? View.VISIBLE : View.GONE);
                if (isNew) newB.setText("NEW");
                chip.setText(v.fromVault ? "Private Vault"
                        : (v.folderName != null ? v.folderName : "Video"));
                more.setTextColor(cDim);
                more.setOnClickListener(x -> showVideoActions(v));
                loadThumbInto(thumb, v);
                return row;
            }
            VideoItem v = items.get(pos);
            View cell = convert != null ? convert
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_video, parent, false);
            ImageView thumb = cell.findViewById(R.id.thumb);
            TextView dur = cell.findViewById(R.id.duration_badge);
            TextView qual = cell.findViewById(R.id.quality_badge);
            TextView name = cell.findViewById(R.id.video_name);
            TextView meta = cell.findViewById(R.id.video_meta);
            name.setText(v.name);
            name.setTextColor(cText);
            name.setTextSize(12.5f * scale);
            name.setVisibility(prefs.showName() ? View.VISIBLE : View.GONE);
            meta.setText(metaFor(v, false));
            meta.setTextColor(cDim);
            meta.setTextSize(11f * scale);
            dur.setText(Prefs.fmtDuration(v.duration));
            dur.setVisibility(prefs.fldDuration() ? View.VISIBLE : View.GONE);
            bindProgress(cell.findViewById(R.id.cell_progress_bg),
                    cell.findViewById(R.id.cell_progress), v);
            TextView newC = cell.findViewById(R.id.cell_new);
            if (v.dateModified > newCutoffSec) {
                newC.setText("NEW");
                newC.setVisibility(View.VISIBLE);
            } else if (lastUri != null && lastUri.equals(v.uriStr)) {
                newC.setText("▶ LAST");
                newC.setVisibility(View.VISIBLE);
            } else {
                newC.setVisibility(View.GONE);
            }
            qual.setText(v.quality());
            qual.setVisibility(v.quality().isEmpty() ? View.GONE : View.VISIBLE);
            loadThumbInto(thumb, v);
            return cell;
        }
    }

    /** Meta line under a video, composed from the Quick Settings field choices. */
    private String metaFor(VideoItem v, boolean listMode) {
        List<String> parts = new ArrayList<>();
        if (prefs.fldResolution() && v.width > 0 && v.height > 0) {
            parts.add(v.width + "×" + v.height);
        } else if (!v.quality().isEmpty()) {
            parts.add(v.quality());
        }
        if (prefs.fldSize()) parts.add(Prefs.fmtSize(v.size));
        if (prefs.fldExtension() && v.name != null) {
            int dot = v.name.lastIndexOf('.');
            if (dot > 0 && dot < v.name.length() - 1) {
                parts.add(v.name.substring(dot + 1).toUpperCase(java.util.Locale.US));
            }
        }
        String s = String.join(" | ", parts);
        if (listMode && prefs.fldPath()) {
            String p = v.folderPath != null ? v.folderPath
                    : (v.folderName != null ? v.folderName : "");
            if (!p.isEmpty()) s = s.isEmpty() ? p : s + "  •  " + p;
        }
        return s;
    }

    /** Watched-progress bar under a thumbnail (from the saved resume position). */
    private void bindProgress(View bg, View fill, VideoItem v) {
        if (bg == null || fill == null) return;
        long resume = prefs.resumePos(v.uriStr);
        if (!prefs.fldProgress() || resume <= 0 || v.duration <= 0) {
            bg.setVisibility(View.GONE);
            return;
        }
        bg.setVisibility(View.VISIBLE);
        fill.setBackgroundColor(prefs.accent());
        double frac = Math.min(1.0, (double) resume / (double) v.duration);
        bg.post(() -> {
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = Math.max(dp(3), (int) (bg.getWidth() * frac));
            fill.setLayoutParams(lp);
        });
    }

    private void loadThumbInto(ImageView iv, VideoItem v) {
        iv.setImageBitmap(null);
        iv.setBackgroundColor(0xFF1A2438);
        iv.setTag(v.uriStr);
        if (!prefs.fldThumbnail()) {
            iv.setImageResource(R.drawable.ic_nav_videos);
            iv.setColorFilter(cDim);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(18), dp(18), dp(18), dp(18));
            return;
        }
        iv.clearColorFilter();
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setPadding(0, 0, 0, 0);
        exec.execute(() -> {
            Bitmap b = MediaLib.loadThumb(MainActivity.this, v, 320, 200);
            if (b == null) return;
            ui.post(() -> {
                if (v.uriStr.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    private interface OnPick { void pick(int i); }

    // ------------------------------------------------------------ folders & playlists

    private void showFolders() {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(videoHeader());

        try {
            List<String> roots = storageRoots();
            StringBuilder sb = new StringBuilder("Storage: ");
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) sb.append("  •  ");
                sb.append(roots.get(i));
            }
            col.addView(dimText(sb.toString()));
        } catch (Exception ignored) {}

        col.addView(row(0, "Browse any folder…", "SD card, USB pendrive — any folder", "›",
                v -> {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(pick, REQ_TREE);
                }));

        Map<String, List<VideoItem>> byFolder = new LinkedHashMap<>();
        for (VideoItem v : lib()) {
            String key = v.folderPath != null ? v.folderPath : (v.folderName != null ? v.folderName : "Unknown");
            byFolder.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        col.addView(sectionLabel("Folders on this device"));
        if (!prefs.hiddenFolders().isEmpty()) {
            col.addView(dimText(prefs.hiddenFolders().size()
                    + " folder(s) hidden — manage them in Me → Settings → Library."));
        }
        if (byFolder.isEmpty()) {
            col.addView(dimText("No folders yet — allow media access and rescan."));
        }
        for (Map.Entry<String, List<VideoItem>> e : byFolder.entrySet()) {
            List<VideoItem> items = e.getValue();
            long total = 0;
            for (VideoItem v : items) total += v.size;
            String folderName = items.get(0).folderName != null ? items.get(0).folderName : "Folder";
            col.addView(row(R.drawable.ic_nav_folders, folderName,
                    folderSub(items), "›",
                    v -> {
                        baseList = items;
                        baseTitle = folderName;
                        currentPlaylist = null;
                        showVideoGrid(true);
                    }));
        }
        sv.addView(col);
        content.addView(sv);
    }

    private String folderSub(List<VideoItem> items) {
        long total = 0, totalDur = 0;
        for (VideoItem v : items) { total += v.size; totalDur += v.duration; }
        String s = items.size() + " videos  •  " + Prefs.fmtSize(total);
        if (prefs.fldFolderDuration() && totalDur > 0) {
            s += "  •  " + Prefs.fmtDuration(totalDur);
        }
        return s;
    }

    /** Tree view: folders grouped under their storage root (internal / SD / USB). */
    private void showTree() {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(videoHeader());
        col.addView(row(0, "Browse any folder…", "SD card, USB pendrive — any folder", "›",
                v -> {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(pick, REQ_TREE);
                }));
        Map<String, List<VideoItem>> byFolder = new LinkedHashMap<>();
        for (VideoItem v : lib()) {
            String key = v.folderPath != null ? v.folderPath
                    : (v.folderName != null ? v.folderName : "Unknown");
            byFolder.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        List<String> roots = new ArrayList<>();
        try { roots = storageRoots(); } catch (Exception ignored) {}
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (String root : roots) {
            List<Map.Entry<String, List<VideoItem>>> under = new ArrayList<>();
            for (Map.Entry<String, List<VideoItem>> e : byFolder.entrySet()) {
                if (e.getKey().startsWith(root)) under.add(e);
            }
            if (under.isEmpty()) continue;
            col.addView(sectionLabel(root));
            for (Map.Entry<String, List<VideoItem>> e : under) {
                placed.add(e.getKey());
                addFolderRow(col, e.getValue());
            }
        }
        boolean anyOther = false;
        for (Map.Entry<String, List<VideoItem>> e : byFolder.entrySet()) {
            if (placed.contains(e.getKey())) continue;
            if (!anyOther) {
                col.addView(sectionLabel("Other locations"));
                anyOther = true;
            }
            addFolderRow(col, e.getValue());
        }
        if (byFolder.isEmpty()) {
            col.addView(dimText("No folders yet — allow media access and rescan."));
        }
        sv.addView(col);
        content.addView(sv);
    }

    private void addFolderRow(LinearLayout col, List<VideoItem> items) {
        String folderName = items.get(0).folderName != null ? items.get(0).folderName : "Folder";
        col.addView(row(R.drawable.ic_nav_folders, folderName, folderSub(items), "›",
                v -> {
                    baseList = items;
                    baseTitle = folderName;
                    currentPlaylist = null;
                    showVideoGrid(true);
                }));
    }

    private void showPlaylists() {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(videoHeader());
        for (String name : prefs.playlistNames()) {
            int count = prefs.playlistItems(name).size();
            View prow = row(R.drawable.ic_queue, name, count + " videos", "›",
                    v -> openPlaylist(name));
            prow.setOnLongClickListener(v -> {
                confirm("Delete playlist?", name + " (videos themselves are not deleted)",
                        () -> { prefs.deletePlaylist(name); showPlaylists(); });
                return true;
            });
            col.addView(prow);
        }
        if (prefs.playlistNames().isEmpty()) {
            col.addView(dimText("No playlists yet. Long-press any video → Add to Playlist."));
        }
        col.addView(row(0, "＋ Create playlist", "Make a new video playlist", "",
                v -> {
                    EditText input = new EditText(this);
                    input.setHint("Playlist name");
                    input.setTextColor(cText);
                    new AlertDialog.Builder(this)
                            .setTitle("New playlist")
                            .setView(input)
                            .setPositiveButton("Create", (d, w) -> {
                                String n = input.getText().toString().trim();
                                if (n.isEmpty()) { toast("Name is empty"); return; }
                                prefs.createPlaylist(n);
                                showPlaylists();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }));
        sv.addView(col);
        content.addView(sv);
    }

    private List<String> storageRoots() {
        List<String> names = new ArrayList<>();
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            for (android.os.storage.StorageVolume vol : sm.getStorageVolumes()) {
                File dir = vol.getDirectory();
                if (dir == null) continue;
                String label = vol.getDescription(this);
                names.add((label == null ? dir.getName() : label)
                        + (vol.isRemovable() ? " (removable)" : ""));
            }
        } catch (Exception ignored) {}
        return names;
    }

    private void openPlaylist(String name) {
        List<VideoItem> items = new ArrayList<>();
        for (String[] e : prefs.playlistItems(name)) {
            VideoItem found = null;
            for (VideoItem v : allVideos) if (v.uriStr.equals(e[0])) { found = v; break; }
            if (found == null) {
                found = new VideoItem();
                found.uriStr = e[0];
                found.name = e[1];
            }
            items.add(found);
        }
        currentPlaylist = name;
        baseList = items;
        baseTitle = "Playlist: " + name;
        showVideoGrid(true);
    }

    // ------------------------------------------------------------ video actions

    private void showVideoActions(VideoItem v) {
        String[] actions;
        if (v.fromVault) {
            actions = new String[]{"Play", "Export back to gallery", "Remove from vault"};
        } else if (currentPlaylist != null) {
            actions = new String[]{"Play", "Move to Private Vault", "Convert to Audio",
                    "Move to Recycle Bin", "Remove from playlist", "Details", "Share", "Delete"};
        } else {
            actions = new String[]{"Play", "Move to Private Vault", "Add to Playlist",
                    "Convert to Audio", "Move to Recycle Bin", "Details", "Share", "Delete"};
        }
        new AlertDialog.Builder(this)
                .setTitle(v.name)
                .setItems(actions, (d, which) -> {
                    if (v.fromVault) {
                        if (which == 0) {
                            List<VideoItem> one = new ArrayList<>();
                            one.add(v);
                            playFrom(one, 0);
                        } else if (which == 1) {
                            exportFromVault(v);
                        } else if (which == 2) {
                            confirm("Remove from vault?", "The video file will be deleted permanently.",
                                    () -> { Vault.remove(v); showVault(); });
                        }
                        return;
                    }
                    if (currentPlaylist != null) {
                        switch (which) {
                            case 0: {
                                List<VideoItem> d2 = displayed();
                                playFrom(d2, Math.max(0, d2.indexOf(v)));
                                break;
                            }
                            case 1: moveToVault(v); break;
                            case 2: convertToAudio(v); break;
                            case 3: moveToRecycleBin(v); break;
                            case 4:
                                prefs.removeFromPlaylist(currentPlaylist, v.uriStr);
                                baseList.remove(v);
                                toast("Removed from playlist");
                                showVideoGrid(true);
                                break;
                            case 5: showDetails(v); break;
                            case 6: share(v); break;
                            case 7: confirm("Delete this video?", v.name, () -> tryDelete(v, false)); break;
                        }
                        return;
                    }
                    switch (which) {
                        case 0: {
                            List<VideoItem> d2 = displayed();
                            playFrom(d2, Math.max(0, d2.indexOf(v)));
                            break;
                        }
                        case 1: moveToVault(v); break;
                        case 2: choosePlaylist(v); break;
                        case 3: convertToAudio(v); break;
                        case 4: moveToRecycleBin(v); break;
                        case 5: showDetails(v); break;
                        case 6: share(v); break;
                        case 7: confirm("Delete this video?", v.name, () -> tryDelete(v, false)); break;
                    }
                })
                .show();
    }

    private void choosePlaylist(VideoItem v) {
        List<String> names = prefs.playlistNames();
        List<String> options = new ArrayList<>(names);
        options.add("＋ New playlist…");
        new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == names.size()) {
                        EditText input = new EditText(this);
                        input.setHint("Playlist name");
                        input.setTextColor(cText);
                        new AlertDialog.Builder(this)
                                .setTitle("New playlist")
                                .setView(input)
                                .setPositiveButton("Create & add", (d2, w2) -> {
                                    String n = input.getText().toString().trim();
                                    if (n.isEmpty()) { toast("Name is empty"); return; }
                                    prefs.createPlaylist(n);
                                    prefs.addToPlaylist(n, v.uriStr, v.name);
                                    toast("Added to " + n);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        prefs.addToPlaylist(names.get(which), v.uriStr, v.name);
                        toast("Added to " + names.get(which));
                    }
                })
                .show();
    }

    private void convertToAudio(VideoItem v) {
        toast("Extracting audio…");
        exec.execute(() -> {
            ConvertAudio.Result r = ConvertAudio.convert(this, v);
            ui.post(() -> {
                toast(r.message);
                if (r.ok) cacheAudio = null;
            });
        });
    }

    private void moveToRecycleBin(VideoItem v) {
        toast("Copying to Recycle Bin…");
        exec.execute(() -> {
            TrashBin.Entry e = TrashBin.add(this, v);
            ui.post(() -> {
                if (e == null) { toast("Could not copy to Recycle Bin"); return; }
                if (v.fromVault) {
                    Vault.remove(v);
                    toast("Moved to Recycle Bin");
                    showVault();
                    return;
                }
                try {
                    getContentResolver().delete(Uri.parse(v.uriStr), null, null);
                    allVideos.remove(v);
                    baseList.remove(v);
                    cacheAll = allVideos;
                    toast("Moved to Recycle Bin");
                    refreshGridData();
                } catch (RecoverableSecurityException rse) {
                    pendingDelete = v;
                    pendingTrashEntry = e;
                    try {
                        startIntentSenderForResult(
                                rse.getUserAction().getActionIntent().getIntentSender(),
                                REQ_DELETE, null, 0, 0, 0);
                    } catch (Exception ex) {
                        TrashBin.deleteForever(this, e);
                        pendingDelete = null;
                        pendingTrashEntry = null;
                        toast("Delete needs your confirmation in the system dialog");
                    }
                } catch (Exception ex) {
                    TrashBin.deleteForever(this, e);
                    toast("Could not remove the original");
                }
            });
        });
    }

    private void moveToVault(VideoItem v) {
        if (prefs.pinHash() == null) {
            toast("Set a vault PIN first");
            showTab(2);
            return;
        }
        showMessage("Moving to vault — copying the file…");
        exec.execute(() -> {
            java.io.File stored = Vault.copyInto(this, v);
            ui.post(() -> {
                if (stored == null) {
                    toast("Could not copy the video");
                    showVideoSection();
                    return;
                }
                try {
                    getContentResolver().delete(Uri.parse(v.uriStr), null, null);
                    allVideos.remove(v);
                    cacheAll = allVideos;
                    toast("Moved to Private Vault");
                    showVideoSection();
                } catch (RecoverableSecurityException e) {
                    pendingDelete = v;
                    pendingAfterVault = true;
                    showVideoSection();
                    try {
                        startIntentSenderForResult(
                                e.getUserAction().getActionIntent().getIntentSender(),
                                REQ_DELETE, null, 0, 0, 0);
                    } catch (Exception ex) {
                        toast("Vault copy saved; the original is waiting for your confirmation");
                    }
                } catch (Exception e) {
                    toast("Copy saved in vault; original could not be removed");
                    showVideoSection();
                }
            });
        });
    }

    private void tryDelete(VideoItem v, boolean fromVaultFlow) {
        try {
            getContentResolver().delete(Uri.parse(v.uriStr), null, null);
            allVideos.remove(v);
            baseList.remove(v);
            cacheAll = allVideos;
            toast("Deleted");
            refreshGridData();
        } catch (RecoverableSecurityException e) {
            pendingDelete = v;
            pendingAfterVault = fromVaultFlow;
            try {
                startIntentSenderForResult(
                        e.getUserAction().getActionIntent().getIntentSender(),
                        REQ_DELETE, null, 0, 0, 0);
            } catch (Exception ex) {
                toast("Delete needs your confirmation in the system dialog");
            }
        } catch (Exception e) {
            toast("Delete failed: " + e.getMessage());
        }
    }

    private void exportFromVault(VideoItem v) {
        exec.execute(() -> {
            boolean ok = Vault.exportBack(this, v);
            ui.post(() -> {
                if (ok) {
                    Vault.remove(v);
                    rescan();
                    showVault();
                    toast("Exported to Movies/TM Player");
                } else {
                    toast("Export failed");
                }
            });
        });
    }

    private void showDetails(VideoItem v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Duration: ").append(Prefs.fmtDuration(v.duration)).append('\n');
        sb.append("Size: ").append(Prefs.fmtSize(v.size)).append('\n');
        if (v.width > 0) sb.append("Resolution: ").append(v.width).append('×').append(v.height).append('\n');
        if (v.dateModified > 0) sb.append("Modified: ").append(
                new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                        .format(new Date(v.dateModified * 1000))).append('\n');
        sb.append("Folder: ").append(v.folderName != null ? v.folderName : "—").append('\n');
        sb.append("Path: ").append(v.folderPath != null ? v.folderPath : v.uriStr);
        new AlertDialog.Builder(this)
                .setTitle(v.name)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void share(VideoItem v) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/*");
        send.putExtra(Intent.EXTRA_STREAM, Uri.parse(v.uriStr));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share video"));
    }

    private void share(Uri uri, String type) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }

    // ------------------------------------------------------------ vault + PIN pad

    private void showVault() {
        pinBuffer.setLength(0);
        pinFirst = null;
        pinConfirmStep = false;
        content.removeAllViews();
        String pin = prefs.pinHash();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        if (pin == null) {
            col.addView(bigText("Private Vault"));
            col.addView(dimText("Set a PIN to protect your private videos. "
                    + "Videos moved here are hidden from the gallery and other apps."));
            buildPinPad(col, "Create PIN to protect your privacy", true);
        } else if (!vaultUnlocked) {
            col.addView(bigText("Private Vault"));
            buildPinPad(col, "Enter your PIN", false);
            if (prefs.fingerprintUnlock() && fingerprintAvailable()) {
                col.addView(button("Use fingerprint", v -> promptFingerprint()));
            }
        } else {
            List<VideoItem> items = Vault.list(this);
            col.addView(bigText(items.size() + " private videos"));
            if (items.isEmpty()) {
                col.addView(dimText("Nothing here yet. Long-press any video in the library "
                        + "and choose \u201CMove to Private Vault\u201D."));
            }
            ListView lv = new ListView(this);
            lv.setDivider(null);
            VideoAdapter ad = new VideoAdapter(items, true, false);
            lv.setAdapter(ad);
            lv.setOnItemClickListener((p, v, pos, id) -> {
                List<VideoItem> one = new ArrayList<>();
                one.add(items.get(pos));
                playFrom(one, 0);
            });
            lv.setOnItemLongClickListener((p, v, pos, id) -> {
                showVideoActions(items.get(pos));
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56) * Math.max(1, items.size()));
            col.addView(lv, lp);
        }
        sv.addView(col);
        content.addView(sv);
    }

    private void buildPinPad(LinearLayout col, String title, boolean create) {
        pinTitle = new TextView(this);
        pinTitle.setText(title);
        pinTitle.setTextColor(cText);
        pinTitle.setTextSize(16f);
        pinTitle.setGravity(Gravity.CENTER);
        pinTitle.setPadding(dp(16), dp(22), dp(16), dp(6));
        col.addView(pinTitle);

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 6; i++) {
            TextView d = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
            lp.setMargins(dp(6), dp(10), dp(6), dp(10));
            d.setLayoutParams(lp);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(cCard2);
            d.setBackground(g);
            pinDots[i] = d;
            dots.addView(d);
        }
        col.addView(dots);

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✓"}};
        for (String[] rowKeys : keys) {
            LinearLayout kr = new LinearLayout(this);
            kr.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : rowKeys) {
                TextView key = new TextView(this);
                key.setText(k);
                key.setTextColor(cText);
                key.setTextSize(26f);
                key.setGravity(Gravity.CENTER);
                key.setPadding(0, dp(16), 0, dp(16));
                key.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                key.setOnClickListener(v -> onPinKey(k, create));
                kr.addView(key);
            }
            col.addView(kr);
        }
    }

    private void updatePinDots() {
        for (int i = 0; i < 6; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            if (i < pinBuffer.length()) {
                g.setColor(cAccent);
            } else {
                g.setColor(cCard2);
                if (i == pinBuffer.length()) g.setStroke(dp(2), cAccent);
            }
            pinDots[i].setBackground(g);
        }
    }

    private void onPinKey(String k, boolean create) {
        if ("⌫".equals(k)) {
            if (pinBuffer.length() > 0) pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        } else if ("✓".equals(k)) {
            submitPin(create);
            return;
        } else {
            if (pinBuffer.length() < 6) pinBuffer.append(k);
        }
        updatePinDots();
    }

    private void submitPin(boolean create) {
        String entered = pinBuffer.toString();
        if (create) {
            if (!pinConfirmStep) {
                if (entered.length() < 4) { toast("PIN must be at least 4 digits"); return; }
                pinFirst = entered;
                pinConfirmStep = true;
                pinBuffer.setLength(0);
                pinTitle.setText("Repeat your PIN");
                updatePinDots();
            } else {
                if (entered.equals(pinFirst)) {
                    prefs.setPinHash(Vault.hashPin(entered));
                    vaultUnlocked = true;
                    toast("Vault PIN set");
                    updateNavStyles();
                    showVault();
                } else {
                    toast("PINs do not match — start again");
                    pinConfirmStep = false;
                    pinFirst = null;
                    pinBuffer.setLength(0);
                    pinTitle.setText("Create PIN to protect your privacy");
                    updatePinDots();
                }
            }
        } else {
            String pin = prefs.pinHash();
            if (pin != null && Vault.hashPin(entered).equals(pin)) {
                vaultUnlocked = true;
                updateNavStyles();
                showVault();
            } else {
                toast("Wrong PIN");
                pinBuffer.setLength(0);
                updatePinDots();
            }
        }
    }

    private boolean fingerprintAvailable() {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            android.hardware.biometrics.BiometricManager bm =
                    (android.hardware.biometrics.BiometricManager)
                            getSystemService(android.content.Context.BIOMETRIC_SERVICE);
            return bm != null && bm.canAuthenticate()
                    == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void promptFingerprint() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(this)
                            .setTitle("Unlock Private Vault")
                            .setSubtitle("TM Player")
                            .setNegativeButton("Use PIN", getMainExecutor(), (d, w) -> {})
                            .build();
            prompt.authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            vaultUnlocked = true;
                            updateNavStyles();
                            showVault();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode != 13 && errorCode != 10) toast(String.valueOf(errString));
                        }
                    });
        } catch (Exception e) {
            toast("Fingerprint not available");
        }
    }

    // ------------------------------------------------------------ music

    private void showMusic() {
        content.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(segmented(new String[]{"All Songs", "Playlist", "Folder", "Album", "Artist"},
                musicSub, i -> { musicSub = i; musicGroup = null; showMusic(); }, true));
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (musicGroup != null) {
            List<AudioItem> songs = musicGroupSongs();
            songs.removeIf(a -> !q.isEmpty()
                    && !a.title.toLowerCase(Locale.ROOT).contains(q));
            wrap.addView(backRow(musicGroupTitle(), v -> { musicGroup = null; showMusic(); }));
            wrap.addView(shuffleRow(songs));
            wrap.addView(songsList(songs, musicSub == 1));
            content.addView(wrap);
            return;
        }
        switch (musicSub) {
            case 1: {
                LinearLayout col = musicColumn(wrap);
                for (String name : prefs.audioPlaylistNames()) {
                    int count = prefs.audioPlaylistItems(name).size();
                    View prow = row(R.drawable.ic_queue, name, count + " songs", "›", v -> {
                        musicGroup = "pl:" + name;
                        showMusic();
                    });
                    prow.setOnLongClickListener(v -> {
                        confirm("Delete playlist?", name, () -> {
                            prefs.deleteAudioPlaylist(name);
                            showMusic();
                        });
                        return true;
                    });
                    col.addView(prow);
                }
                if (prefs.audioPlaylistNames().isEmpty()) {
                    col.addView(dimText("No music playlists yet. Long-press a song → Add to Playlist."));
                }
                col.addView(row(0, "＋ Create playlist", "Make a new music playlist", "", v -> {
                    EditText input = new EditText(this);
                    input.setHint("Playlist name");
                    input.setTextColor(cText);
                    new AlertDialog.Builder(this)
                            .setTitle("New playlist")
                            .setView(input)
                            .setPositiveButton("Create", (d, w) -> {
                                String n = input.getText().toString().trim();
                                if (n.isEmpty()) { toast("Name is empty"); return; }
                                prefs.createAudioPlaylist(n);
                                showMusic();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }));
                break;
            }
            case 2: case 3: case 4: {
                LinearLayout col = musicColumn(wrap);
                Map<String, Integer> groups = new LinkedHashMap<>();
                for (AudioItem a : allAudio) {
                    String g = musicGroupKey(a, musicSub);
                    groups.merge(g, 1, Integer::sum);
                }
                if (groups.isEmpty()) col.addView(dimText("No music found on this device."));
                for (Map.Entry<String, Integer> e : groups.entrySet()) {
                    col.addView(row(R.drawable.ic_nav_music, e.getKey(),
                            e.getValue() + " songs", "›", v -> {
                                musicGroup = "g:" + musicSub + ":" + e.getKey();
                                showMusic();
                            }));
                }
                break;
            }
            default: {
                List<AudioItem> songs = new ArrayList<>();
                for (AudioItem a : allAudio) {
                    if (q.isEmpty() || a.title.toLowerCase(Locale.ROOT).contains(q)
                            || (a.artist != null && a.artist.toLowerCase(Locale.ROOT).contains(q))) {
                        songs.add(a);
                    }
                }
                wrap.addView(shuffleRow(songs));
                if (songs.isEmpty()) {
                    wrap.addView(dimText(hasMediaPermission() ? "No music found on this device."
                            : "Allow media access to see your music."));
                } else {
                    wrap.addView(songsList(songs, false));
                }
                break;
            }
        }
        content.addView(wrap);
    }

    private LinearLayout musicColumn(LinearLayout wrap) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    private String musicGroupKey(AudioItem a, int sub) {
        String v = sub == 2 ? a.folderName : sub == 3 ? a.album : a.artist;
        return v == null || v.isEmpty() ? "<unknown>" : v;
    }

    private String musicGroupTitle() {
        if (musicGroup == null) return "";
        if (musicGroup.startsWith("pl:")) return musicGroup.substring(3);
        String[] p = musicGroup.split(":", 3);
        return p.length == 3 ? p[2] : musicGroup;
    }

    private List<AudioItem> musicGroupSongs() {
        List<AudioItem> out = new ArrayList<>();
        if (musicGroup == null) return out;
        if (musicGroup.startsWith("pl:")) {
            String name = musicGroup.substring(3);
            for (String[] e : prefs.audioPlaylistItems(name)) {
                AudioItem found = null;
                for (AudioItem a : allAudio) if (a.uriStr.equals(e[0])) { found = a; break; }
                if (found == null) {
                    found = new AudioItem();
                    found.uriStr = e[0];
                    found.title = e[1];
                    found.artist = "";
                }
                out.add(found);
            }
            return out;
        }
        String[] p = musicGroup.split(":", 3);
        int sub = Integer.parseInt(p[1]);
        String key = p[2];
        for (AudioItem a : allAudio) if (musicGroupKey(a, sub).equals(key)) out.add(a);
        return out;
    }

    private View shuffleRow(List<AudioItem> songs) {
        TextView shuffle = new TextView(this);
        shuffle.setText("▶   Shuffle all (" + songs.size() + ")");
        shuffle.setTextColor(cAccent);
        shuffle.setTextSize(15f);
        shuffle.setTypeface(null, android.graphics.Typeface.BOLD);
        shuffle.setPadding(dp(16), dp(12), dp(16), dp(8));
        shuffle.setOnClickListener(v -> {
            if (songs.isEmpty()) return;
            MusicPlayer mp = MusicPlayer.get(this);
            mp.shuffle = true;
            mp.playQueue(new ArrayList<>(songs), new java.util.Random().nextInt(songs.size()));
            startActivity(new Intent(this, MusicActivity.class));
        });
        return shuffle;
    }

    private View songsList(List<AudioItem> songs, boolean inPlaylist) {
        ListView lv = new ListView(this);
        lv.setDivider(null);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return songs.size(); }
            @Override public Object getItem(int i) { return songs.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                View rowV = convert != null ? convert
                        : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_row, parent, false);
                AudioItem a = songs.get(pos);
                ImageView icon = rowV.findViewById(R.id.row_icon);
                icon.setImageResource(R.drawable.ic_nav_music);
                icon.setColorFilter(cAccent);
                icon.getLayoutParams().width = dp(30);
                icon.getLayoutParams().height = dp(30);
                TextView title = rowV.findViewById(R.id.row_title);
                title.setText(a.title);
                title.setTextColor(cText);
                title.setTextSize(14.5f * prefs.textScale());
                TextView sub = rowV.findViewById(R.id.row_sub);
                sub.setText((pos + 1) + ".  " + (a.artist == null || a.artist.isEmpty() ? "<unknown>" : a.artist)
                        + "  •  " + Prefs.fmtDuration(a.duration));
                sub.setTextColor(cDim);
                ((TextView) rowV.findViewById(R.id.row_value)).setText("⋮");
                return rowV;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            MusicPlayer mp = MusicPlayer.get(this);
            mp.playQueue(new ArrayList<>(songs), pos);
            refreshMini();
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            showSongActions(songs.get(pos), inPlaylist);
            return true;
        });
        return lv;
    }

    private void showSongActions(AudioItem a, boolean inPlaylist) {
        String plName = musicGroup != null && musicGroup.startsWith("pl:") ? musicGroup.substring(3) : null;
        String[] acts = inPlaylist && plName != null
                ? new String[]{"Play", "Remove from playlist", "Share"}
                : new String[]{"Play", "Add to Playlist", "Share"};
        new AlertDialog.Builder(this)
                .setTitle(a.title)
                .setItems(acts, (d, w) -> {
                    if (w == 0) {
                        List<AudioItem> one = new ArrayList<>();
                        one.add(a);
                        MusicPlayer.get(this).playQueue(one, 0);
                        refreshMini();
                    } else if (w == 1 && inPlaylist && plName != null) {
                        prefs.removeFromAudioPlaylist(plName, a.uriStr);
                        showMusic();
                    } else if (w == 1) {
                        chooseAudioPlaylist(a);
                    } else {
                        share(Uri.parse(a.uriStr), "audio/*");
                    }
                })
                .show();
    }

    private void chooseAudioPlaylist(AudioItem a) {
        List<String> names = prefs.audioPlaylistNames();
        List<String> options = new ArrayList<>(names);
        options.add("＋ New playlist…");
        new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == names.size()) {
                        EditText input = new EditText(this);
                        input.setHint("Playlist name");
                        input.setTextColor(cText);
                        new AlertDialog.Builder(this)
                                .setTitle("New playlist")
                                .setView(input)
                                .setPositiveButton("Create & add", (d2, w2) -> {
                                    String n = input.getText().toString().trim();
                                    if (n.isEmpty()) { toast("Name is empty"); return; }
                                    prefs.createAudioPlaylist(n);
                                    prefs.addToAudioPlaylist(n, a.uriStr, a.title);
                                    toast("Added to " + n);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        prefs.addToAudioPlaylist(names.get(which), a.uriStr, a.title);
                        toast("Added to " + names.get(which));
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ Me hub

    private void showMe() {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        switch (mePage) {
            case "settings": buildSettings(col); break;
            case "theme": buildThemePage(col); break;
            case "media": buildMediaPage(col); break;
            case "history": buildHistoryPage(col); break;
            case "trash": buildTrashPage(col); break;
            case "converter": buildConverterPage(col); break;
            case "clean": buildCleanPage(col); break;
            default: buildMeHome(col); break;
        }
        sv.addView(col);
        content.addView(sv);
    }

    private void buildMeHome(LinearLayout col) {
        // brand card (PLAYit's top card slot — no coins, no VIP, no ads)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setCornerRadius(dp(16));
        cg.setColor(cCard);
        card.setBackground(cg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(14), dp(14), dp(14), dp(6));
        card.setLayoutParams(clp);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_brand);
        card.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(12), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText("TM Player");
        t1.setTextColor(cText);
        t1.setTextSize(17f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        tc.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("Turbo Media Player • by MD. Muklash Mia");
        t2.setTextColor(cDim);
        t2.setTextSize(12f);
        tc.addView(t2);
        card.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chip = new TextView(this);
        chip.setText("ADS FREE");
        chip.setTextColor(cAccent);
        chip.setTextSize(11f);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable chg = new GradientDrawable();
        chg.setCornerRadius(dp(20));
        chg.setStroke(dp(1), cAccent);
        chip.setBackground(chg);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        card.addView(chip);
        col.addView(card);

        // feature tiles, PLAYit-style grid
        col.addView(tileRow(new Object[][]{
                {R.drawable.ic_headphone, "MP3 Converter", (Runnable) () -> { mePage = "converter"; showMe(); }},
                {R.drawable.ic_nav_vault, "Privacy", (Runnable) () -> showTab(2)},
                {R.drawable.ic_queue, "Playlists", (Runnable) () -> { videoSub = 2; showTab(0); }},
                {R.drawable.ic_nav_settings, "Theme", (Runnable) () -> { mePage = "theme"; showMe(); }},
        }));
        col.addView(tileRow(new Object[][]{
                {R.drawable.ic_nav_folders, "Media Manage", (Runnable) () -> { mePage = "media"; showMe(); }},
                {R.drawable.ic_history, "History", (Runnable) () -> { mePage = "history"; showMe(); }},
                {R.drawable.ic_clean, "Clean", (Runnable) () -> { mePage = "clean"; cleanScanned = false; showMe(); }},
                {R.drawable.ic_trash, "Recycle Bin", (Runnable) () -> { mePage = "trash"; showMe(); }},
        }));

        col.addView(sectionLabel("More"));
        col.addView(row(R.drawable.ic_nav_settings, "Settings", "Appearance, playback, library, privacy", "›",
                v -> { mePage = "settings"; settingsSection = 0; showMe(); }));
        long trashSize = TrashBin.totalSize(this);
        int trashCount = TrashBin.list(this).size();
        col.addView(row(R.drawable.ic_trash, "Recycle Bin",
                trashCount + " items  •  " + Prefs.fmtSize(trashSize), "›",
                v -> { mePage = "trash"; showMe(); }));
        col.addView(row(R.drawable.ic_share, "Share TM Player", "Send the GitHub page to a friend", "›",
                v -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT,
                            "TM Player — Turbo Media Player (ads-free, open source): "
                                    + "https://github.com/Muklash-Dev/TM-Player");
                    startActivity(Intent.createChooser(s, "Share TM Player"));
                }));
        col.addView(row(R.drawable.ic_person, "Help & Feedback", "Telegram: @muklash_dev", "›",
                v -> openUrl("https://t.me/muklash_dev")));
        col.addView(row(R.drawable.ic_history, "Rate / Star on GitHub", "github.com/Muklash-Dev/TM-Player", "›",
                v -> openUrl("https://github.com/Muklash-Dev/TM-Player")));
        col.addView(row(R.drawable.ic_person, "About", "Developer and app info", "›",
                v -> showAbout()));
    }

    private View tileRow(Object[][] defs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(10), dp(8), dp(10), dp(2));
        for (Object[] d : defs) {
            int icon = (int) d[0];
            String label = (String) d[1];
            Runnable run = (Runnable) d[2];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(2), dp(6), dp(2), dp(6));
            FrameLayout box = new FrameLayout(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(cCard);
            box.setBackground(bg);
            box.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(54)));
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(cAccent);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(27), dp(27));
            ilp.gravity = Gravity.CENTER;
            iv.setLayoutParams(ilp);
            box.addView(iv);
            tile.addView(box);
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(cText);
            tv.setTextSize(11.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(5), 0, 0);
            tile.addView(tv);
            tile.setOnClickListener(v -> run.run());
            r.addView(tile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return r;
    }

    private void cleanCache() {
        exec.execute(() -> {
            long before = dirSize(getCacheDir());
            deleteRecursive(getCacheDir());
            long freed = before - dirSize(getCacheDir());
            ui.post(() -> toast(freed > 0
                    ? "Cleaned " + Prefs.fmtSize(freed) + " of cache"
                    : "Already clean — no junk found"));
        });
    }

    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += dirSize(k);
        return s;
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.equals(getCacheDir())) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("No browser found");
        }
    }

    // ------------------------------------------------------------ Theme page

    private static final class ThemeDef {
        final String name;
        final boolean dark;
        final int accent;
        ThemeDef(String n, boolean d, int a) { name = n; dark = d; accent = a; }
    }

    private void buildThemePage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        col.addView(bigText("Theme"));
        List<ThemeDef> themes = new ArrayList<>();
        themes.add(new ThemeDef("Dark Colour", true, 0xFF19C8E8));
        themes.add(new ThemeDef("Light Colour", false, 0xFF19C8E8));
        themes.add(new ThemeDef("Ocean Blue", true, 0xFF3B82F6));
        themes.add(new ThemeDef("Purple Night", true, 0xFF9B5CF6));
        themes.add(new ThemeDef("Emerald", true, 0xFF22C07A));
        themes.add(new ThemeDef("Sunset", true, 0xFFF97316));
        themes.add(new ThemeDef("Rose", true, 0xFFEC4899));
        themes.add(new ThemeDef("Golden", true, 0xFFF5B921));

        // Customize card first (PLAYit puts it first too)
        LinearLayout gridHost = new LinearLayout(this);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        gridHost.setPadding(dp(10), dp(4), dp(10), dp(4));
        List<View> cards = new ArrayList<>();
        View customize = themeCard("Customize Theme", 0xFF3A3A3A, 0xFF232323, false, true,
                v -> { mePage = "settings"; settingsSection = 1; showMe(); });
        cards.add(customize);
        for (ThemeDef t : themes) {
            boolean sel = prefs.darkTheme() == t.dark && prefs.accent() == t.accent;
            int top = blend(t.accent, t.dark ? 0xFF0A0F1C : 0xFFFFFFFF, t.dark ? 0.45f : 0.25f);
            int bottom = t.dark ? 0xFF0A0F1C : 0xFFE8EEF6;
            cards.add(themeCard(t.name, top, bottom, sel, false, v -> {
                prefs.setAccentColor(t.accent);
                prefs.setDarkTheme(t.dark);
                prefs.setThemeMode(t.dark ? 2 : 1);
                recreate();
            }));
        }
        for (int i = 0; i < cards.size(); i += 2) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.addView(cards.get(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i + 1 < cards.size()) {
                r.addView(cards.get(i + 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else {
                r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            gridHost.addView(r);
        }
        col.addView(gridHost);
        col.addView(dimText("All themes are free — no VIP, no ads. Customize Theme lets you "
                + "pick any accent color, light or dark, and text size."));
    }

    private int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private View themeCard(String name, int top, int bottom, boolean selected, boolean customize,
                           View.OnClickListener click) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(6), dp(6), dp(6), dp(2));
        FrameLayout card = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        g.setCornerRadius(dp(12));
        if (selected) g.setStroke(dp(2), cAccent);
        card.setBackground(g);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        if (customize) {
            TextView plus = new TextView(this);
            plus.setText("＋");
            plus.setTextColor(0xFFFFFFFF);
            plus.setTextSize(38f);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            plus.setLayoutParams(plp);
            card.addView(plus);
        }
        if (selected) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextColor(0xFFFFFFFF);
            check.setTextSize(13f);
            check.setGravity(Gravity.CENTER);
            GradientDrawable cg = new GradientDrawable();
            cg.setShape(GradientDrawable.OVAL);
            cg.setColor(cAccent);
            check.setBackground(cg);
            FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(dp(24), dp(24));
            clp2.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            clp2.setMargins(0, 0, dp(8), dp(8));
            check.setLayoutParams(clp2);
            card.addView(check);
        }
        card.setOnClickListener(click);
        wrap.addView(card);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(cText);
        label.setTextSize(13.5f);
        label.setPadding(dp(2), dp(6), dp(2), dp(8));
        wrap.addView(label);
        return wrap;
    }

    // ------------------------------------------------------------ Media Manage page

    private void buildMediaPage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        col.addView(bigText("Media Manage"));
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            for (android.os.storage.StorageVolume vol : sm.getStorageVolumes()) {
                File dir = vol.getDirectory();
                if (dir == null) continue;
                android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
                long total = sf.getTotalBytes();
                long free = sf.getAvailableBytes();
                long used = total - free;
                String label = vol.getDescription(this);
                if (label == null) label = dir.getName();
                TextView head = new TextView(this);
                head.setText(label);
                head.setTextColor(cText);
                head.setTextSize(14.5f);
                head.setPadding(dp(16), dp(14), dp(16), 0);
                col.addView(head);
                TextView usage = new TextView(this);
                usage.setText("Used " + Prefs.fmtSize(used) + " / " + Prefs.fmtSize(total));
                usage.setTextColor(cDim);
                usage.setTextSize(12f);
                usage.setPadding(dp(16), dp(2), dp(16), dp(4));
                usage.setGravity(Gravity.END);
                col.addView(usage);
                LinearLayout bar = new LinearLayout(this);
                bar.setOrientation(LinearLayout.HORIZONTAL);
                float pct = total > 0 ? (float) used / total : 0;
                View fill = new View(this);
                fill.setBackgroundColor(cAccent);
                bar.addView(fill, new LinearLayout.LayoutParams(0, dp(4), Math.max(pct, 0.005f)));
                View rest = new View(this);
                GradientDrawable rg = new GradientDrawable();
                rg.setColor(cCard2);
                rest.setBackground(rg);
                bar.addView(rest, new LinearLayout.LayoutParams(0, dp(4), Math.max(1 - pct, 0.005f)));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
                blp.setMargins(dp(16), dp(2), dp(16), dp(6));
                bar.setLayoutParams(blp);
                col.addView(bar);
            }
        } catch (Exception ignored) {}

        long vTotal = 0;
        for (VideoItem v : allVideos) vTotal += v.size;
        long aTotal = 0;
        for (AudioItem a : allAudio) aTotal += a.size;
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(dp(12), dp(10), dp(12), dp(2));
        cards.addView(mediaTypeCard("VIDEO", Prefs.fmtSize(vTotal), 0xFF2E7CF6,
                v -> { videoSub = 0; showTab(0); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cards.addView(mediaTypeCard("MUSIC", Prefs.fmtSize(aTotal), 0xFF22C07A,
                v -> showTab(1)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(cards);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setPadding(dp(12), dp(10), dp(12), dp(2));
        shortcuts.addView(shortcut(R.drawable.ic_history, "Watched videos",
                v -> { mePage = "history"; showMe(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        shortcuts.addView(shortcut(R.drawable.ic_nav_videos, "Largest file",
                v -> {
                    List<VideoItem> sorted = new ArrayList<>(allVideos);
                    sorted.sort((a, b) -> Long.compare(b.size, a.size));
                    currentTab = 0;
                    videoSub = 0;
                    updateNavStyles();
                    baseList = sorted;
                    baseTitle = "Largest files";
                    currentPlaylist = null;
                    showVideoGrid(true);
                }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        shortcuts.addView(shortcut(R.drawable.ic_clean, "Clean",
                v -> { mePage = "clean"; cleanScanned = false; showMe(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(shortcuts);

        List<String> histUris = new ArrayList<>();
        for (String[] e : prefs.history()) histUris.add(e[0]);
        List<VideoItem> unwatched = new ArrayList<>();
        for (VideoItem v : allVideos) {
            if (!histUris.contains(v.uriStr) && prefs.resumePos(v.uriStr) <= 0) unwatched.add(v);
        }
        TextView uh = new TextView(this);
        uh.setText("Videos you haven't watched");
        uh.setTextColor(cText);
        uh.setTextSize(15.5f);
        uh.setTypeface(null, android.graphics.Typeface.BOLD);
        uh.setPadding(dp(16), dp(16), dp(16), 0);
        col.addView(uh);
        col.addView(dimText(unwatched.isEmpty()
                ? "You have watched everything in the library."
                : "You haven't watched these videos yet."));
        if (!unwatched.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout hrow = new LinearLayout(this);
            hrow.setOrientation(LinearLayout.HORIZONTAL);
            hrow.setPadding(dp(12), dp(4), dp(12), dp(10));
            int cap = Math.min(unwatched.size(), 12);
            for (int i = 0; i < cap; i++) {
                VideoItem v = unwatched.get(i);
                LinearLayout cardV = new LinearLayout(this);
                cardV.setOrientation(LinearLayout.VERTICAL);
                ImageView th = new ImageView(this);
                th.setScaleType(ImageView.ScaleType.CENTER_CROP);
                th.setBackgroundColor(0xFF1A2438);
                cardV.addView(th, new LinearLayout.LayoutParams(dp(170), dp(96)));
                loadThumbInto(th, v);
                TextView nm = new TextView(this);
                nm.setText(v.name);
                nm.setTextColor(cDim);
                nm.setTextSize(11f);
                nm.setMaxLines(2);
                cardV.addView(nm, new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(10);
                cardV.setLayoutParams(clp);
                cardV.setOnClickListener(x -> {
                    List<VideoItem> one = new ArrayList<>();
                    one.add(v);
                    playFrom(one, 0);
                });
                hrow.addView(cardV);
            }
            hs.addView(hrow);
            col.addView(hs);
        }
    }

    private View mediaTypeCard(String label, String size, int dotColor, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(cCard);
        card.setBackground(g);
        card.setPadding(dp(14), dp(16), dp(14), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        card.setLayoutParams(lp);
        TextView dot = new TextView(this);
        dot.setText("▶");
        dot.setTextColor(0xFFFFFFFF);
        dot.setTextSize(15f);
        dot.setGravity(Gravity.CENTER);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(dotColor);
        dot.setBackground(dg);
        card.addView(dot, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(10), 0, 0, 0);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(cText);
        l.setTextSize(14.5f);
        l.setTypeface(null, android.graphics.Typeface.BOLD);
        tc.addView(l);
        TextView s = new TextView(this);
        s.setText(size);
        s.setTextColor(cDim);
        s.setTextSize(12f);
        tc.addView(s);
        card.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(cDim);
        chev.setTextSize(20f);
        card.addView(chev);
        card.setOnClickListener(click);
        return card;
    }

    private View shortcut(int icon, String label, View.OnClickListener click) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        s.setPadding(dp(4), dp(6), dp(4), dp(6));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(cAccent);
        s.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(cText);
        tv.setTextSize(12f);
        tv.setPadding(0, dp(5), 0, 0);
        s.addView(tv);
        s.setOnClickListener(click);
        return s;
    }

    // ------------------------------------------------------------ History / Trash / Converter pages

    private void buildHistoryPage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        col.addView(bigText("History"));
        List<String[]> hist = prefs.history();
        if (hist.isEmpty()) {
            col.addView(dimText("Nothing watched yet."));
            return;
        }
        col.addView(button("Clear history", v -> {
            prefs.clearHistory();
            prefs.clearHistoryEntries();
            toast("History cleared");
            showMe();
        }));
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        for (String[] e : hist) {
            String when = "";
            try { when = df.format(new Date(Long.parseLong(e[2]))); } catch (Exception ignored) {}
            col.addView(row(R.drawable.ic_nav_videos, e[1], when, "▶",
                    v -> playHistoryEntry(e[0], e[1])));
        }
    }

    private void buildTrashPage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        List<TrashBin.Entry> entries = TrashBin.list(this);
        col.addView(bigText("Recycle Bin"));
        if (entries.isEmpty()) {
            col.addView(dimText("The bin is empty. Videos you send here stay safe "
                    + "until you restore them or delete them forever."));
            return;
        }
        col.addView(dimText(entries.size() + " items  •  " + Prefs.fmtSize(TrashBin.totalSize(this))
                + "  —  tap an item to restore or delete it."));
        col.addView(button("Empty Recycle Bin", v -> confirm("Empty the Recycle Bin?",
                "All items will be deleted forever.",
                () -> { TrashBin.empty(this); toast("Recycle Bin emptied"); showMe(); })));
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        for (TrashBin.Entry e : entries) {
            col.addView(row(R.drawable.ic_trash, e.origName,
                    Prefs.fmtSize(e.size) + "  •  " + df.format(new Date(e.deletedMs)), "⋮",
                    v -> new AlertDialog.Builder(this)
                            .setTitle(e.origName)
                            .setItems(new String[]{"Restore to gallery", "Delete forever"}, (d, w) -> {
                                if (w == 0) {
                                    exec.execute(() -> {
                                        boolean ok = TrashBin.restore(this, e);
                                        ui.post(() -> {
                                            toast(ok ? "Restored to Movies" : "Restore failed");
                                            if (ok) rescan();
                                            showMe();
                                        });
                                    });
                                } else {
                                    TrashBin.deleteForever(this, e);
                                    toast("Deleted forever");
                                    showMe();
                                }
                            })
                            .show()));
        }
    }

    private void buildConverterPage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        col.addView(bigText("MP3 Converter"));
        col.addView(dimText("Tap a video to extract its audio. MP3 tracks stay MP3, "
                + "AAC becomes M4A — saved to Music/TM Player."));
        for (VideoItem v : allVideos) {
            col.addView(row(R.drawable.ic_headphone, v.name, Prefs.fmtSize(v.size), "♪",
                    x -> convertToAudio(v)));
        }
        if (allVideos.isEmpty()) col.addView(dimText("No videos found yet."));
    }

    /** Photo theme: the picked picture becomes the window background, softened
     *  with the theme color so text and icons stay readable. */
    private void applyCustomBg() {
        String uri = prefs.customBg();
        if (uri == null || uri.isEmpty()) return;
        final int overlay = cBg;
        exec.execute(() -> {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(Uri.parse(uri));
                if (in == null) return;
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
                in.close();
                if (bmp == null) return;
                android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                        bmp.getWidth(), bmp.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(out);
                canvas.drawBitmap(bmp, 0, 0, null);
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor((0xA6 << 24) | (overlay & 0xFFFFFF)); // 65% theme color over the photo
                canvas.drawRect(0, 0, out.getWidth(), out.getHeight(), p);
                final android.graphics.drawable.BitmapDrawable dr =
                        new android.graphics.drawable.BitmapDrawable(getResources(), out);
                dr.setGravity(Gravity.FILL);
                ui.post(() -> getWindow().setBackgroundDrawable(dr));
            } catch (Exception ignored) {}
        });
    }

    // ------------------------------------------------------------ Clean page (PLAYit-style junk scan)

    private static final class CleanApk {
        String name;
        Uri uri;
        long size;
        boolean checked = false; // PLAYit leaves APKs unchecked: "clean carefully"
    }

    private final List<CleanApk> cleanApks = new ArrayList<>();
    private final List<File> cleanThumbFiles = new ArrayList<>();
    private long cleanThumbBytes = 0;
    private long cleanCacheBytes = 0;
    private boolean cleanThumbChecked = true;
    private boolean cleanCacheChecked = true;
    private boolean cleanScanned = false;
    private boolean cleanScanning = false;

        private void buildCleanPage(LinearLayout col) {
        col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
        col.addView(bigText("Clean"));
        if (!cleanScanned) {
            col.addView(dimText(cleanScanning ? "Scanning for junk…" : "Preparing scan…"));
            if (!cleanScanning) scanClean();
            return;
        }
        long total = cleanCacheBytes + cleanThumbBytes;
        for (CleanApk a : cleanApks) total += a.size;
        TextView big = new TextView(this);
        big.setText(Prefs.fmtSize(total));
        big.setTextColor(cAccent);
        big.setTextSize(36f);
        big.setTypeface(null, android.graphics.Typeface.BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(dp(16), dp(16), dp(16), 0);
        col.addView(big);
        TextView found = new TextView(this);
        found.setText("found");
        found.setTextColor(cDim);
        found.setTextSize(13f);
        found.setGravity(Gravity.CENTER);
        col.addView(found);

        if (!cleanApks.isEmpty()) {
            long apkTotal = 0;
            for (CleanApk a : cleanApks) apkTotal += a.size;
            col.addView(sectionLabel("APK files  •  " + Prefs.fmtSize(apkTotal)));
            for (CleanApk a : cleanApks) {
                col.addView(cleanRow(a.name, Prefs.fmtSize(a.size) + "  •  Clean carefully",
                        a.checked, v -> { a.checked = !a.checked; showMe(); }));
            }
        }
        if (cleanThumbBytes > 0) {
            col.addView(sectionLabel("Thumbnail  •  " + Prefs.fmtSize(cleanThumbBytes)));
            col.addView(cleanRow(".thumbnails", "Advice: Clean",
                    cleanThumbChecked, v -> { cleanThumbChecked = !cleanThumbChecked; showMe(); }));
        }
        if (cleanCacheBytes > 0) {
            col.addView(sectionLabel("App cache  •  " + Prefs.fmtSize(cleanCacheBytes)));
            col.addView(cleanRow("TM Player cache", "Safe to clean",
                    cleanCacheChecked, v -> { cleanCacheChecked = !cleanCacheChecked; showMe(); }));
        }
        if (total == 0) {
            col.addView(dimText("Nothing to clean — your phone is tidy."));
            return;
        }
        long sel = cleanSelectedBytes();
        TextView btn = (TextView) button("Clean " + Prefs.fmtSize(sel), v -> doClean());
        btn.setEnabled(sel > 0);
        col.addView(btn);
        col.addView(dimText("Only the ticked items are deleted. Videos, music and the "
                + "Recycle Bin are never touched by Clean."));
    }

    private long cleanSelectedBytes() {
        long s = 0;
        for (CleanApk a : cleanApks) if (a.checked) s += a.size;
        if (cleanThumbChecked) s += cleanThumbBytes;
        if (cleanCacheChecked) s += cleanCacheBytes;
        return s;
    }

    private View cleanRow(String name, String sub, boolean checked, View.OnClickListener toggle) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(cText);
        n.setTextSize(14f);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tc.addView(n);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(cAccent);
        s.setTextSize(11.5f);
        tc.addView(s);
        r.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView box = new TextView(this);
        box.setText(checked ? "✓" : "");
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(14f);
        box.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (checked) g.setColor(cAccent);
        else g.setStroke(dp(2), cAccent);
        box.setBackground(g);
        r.addView(box, new LinearLayout.LayoutParams(dp(26), dp(26)));
        r.setOnClickListener(toggle);
        return r;
    }

    private void scanClean() {
        cleanScanning = true;
        exec.execute(() -> {
            cleanApks.clear();
            cleanThumbFiles.clear();
            cleanThumbBytes = 0;
            try {
                Uri files = android.provider.MediaStore.Files.getContentUri("external");
                String[] proj = {android.provider.MediaStore.MediaColumns._ID,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE};
                try (Cursor c = getContentResolver().query(files, proj,
                        android.provider.MediaStore.MediaColumns.MIME_TYPE + "=?",
                        new String[]{"application/vnd.android.package-archive"}, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            CleanApk a = new CleanApk();
                            a.uri = android.content.ContentUris.withAppendedId(files, c.getLong(0));
                            a.name = c.getString(1);
                            a.size = c.getLong(2);
                            if (a.name != null) cleanApks.add(a);
                        }
                    }
                }
            } catch (Exception ignored) {}
            cleanApks.sort((a, b) -> Long.compare(b.size, a.size));
            try {
                File ext = Environment.getExternalStorageDirectory();
                String[] dirs = {"DCIM/.thumbnails", ".thumbnails", "Pictures/.thumbnails"};
                for (String d : dirs) {
                    collectFiles(new File(ext, d), cleanThumbFiles);
                }
                for (File f : cleanThumbFiles) cleanThumbBytes += f.length();
            } catch (Exception ignored) {}
            cleanCacheBytes = dirSize(getCacheDir());
            cleanScanned = true;
            cleanScanning = false;
            ui.post(() -> { if ("clean".equals(mePage) && currentTab == 3) showMe(); });
        });
    }

    private void collectFiles(File f, List<File> out) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) { out.add(f); return; }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) collectFiles(k, out);
    }

    private void doClean() {
        long expected = cleanSelectedBytes();
        exec.execute(() -> {
            int apkDeleted = 0;
            for (CleanApk a : cleanApks) {
                if (!a.checked) continue;
                try {
                    if (getContentResolver().delete(a.uri, null, null) > 0) apkDeleted++;
                } catch (Exception ignored) {}
            }
            int thumbDeleted = 0;
            if (cleanThumbChecked) {
                for (File f : cleanThumbFiles) {
                    //noinspection ResultOfMethodCallIgnored
                    if (f.delete()) thumbDeleted++;
                }
            }
            if (cleanCacheChecked) {
                File[] kids = getCacheDir().listFiles();
                if (kids != null) for (File k : kids) deleteRecursive(k);
            }
            final int ad = apkDeleted, td = thumbDeleted;
            ui.post(() -> {
                toast("Cleaned " + Prefs.fmtSize(expected) + " (" + ad + " APKs, "
                        + td + " thumbnails, cache)");
                cleanScanned = false;
                showMe();
            });
        });
    }

    /** PLAYit-style settings row with a pill switch on the right. */
    private View toggleRow(String title, String sub, boolean on, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText);
        t.setTextSize(15f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        tc.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(cDim);
            s.setTextSize(12.5f);
            s.setPadding(0, dp(2), dp(12), 0);
            tc.addView(s);
        }
        r.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout pill = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(14));
        track.setColor(on ? cAccent : (prefs.darkTheme() ? 0xFF3A4457 : 0xFFC9D4E2));
        pill.setBackground(track);
        View knob = new View(this);
        GradientDrawable kd = new GradientDrawable();
        kd.setShape(GradientDrawable.OVAL);
        kd.setColor(0xFFFFFFFF);
        knob.setBackground(kd);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(21), dp(21));
        klp.gravity = (on ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        klp.leftMargin = dp(3);
        klp.rightMargin = dp(3);
        pill.addView(knob, klp);
        r.addView(pill, new LinearLayout.LayoutParams(dp(48), dp(27)));
        r.setOnClickListener(click);
        return r;
    }

    private void choiceDialog(String title, String[] options, int selected, OnPick pick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, Math.max(selected, 0), (d, w) -> {
                    pick.pick(w);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHiddenFoldersDialog() {
        Map<String, String> folders = new LinkedHashMap<>(); // path -> name
        for (VideoItem v : allVideos) {
            String key = v.folderPath != null ? v.folderPath
                    : (v.folderName != null ? v.folderName : "Unknown");
            folders.put(key, v.folderName != null ? v.folderName : key);
        }
        if (folders.isEmpty()) {
            toast("No folders scanned yet");
            return;
        }
        String[] keys = folders.keySet().toArray(new String[0]);
        String[] names = new String[keys.length];
        boolean[] visible = new boolean[keys.length];
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs.hiddenFolders());
        for (int i = 0; i < keys.length; i++) {
            names[i] = folders.get(keys[i]);
            visible[i] = !hidden.contains(keys[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Manage your scan list")
                .setMultiChoiceItems(names, visible, (d, w, checked) -> visible[w] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    java.util.Set<String> nh = new java.util.HashSet<>();
                    for (int i = 0; i < keys.length; i++) if (!visible[i]) nh.add(keys[i]);
                    prefs.setHiddenFolders(nh);
                    baseList = lib();
                    toast(nh.isEmpty() ? "All folders visible"
                            : nh.size() + " folder(s) hidden");
                    showMe();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ Settings pages

    private void buildSettings(LinearLayout col) {
        if (settingsSection == 0) {
            col.addView(backRow("Me", v -> { mePage = "home"; showMe(); }));
            col.addView(bigText("Settings"));
            col.addView(row(R.drawable.ic_nav_settings, "Appearance", "Adjust the app to your liking", "›",
                    v -> { settingsSection = 1; showMe(); }));
            col.addView(row(R.drawable.ic_nav_folders, "Media library", "Scan and manage media library", "›",
                    v -> { settingsSection = 2; showMe(); }));
            col.addView(row(R.drawable.ic_play, "Player", "Player appearance, playback controls", "›",
                    v -> { settingsSection = 3; showMe(); }));
            col.addView(row(0, "Gestures", "Configure player touch gestures", "›",
                    v -> { settingsSection = 4; showMe(); }));
            col.addView(row(R.drawable.ic_headphone, "Audio", "Audio playback options", "›",
                    v -> { settingsSection = 5; showMe(); }));
            col.addView(row(R.drawable.ic_subtitle, "Subtitle", "Subtitle appearance, playback options", "›",
                    v -> { settingsSection = 6; showMe(); }));
            col.addView(row(R.drawable.ic_person, "General", "User data options", "›",
                    v -> { settingsSection = 7; showMe(); }));
            col.addView(row(0, "About", "Version, support, credits", "›",
                    v -> { settingsSection = 8; showMe(); }));
            return;
        }
        col.addView(backRow("Settings", v -> { settingsSection = 0; showMe(); }));
        switch (settingsSection) {
            case 1: {
                col.addView(bigText("Appearance"));
                col.addView(accentRow());
                col.addView(dimText("Dark theme"));
                col.addView(chips(new String[]{"System default", "Light", "Dark"}, prefs.themeMode(),
                        i -> { prefs.setThemeMode(i); recreate(); }));
                col.addView(toggleRow("High contrast dark theme",
                        "Use pure black background for dark theme",
                        prefs.highContrast(),
                        v -> { prefs.setHighContrast(!prefs.highContrast()); recreate(); }));
                if (Build.VERSION.SDK_INT >= 31) {
                    col.addView(toggleRow("Dynamic color theme",
                            "Apply colors from system wallpaper to the application theme",
                            prefs.dynamicColor(),
                            v -> { prefs.setDynamicColor(!prefs.dynamicColor()); recreate(); }));
                }
                col.addView(dimText("Text size"));
                col.addView(chips(new String[]{"Small", "Normal", "Large"}, prefs.textSize(),
                        i -> { prefs.setTextSize(i); recreate(); }));
                break;
            }
            case 2: {
                col.addView(bigText("Media library"));
                col.addView(toggleRow("Mark last played media",
                        "Mark last played media in home and in each folder",
                        prefs.markLastPlayed(),
                        v -> { prefs.setMarkLastPlayed(!prefs.markLastPlayed()); showMe(); }));
                final int[] dayVals = {0, 1, 3, 7, 14, 30};
                col.addView(row(0, "Mark newly added media as new",
                        "Show a NEW badge on recently added videos",
                        prefs.newDays() == 0 ? "Off ›" : prefs.newDays() + " days ›",
                        v -> choiceDialog("Mark as new for",
                                new String[]{"Off", "1 day", "3 days", "7 days", "14 days", "30 days"},
                                java.util.Arrays.asList(0, 1, 3, 7, 14, 30).indexOf(prefs.newDays()),
                                i -> { prefs.setNewDays(dayVals[i]); showMe(); })));
                col.addView(sectionLabel("Scan"));
                col.addView(row(0, "Manage folders",
                        "Select folders to be excluded in the media library",
                        prefs.hiddenFolders().size() + " hidden ›",
                        v -> showHiddenFoldersDialog()));
                col.addView(row(0, "All files access", "Needed on some phones for USB drives", "›",
                        v -> {
                            if (Build.VERSION.SDK_INT >= 30) {
                                try {
                                    startActivity(new Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + getPackageName())));
                                } catch (Exception e) {
                                    toast("Not available on this phone");
                                }
                            } else {
                                toast("Already available on this Android version");
                            }
                        }));
                col.addView(sectionLabel("History"));
                col.addView(toggleRow("Watch history",
                        "Keep a record of videos you watch",
                        prefs.watchHistory(),
                        v -> { prefs.setWatchHistory(!prefs.watchHistory()); showMe(); }));
                col.addView(sectionLabel("Thumbnail"));
                col.addView(row(0, "Thumbnail generation", "Frame at position",
                        new String[]{"First frame", "10%", "25%", "Middle"}[prefs.thumbPos()] + " ›",
                        v -> choiceDialog("Thumbnail generation",
                                new String[]{"First frame", "10% into the video",
                                        "25% into the video", "Middle"},
                                prefs.thumbPos(),
                                i -> { prefs.setThumbPos(i); showMe(); })));
                col.addView(dimText("Grid columns"));
                col.addView(chips(new String[]{"2", "3"}, prefs.gridCols() == 2 ? 0 : 1,
                        i -> prefs.setGridCols(i == 0 ? 2 : 3)));
                col.addView(button("Rescan library", v -> rescan()));
                break;
            }
            case 3: {
                col.addView(bigText("Player"));
                col.addView(sectionLabel("Interface"));
                final int[] timeoutVals = {2, 3, 4, 5, 6, 8, 10};
                col.addView(row(0, "Controller timeout", "Hide player controls after",
                        prefs.ctrlTimeout() + " seconds ›",
                        v -> choiceDialog("Controller timeout",
                                new String[]{"2 seconds", "3 seconds", "4 seconds", "5 seconds",
                                        "6 seconds", "8 seconds", "10 seconds"},
                                java.util.Arrays.asList(2, 3, 4, 5, 6, 8, 10)
                                        .indexOf(prefs.ctrlTimeout()),
                                i -> { prefs.setCtrlTimeout(timeoutVals[i]); showMe(); })));
                col.addView(toggleRow("Display battery level, time",
                        "Keep battery and clock visible when the controls are hidden",
                        prefs.showStatus(),
                        v -> { prefs.setShowStatus(!prefs.showStatus()); showMe(); }));
                col.addView(sectionLabel("Display"));
                col.addView(toggleRow("Show video name",
                        "Display video headlines on the homepage",
                        prefs.showName(),
                        v -> { prefs.setShowName(!prefs.showName()); showMe(); }));
                col.addView(row(0, "Player screen orientation",
                        "Default orientation for the player",
                        new String[]{"Auto", "Landscape", "Portrait"}[prefs.orientation()] + " ›",
                        v -> choiceDialog("Player screen orientation",
                                new String[]{"Auto", "Landscape", "Portrait"},
                                prefs.orientation(),
                                i -> { prefs.setOrientation(i); showMe(); })));
                col.addView(toggleRow("Keep screen orientation locked",
                        "Apply the chosen orientation to all videos",
                        prefs.orientLock(),
                        v -> { prefs.setOrientLock(!prefs.orientLock()); showMe(); }));
                col.addView(toggleRow("Fixed screen ratio",
                        "Applies the default screen mode to all video playback",
                        prefs.fixedRatio(),
                        v -> { prefs.setFixedRatio(!prefs.fixedRatio()); showMe(); }));
                col.addView(row(0, "Default screen mode", "Fit, fill or stretch the picture",
                        Prefs.RESIZE_NAMES[prefs.defaultResize()] + " ›",
                        v -> choiceDialog("Default screen mode",
                                new String[]{"Fit", "Fill", "Stretch"},
                                prefs.defaultResize(),
                                i -> { prefs.setDefaultResize(i); showMe(); })));
                col.addView(sectionLabel("Playback"));
                String[] speedLabels = new String[Prefs.SPEEDS.length];
                int sel = 3;
                for (int i = 0; i < Prefs.SPEEDS.length; i++) {
                    float sf = Prefs.SPEEDS[i];
                    speedLabels[i] = (sf == (int) sf ? ((int) sf) + ".0" : String.valueOf(sf)) + "x";
                    if (Math.abs(Prefs.SPEEDS[i] - prefs.defaultSpeed()) < 0.01f) sel = i;
                }
                final int selF = sel;
                col.addView(row(0, "Default playback speed", "Speed new videos start with",
                        speedLabels[selF] + " ›",
                        v -> choiceDialog("Default playback speed", speedLabels, selF,
                                i -> { prefs.setDefaultSpeed(Prefs.SPEEDS[i]); showMe(); })));
                col.addView(toggleRow("Resume",
                        "Resume played videos from the point where you stopped",
                        prefs.resumeOn(),
                        v -> { prefs.setResumeOn(!prefs.resumeOn()); showMe(); }));
                col.addView(toggleRow("Autoplay",
                        "Automatically play the next video in the current folder",
                        prefs.continuous(),
                        v -> { prefs.setContinuous(!prefs.continuous()); showMe(); }));
                col.addView(toggleRow("Picture in Picture Mode",
                        "Automatically switch to miniature player when tapping Home Button",
                        prefs.autoPip(),
                        v -> { prefs.setAutoPip(!prefs.autoPip()); showMe(); }));
                col.addView(toggleRow("Remember brightness level",
                        "Remember brightness level between playback sessions",
                        prefs.rememberBrightness(),
                        v -> { prefs.setRememberBrightness(!prefs.rememberBrightness()); showMe(); }));
                break;
            }
            case 4: {
                col.addView(bigText("Gestures"));
                col.addView(toggleRow("Gesture operation",
                        "Control brightness, volume and seek by swiping",
                        prefs.gesturesOn(),
                        v -> { prefs.setGesturesOn(!prefs.gesturesOn()); showMe(); }));
                col.addView(toggleRow("Double-tap to seek",
                        "Double-tap the sides to fast forward or rewind",
                        prefs.doubleTapOn(),
                        v -> { prefs.setDoubleTapOn(!prefs.doubleTapOn()); showMe(); }));
                col.addView(row(0, "Double-tap to seek time", "Seconds per double-tap skip",
                        prefs.doubleTapSeconds() + "s ›",
                        v -> choiceDialog("Double-tap to seek time",
                                new String[]{"5s", "10s", "15s", "20s", "30s"},
                                java.util.Arrays.asList(5, 10, 15, 20, 30)
                                        .indexOf(prefs.doubleTapSeconds()),
                                i -> { prefs.setDoubleTapSeconds(new int[]{5, 10, 15, 20, 30}[i]); showMe(); })));
                col.addView(toggleRow("Long press accelerator",
                        "Long press the screen to increase the playback speed",
                        prefs.longPressOn(),
                        v -> { prefs.setLongPressOn(!prefs.longPressOn()); showMe(); }));
                col.addView(row(0, "Long press playback speed", "Speed while long pressing",
                        Prefs.fmtSpeedShort(prefs.longPressSpeed()) + " ›",
                        v -> choiceDialog("Long press playback speed",
                                new String[]{"1.5x", "2.0x", "3.0x"},
                                prefs.longPressSpeed() < 1.75f ? 0
                                        : prefs.longPressSpeed() < 2.5f ? 1 : 2,
                                i -> { prefs.setLongPressSpeed(new float[]{1.5f, 2.0f, 3.0f}[i]); showMe(); })));
                col.addView(toggleRow("Long press vibration",
                        "Vibrate when the long-press accelerator starts",
                        prefs.longPressVibrate(),
                        v -> { prefs.setLongPressVibrate(!prefs.longPressVibrate()); showMe(); }));
                break;
            }
            case 5: {
                col.addView(bigText("Audio"));
                col.addView(sectionLabel("Playback"));
                col.addView(toggleRow("Require audio focus",
                        "Pause other players while this player is playing videos, and vice versa",
                        prefs.audioFocus(),
                        v -> { prefs.setAudioFocus(!prefs.audioFocus()); showMe(); }));
                col.addView(toggleRow("Pause on disconnect",
                        "Pause playback when headset disconnected from the device",
                        prefs.pauseDisconnect(),
                        v -> { prefs.setPauseDisconnect(!prefs.pauseDisconnect()); showMe(); }));
                col.addView(toggleRow("System volume panel",
                        "Show system volume panel while adjusting volume with headset on",
                        prefs.sysVolPanel(),
                        v -> { prefs.setSysVolPanel(!prefs.sysVolPanel()); showMe(); }));
                col.addView(toggleRow("Volume boost",
                        "Boost audio volume up to 200%",
                        prefs.volBoost(),
                        v -> { prefs.setVolBoost(!prefs.volBoost()); showMe(); }));
                break;
            }
            case 6: {
                col.addView(bigText("Subtitle"));
                col.addView(sectionLabel("Appearance"));
                final int[] subSizes = {14, 16, 17, 18, 20, 22, 24, 28, 32};
                col.addView(row(0, "Subtitle text size", "Size of subtitle text",
                        prefs.subTextSize() + " ›",
                        v -> choiceDialog("Subtitle text size",
                                new String[]{"14", "16", "17", "18", "20", "22", "24", "28", "32"},
                                java.util.Arrays.asList(14, 16, 17, 18, 20, 22, 24, 28, 32)
                                        .indexOf(prefs.subTextSize()),
                                i -> { prefs.setSubTextSize(subSizes[i]); showMe(); })));
                col.addView(row(0, "Subtitle font", "Font of subtitle text",
                        new String[]{"Default", "Sans", "Serif", "Monospace"}[prefs.subFont()] + " ›",
                        v -> choiceDialog("Subtitle font",
                                new String[]{"Default", "Sans", "Serif", "Monospace"},
                                prefs.subFont(),
                                i -> { prefs.setSubFont(i); showMe(); })));
                col.addView(toggleRow("Bold subtitle text",
                        "Use bold text for subtitle",
                        prefs.subBold(),
                        v -> { prefs.setSubBold(!prefs.subBold()); showMe(); }));
                col.addView(toggleRow("Subtitle background",
                        "Enable background to subtitle text",
                        prefs.subBg(),
                        v -> { prefs.setSubBg(!prefs.subBg()); showMe(); }));
                col.addView(row(0, "System caption style",
                        "Open system captioning preferences", "›",
                        v -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
                            } catch (Exception e) {
                                toast("Not available on this phone");
                            }
                        }));
                break;
            }
            case 7: {
                col.addView(bigText("General"));
                col.addView(dimText("Vault fingerprint unlock"));
                col.addView(chips(new String[]{"Off", "On"}, prefs.fingerprintUnlock() ? 1 : 0,
                        i -> prefs.setFingerprintUnlock(i == 1)));
                col.addView(button("Change vault PIN", v -> changePinDialog()));
                col.addView(sectionLabel("User data"));
                col.addView(button("Clear watch history", v -> {
                    prefs.clearHistory();
                    prefs.clearHistoryEntries();
                    toast("History cleared");
                }));
                col.addView(button("Clear playback positions", v -> {
                    prefs.clearPositions();
                    toast("Playback positions cleared");
                }));
                col.addView(button("Reset all settings", v -> confirm(
                        "Reset all settings?",
                        "Theme, playback and subtitle settings go back to defaults. Your videos and vault are not touched.",
                        () -> { prefs.resetAll(); recreate(); })));
                break;
            }
            case 8: {
                col.addView(bigText("About"));
                LinearLayout brand = new LinearLayout(this);
                brand.setOrientation(LinearLayout.HORIZONTAL);
                brand.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable bg2 = new GradientDrawable();
                bg2.setCornerRadius(dp(14));
                bg2.setColor(cCard);
                brand.setBackground(bg2);
                brand.setPadding(dp(16), dp(14), dp(16), dp(14));
                ImageView bicon = new ImageView(this);
                bicon.setImageResource(R.drawable.ic_brand);
                brand.addView(bicon, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout btxt = new LinearLayout(this);
                btxt.setOrientation(LinearLayout.VERTICAL);
                btxt.setPadding(dp(14), 0, 0, 0);
                TextView bn = new TextView(this);
                bn.setText("TM Player");
                bn.setTextColor(cText);
                bn.setTextSize(18f);
                bn.setTypeface(null, android.graphics.Typeface.BOLD);
                btxt.addView(bn);
                TextView bv = new TextView(this);
                bv.setText("Version 0.1 • by MD. Muklash Mia");
                bv.setTextColor(cDim);
                bv.setTextSize(13f);
                btxt.addView(bv);
                brand.addView(btxt);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.setMargins(dp(14), dp(10), dp(14), dp(10));
                brand.setLayoutParams(blp);
                col.addView(brand);
                LinearLayout dev = new LinearLayout(this);
                dev.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable dg = new GradientDrawable();
                dg.setCornerRadius(dp(14));
                dg.setColor(cCard);
                dev.setBackground(dg);
                dev.setPadding(dp(16), dp(14), dp(16), dp(14));
                TextView dn = new TextView(this);
                dn.setText("Developer");
                dn.setTextColor(cDim);
                dn.setTextSize(12f);
                dev.addView(dn);
                TextView dname = new TextView(this);
                dname.setText("MD. Muklash Mia");
                dname.setTextColor(cText);
                dname.setTextSize(16f);
                dname.setTypeface(null, android.graphics.Typeface.BOLD);
                dev.addView(dname);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.setMargins(dp(14), dp(10), dp(14), dp(10));
                dev.setLayoutParams(dlp);
                col.addView(dev);
                col.addView(button("Telegram  @muklash_dev", v -> openUrl("https://t.me/muklash_dev")));
                col.addView(button("GitHub  Muklash-Dev", v -> openUrl("https://github.com/Muklash-Dev")));
                col.addView(button("About TM Player", v -> showAbout()));
                col.addView(dimText("No third-party libraries — built with Android framework APIs only. Ads-free and open source."));
                break;
            }
        }
    }

    private void changePinDialog() {
        if (prefs.pinHash() == null) { toast("Set a PIN in the Vault tab first"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText cur = new EditText(this);
        cur.setHint("Current PIN");
        cur.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText nw = new EditText(this);
        nw.setHint("New PIN (4–6 digits)");
        nw.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(cur);
        box.addView(nw);
        new AlertDialog.Builder(this)
                .setTitle("Change vault PIN")
                .setView(box)
                .setPositiveButton("Change", (d, w) -> {
                    String pin = prefs.pinHash();
                    if (pin == null || !Vault.hashPin(cur.getText().toString().trim()).equals(pin)) {
                        toast("Current PIN is wrong");
                        return;
                    }
                    String np = nw.getText().toString().trim();
                    if (np.length() < 4 || np.length() > 6) { toast("New PIN must be 4–6 digits"); return; }
                    prefs.setPinHash(Vault.hashPin(np));
                    toast("PIN changed");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("TM Player — Turbo Media Player")
                .setMessage("An ads-free, open-source video & music player.\n\n"
                        + "Plays from internal storage, SD card and USB pendrive, "
                        + "with a PIN + fingerprint private vault, playlists, "
                        + "background play and a video-to-audio converter.\n\n"
                        + "Developed by MD. Muklash Mia\n"
                        + "Telegram: @muklash_dev\n"
                        + "GitHub: github.com/Muklash-Dev")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ shared UI helpers

    private View backRow(String target, View.OnClickListener click) {
        TextView back = new TextView(this);
        back.setText("←  " + target);
        back.setTextColor(cAccent);
        back.setTextSize(14.5f);
        back.setPadding(dp(16), dp(12), dp(16), dp(4));
        back.setOnClickListener(click);
        return back;
    }

    private TextView bigText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cText);
        t.setTextSize(17f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(16), dp(14), dp(16), dp(4));
        return t;
    }

    private TextView dimText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cDim);
        t.setTextSize(12.5f);
        t.setPadding(dp(16), dp(6), dp(16), dp(6));
        return t;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cText);
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(16), dp(14), dp(16), dp(2));
        return t;
    }

    private View row(int iconRes, String titleS, String subS, String valueS,
                     View.OnClickListener click) {
        View r = LayoutInflater.from(this).inflate(R.layout.item_row, null);
        ImageView icon = r.findViewById(R.id.row_icon);
        if (iconRes == 0) {
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageResource(iconRes);
            icon.setColorFilter(cAccent);
            icon.getLayoutParams().width = dp(26);
            icon.getLayoutParams().height = dp(26);
        }
        TextView title = r.findViewById(R.id.row_title);
        title.setText(titleS);
        title.setTextColor(cText);
        title.setTextSize(14.5f * prefs.textScale());
        TextView sub = r.findViewById(R.id.row_sub);
        sub.setText(subS);
        sub.setTextColor(cDim);
        sub.setTextSize(12f * prefs.textScale());
        TextView value = r.findViewById(R.id.row_value);
        value.setText(valueS);
        value.setTextColor(cAccent);
        r.setOnClickListener(click);
        return r;
    }

    private View chips(String[] labels, int selected, OnPick onPick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(14), dp(4), dp(14), dp(8));
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13f);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            if (i == selected) {
                g.setColor(cAccent);
                chip.setTextColor(0xFFFFFFFF);
            } else {
                g.setColor(cCard2);
                chip.setTextColor(cText);
            }
            chip.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            final int idx = i;
            chip.setOnClickListener(v -> onPick.pick(idx));
            r.addView(chip);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(r);
        return hs;
    }

    private View button(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14.5f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(cAccent);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private View accentRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(dimText("Accent color"));
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(16), dp(2), dp(16), dp(8));
        for (int c : Prefs.ACCENTS) {
            TextView dot = new TextView(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            if (c == prefs.accent()) g.setStroke(dp(3), cText);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.rightMargin = dp(12);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> { prefs.setAccentColor(c); recreate(); });
            r.addView(dot);
        }
        wrap.addView(r);
        return wrap;
    }

    private void confirm(String title, String msg, Runnable onYes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
