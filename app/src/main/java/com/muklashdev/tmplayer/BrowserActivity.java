package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TM Browser — Kiwi-style interface on the Android system WebView engine
 * (the engine lives in the OS, like in Via Browser, so the APK stays tiny).
 * Top address bar, circular shortcuts, icon menu, card tab switcher with
 * page thumbnails, full Settings page, History with search, Downloads list.
 * Chromium-only features (Extensions, Developer tools) cannot exist on
 * WebView and are not offered.
 */
public class BrowserActivity extends Activity {

    // ---------- theme ----------
    int cBg, cCard, cCard2, cText, cDim, cAccent;
    boolean dark;

    // ---------- settings (SharedPreferences "tmp_browser") ----------
    SharedPreferences bp;
    boolean desktopMode;
    int prefSearch;          // 0 Google, 1 DuckDuckGo, 2 Bing
    boolean prefHomepage;    // show start page on new tabs
    int prefNight;           // 0 auto, 1 on, 2 off
    int prefTextZoom;        // percent
    boolean prefToolbarBottom;
    boolean prefTpCookies;
    boolean prefSafeBrowsing;
    boolean prefJs;
    boolean prefImages;
    boolean prefAdBlock;
    int prefUaMode;          // 0 default mobile, 1 desktop, 2 iPhone
    int prefOrientation;     // 0 auto, 1 portrait, 2 landscape
    boolean fsOn;
    int prefDnsPreset;       // 0 Google, 1 Cloudflare, 2 AdGuard, 3 custom
    String prefDohCustom = "";
    int prefCustomSel = -1;  // selected custom search engine index, -1 = preset
    final ArrayList<String[]> customEngines = new ArrayList<>(); // {name, urlTemplate}

    static final String[][] ENGINES = {
            {"Google", "https://www.google.com/search?q="},
            {"DuckDuckGo", "https://duckduckgo.com/?q="},
            {"Bing", "https://www.bing.com/search?q="},
            {"Yahoo", "https://search.yahoo.com/search?p="},
            {"StartPage", "https://www.startpage.com/sp/search?query="},
            {"Ecosia", "https://www.ecosia.org/search?q="},
            {"Qwant", "https://www.qwant.com/?q="},
            {"Yandex", "https://yandex.com/search/?text="},
    };

    String effectiveDoh() {
        switch (prefDnsPreset) {
            case 1: return "https://cloudflare-dns.com/dns-query";
            case 2: return "https://dns.adguard-dns.com/dns-query";
            case 3: return prefDohCustom == null ? "" : prefDohCustom;
            default: return "https://dns.google/resolve";
        }
    }

    String dnsName() {
        switch (prefDnsPreset) {
            case 1: return "Cloudflare (1.1.1.1)";
            case 2: return "AdGuard (blocks ads at DNS level)";
            case 3: return "Custom: " + (prefDohCustom == null || prefDohCustom.isEmpty()
                    ? "not set" : prefDohCustom);
            default: return "Google (8.8.8.8)";
        }
    }

    void saveCustomEngines() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] ce : customEngines) {
                JSONObject o = new JSONObject();
                o.put("n", ce[0]);
                o.put("u", ce[1]);
                arr.put(o);
            }
            bp.edit().putString("custom_engines", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    void loadCustomEngines() {
        customEngines.clear();
        try {
            JSONArray arr = new JSONArray(bp.getString("custom_engines", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                customEngines.add(new String[]{o.optString("n"), o.optString("u")});
            }
        } catch (Exception ignored) {}
    }

    void loadPrefs() {
        prefUaMode = bp.getInt("ua_mode", bp.getBoolean("desktop", false) ? 1 : 0);
        desktopMode = prefUaMode == 1;
        prefAdBlock = bp.getBoolean("adblock", true);
        prefOrientation = bp.getInt("orientation", 0);
        prefDnsPreset = bp.getInt("dns_preset", 2); // default: AdGuard (ad-blocking)
        prefDohCustom = bp.getString("doh_custom", "");
        prefCustomSel = bp.getInt("search_custom_sel", -1);
        loadCustomEngines();
        DohFetch.customDoh = effectiveDoh();
        TmDownloadService.loadPersisted(this);
        loadAdList(this);
        prefSearch = bp.getInt("search_engine", 0);
        prefHomepage = bp.getBoolean("homepage", true);
        prefNight = bp.getInt("night_mode", 0);
        prefTextZoom = bp.getInt("text_zoom", 100);
        prefToolbarBottom = bp.getBoolean("toolbar_bottom", false);
        prefTpCookies = bp.getBoolean("tp_cookies", true);
        prefSafeBrowsing = bp.getBoolean("safe_browsing", true);
        prefJs = bp.getBoolean("javascript", true);
        prefImages = bp.getBoolean("images", true);
    }

    String searchUrl(String q) {
        try {
            String e = URLEncoder.encode(q, "UTF-8");
            if (prefCustomSel >= 0 && prefCustomSel < customEngines.size()) {
                String tpl = customEngines.get(prefCustomSel)[1];
                return tpl.contains("%s") ? tpl.replace("%s", e) : tpl + e;
            }
            int i = Math.max(0, Math.min(prefSearch, ENGINES.length - 1));
            return ENGINES[i][1] + e;
        } catch (Exception ex) { return "https://www.google.com/search?q=" + q; }
    }

    String searchName() {
        if (prefCustomSel >= 0 && prefCustomSel < customEngines.size())
            return customEngines.get(prefCustomSel)[0];
        int i = Math.max(0, Math.min(prefSearch, ENGINES.length - 1));
        return ENGINES[i][0];
    }

    // ---------- tabs ----------
    static class BTab {
        WebView wv;
        Bitmap thumb;
        String url = "";
        String title = "";
        String lastRecorded = "";
        boolean priv, started, home;
        final ArrayList<String> sniffed = new ArrayList<>();
        BTab(boolean p) { priv = p; }
    }
    static final ArrayList<BTab> TABS = new ArrayList<>();
    static int sActive = 0;
    BTab lastBoundTab;

    // ---------- views ----------
    FrameLayout stage;
    View homeView, panelView, tabsView, findBar, errorBar, menuView, settingsView, pageView, undoBar;
    TextView errorText, netInfo, tabCountBtn, findCount, starBtn;
    EditText urlText, findText;
    ProgressBar progress;
    TextView backBtn;
    Handler ui = new Handler(Looper.getMainLooper());
    boolean urlEditing = false;
    boolean showingHome = true;
    ValueCallback<Uri[]> fileCallback;
    static final int REQ_FILE = 4411;
    static final int REQ_TREE = 7821;

    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    static final String IPHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    String currentUa() {
        return prefUaMode == 1 ? DESKTOP_UA : prefUaMode == 2 ? IPHONE_UA : null;
    }

    String uaName() {
        return prefUaMode == 1 ? "Desktop" : prefUaMode == 2 ? "iPhone" : "Default (Android)";
    }

    void applyOrientation() {
        setRequestedOrientation(prefOrientation == 1
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : prefOrientation == 2
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    BTab activeTab() {
        if (TABS.isEmpty()) TABS.add(new BTab(false));
        if (sActive < 0 || sActive >= TABS.size()) sActive = 0;
        return TABS.get(sActive);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bp = getSharedPreferences("tmp_browser", MODE_PRIVATE);
        loadPrefs();
        Prefs prefs = new Prefs(this);
        int themeOverride = bp.getInt("theme_override", -1);
        dark = themeOverride >= 0 ? themeOverride == 1 : prefs.isDark(this);
        cAccent = prefs.accent(this);
        if (dark) { cBg = 0xFF0B0E13; cCard = 0xFF161B22; cCard2 = 0xFF212A35; cText = 0xFFF2F6FC; cDim = 0xFF9DADC6; }
        else { cBg = 0xFFF4F7FB; cCard = 0xFFFFFFFF; cCard2 = 0xFFE8EEF6; cText = 0xFF0B1220; cDim = 0xFF42536B; }

        Window w = getWindow();
        w.setStatusBarColor(cBg);
        w.setNavigationBarColor(cCard);
        if (!dark) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        if (TABS.isEmpty()) { TABS.add(new BTab(false)); sActive = 0; }
        activeTab().home = prefHomepage && activeTab().url.isEmpty();
        showingHome = activeTab().home;
        applyOrientation();
        bindActive();
        runNetTest();
        String openUrl = getIntent() == null ? null : getIntent().getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty()) navigate(openUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String openUrl = intent == null ? null : intent.getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty()) navigate(openUrl);
    }

    // ============================================================ UI helpers
    TextView btn(String label, float size) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(size);
        t.setTextColor(cText);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        return t;
    }

    TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ============================================================ main UI
    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        // --- error bar (hidden until a load fails) ---
        LinearLayout eb = new LinearLayout(this);
        eb.setOrientation(LinearLayout.HORIZONTAL);
        eb.setBackgroundColor(0xFF8E1F2F);
        eb.setGravity(Gravity.CENTER_VERTICAL);
        eb.setPadding(dp(12), dp(8), dp(6), dp(8));
        errorText = new TextView(this);
        errorText.setTextColor(0xFFFFFFFF);
        errorText.setTextSize(12.5f);
        eb.addView(errorText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ebX = btn("✕", 15);
        ebX.setTextColor(0xFFFFFFFF);
        ebX.setOnClickListener(v -> errorBar.setVisibility(View.GONE));
        eb.addView(ebX);
        eb.setVisibility(View.GONE);
        errorBar = eb;
        root.addView(eb);

        View bar = buildBar();
        if (!prefToolbarBottom) root.addView(bar);

        // --- stage ---
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pp.gravity = Gravity.TOP;
        stage.addView(progress, pp);

        homeView = buildHome();
        stage.addView(homeView);
        panelView = buildPanel();
        panelView.setVisibility(View.GONE);
        stage.addView(panelView);
        tabsView = buildTabsView();
        tabsView.setVisibility(View.GONE);
        stage.addView(tabsView);
        settingsView = buildSettings();
        settingsView.setVisibility(View.GONE);
        stage.addView(settingsView);
        menuView = buildMenu();
        menuView.setVisibility(View.GONE);
        stage.addView(menuView);
        pageView = buildPage();
        pageView.setVisibility(View.GONE);
        stage.addView(pageView);
        undoBar = buildUndoBar();
        undoBar.setVisibility(View.GONE);
        stage.addView(undoBar);

        findBar = buildFindBar();
        findBar.setVisibility(View.GONE);
        if (prefToolbarBottom) {
            root.addView(findBar);
            root.addView(bar);
        } else {
            root.addView(findBar);
        }
        Edge.pad(root);
        setContentView(root);
    }

    View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cBg);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView homeBtn = btn("⌂", 20);
        homeBtn.setOnClickListener(v -> showHome());
        bar.addView(homeBtn);

        LinearLayout pillBox = new LinearLayout(this);
        pillBox.setOrientation(LinearLayout.HORIZONTAL);
        pillBox.setGravity(Gravity.CENTER_VERTICAL);
        pillBox.setBackground(pill(cCard2, 24));
        pillBox.setPadding(dp(8), 0, dp(12), 0);
        TextView infoI = new TextView(this);
        infoI.setText("ⓘ");
        infoI.setTextSize(15);
        infoI.setTextColor(cDim);
        infoI.setPadding(dp(4), 0, dp(8), 0);
        pillBox.addView(infoI);
        urlText = new EditText(this);
        urlText.setSingleLine(true);
        urlText.setTextSize(14f);
        urlText.setTextColor(cText);
        urlText.setHintTextColor(cDim);
        urlText.setHint("Search or type web address");
        urlText.setBackground(null);
        urlText.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlText.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(urlText.getText().toString());
                hideKeyboard();
                urlText.clearFocus();
                return true;
            }
            return false;
        });
        urlText.setOnFocusChangeListener((v, has) -> {
            urlEditing = has;
            if (has) urlText.selectAll();
            else syncUrlBar();
        });
        pillBox.addView(urlText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pbp.leftMargin = dp(2);
        pbp.rightMargin = dp(8);
        bar.addView(pillBox, pbp);

        tabCountBtn = new TextView(this);
        tabCountBtn.setTextSize(12.5f);
        tabCountBtn.setTextColor(cText);
        tabCountBtn.setGravity(Gravity.CENTER);
        tabCountBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable sq = new GradientDrawable();
        sq.setColor(0x00000000);
        sq.setStroke(dp(2), cDim);
        sq.setCornerRadius(dp(6));
        tabCountBtn.setBackground(sq);
        int ts = dp(30);
        tabCountBtn.setLayoutParams(new LinearLayout.LayoutParams(ts, ts));
        tabCountBtn.setOnClickListener(v -> { tabsIncognito = activeTab().priv; showTabs(); });
        bar.addView(tabCountBtn);

        TextView menuBtn = btn("⋮", 20);
        menuBtn.setOnClickListener(v -> {
            refreshMenu();
            menuView.setVisibility(View.VISIBLE);
        });
        bar.addView(menuBtn);
        return bar;
    }

    // ============================================================ home page
    View buildHome() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(16));
        sv.addView(col);

        // circular shortcuts, Kiwi-style
        Object[][] sites = {
                {"f", 0xFF1877F2, "Facebook", "https://m.facebook.com/"},
                {"▶", 0xFFFF0000, "YouTube", "https://m.youtube.com/"},
                {"G", 0xFF34A853, "Google", "https://www.google.com/"},
                {"◎", 0xFFE1306C, "Instagram", "https://www.instagram.com/"},
                {"♪", 0xFF111111, "TikTok", "https://www.tiktok.com/"},
                {"𝕏", 0xFF111111, "X", "https://x.com/"},
                {"W", 0xFF666666, "Wikipedia", "https://en.wikipedia.org/"},
                {"a", 0xFFFF9900, "Amazon", "https://www.amazon.com/"},
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        for (Object[] s : sites) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            TextView icon = new TextView(this);
            icon.setText((String) s[0]);
            icon.setTextSize(19);
            icon.setTextColor(0xFFFFFFFF);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(circle((Integer) s[1]));
            int sz = dp(52);
            icon.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            cell.addView(icon);
            TextView nm = label((String) s[2], 11, cDim, false);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(5), 0, 0);
            cell.addView(nm);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = 0;
            gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cell.setLayoutParams(gp);
            final String u = (String) s[3];
            cell.setOnClickListener(v -> navigate(u));
            grid.addView(cell);
        }
        col.addView(grid);

        // Discover header + settings gear (Kiwi-style) — content = recent history
        LinearLayout dHead = new LinearLayout(this);
        dHead.setGravity(Gravity.CENTER_VERTICAL);
        dHead.setPadding(0, dp(18), 0, dp(8));
        dHead.addView(label("Discover", 15, cText, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView gear = btn("⚙", 17);
        gear.setTextColor(cDim);
        gear.setOnClickListener(v -> showSettings());
        dHead.addView(gear);
        col.addView(dHead);

        LinearLayout recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        recentBox.setTag("recentBox");
        col.addView(recentBox);

        // diagnostics card (kept — it solved the DNS mystery)
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(pill(cCard, 14));
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icp.topMargin = dp(14);
        infoCard.setLayoutParams(icp);
        netInfo = new TextView(this);
        netInfo.setTextSize(12f);
        netInfo.setTextColor(cDim);
        netInfo.setText("Connection test — running…");
        infoCard.addView(netInfo);
        infoCard.setOnClickListener(v -> runNetTest());
        col.addView(infoCard);
        return sv;
    }

    void refreshRecent() {
        LinearLayout box = homeView.findViewWithTag("recentBox");
        if (box == null) return;
        box.removeAllViews();
        // Discover shows the user's own bookmarks — never the previous tab's
        // history, so a fresh tab never displays the last tab's link.
        List<BrowserStore.Entry> saved = BrowserStore.bookmarks(this);
        int n = Math.min(saved.size(), 6);
        if (n == 0) {
            box.addView(label("Pages you bookmark (☆) will appear here.",
                    12.5f, cDim, false));
            return;
        }
        for (int i = 0; i < n; i++) {
            final BrowserStore.Entry e = saved.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(7), dp(2), dp(7));
            TextView dot = new TextView(this);
            dot.setText(e.title.isEmpty() ? "•" : e.title.substring(0, 1).toUpperCase(Locale.ROOT));
            dot.setTextSize(13);
            dot.setTextColor(0xFFFFFFFF);
            dot.setGravity(Gravity.CENTER);
            dot.setBackground(circle(cCard2));
            int sz = dp(34);
            dot.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            row.addView(dot);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, 0, 0);
            texts.addView(label(e.title.isEmpty() ? hostOf(e.url) : e.title, 13, cText, false));
            texts.addView(label(e.url, 11, cDim, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> navigate(e.url));
            box.addView(row);
        }
    }

    // ============================================================ Kiwi-style menu panel
    LinearLayout menuList;

    View buildMenu() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x66000000);
        wrap.setOnClickListener(v -> menuView.setVisibility(View.GONE));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable drawerBg = new GradientDrawable();
        drawerBg.setColor(cCard);
        drawerBg.setCornerRadii(new float[]{dp(18), dp(18), 0, 0, 0, 0, dp(18), dp(18)});
        panel.setBackground(drawerBg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82f),
                ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.END;
        panel.setLayoutParams(plp);
        panel.setPadding(0, dp(10), 0, dp(10));
        panel.setOnClickListener(v -> {});

        // quick actions row: forward · star · download · reload
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER);
        quick.setPadding(dp(8), dp(4), dp(8), dp(6));
        TextView qFwd = btn("→", 19);
        qFwd.setOnClickListener(v -> {
            BTab t = activeTab();
            if (t.wv != null && t.wv.canGoForward()) t.wv.goForward();
            menuView.setVisibility(View.GONE);
        });
        quick.addView(qFwd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        starBtn = btn("☆", 19);
        starBtn.setOnClickListener(v -> { toggleStar(); menuView.setVisibility(View.GONE); });
        quick.addView(starBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView qDl = btn("⬇", 18);
        qDl.setOnClickListener(v -> {
            BTab t = activeTab();
            menuView.setVisibility(View.GONE);
            if (!t.url.isEmpty()) DohFetch.download(this, t.url, null, ui, this::toastBg);
        });
        quick.addView(qDl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView qRe = btn("⟳", 19);
        qRe.setOnClickListener(v -> {
            BTab t = activeTab();
            if (t.wv != null) t.wv.reload();
            menuView.setVisibility(View.GONE);
        });
        quick.addView(qRe, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(quick);
        View div = new View(this);
        div.setBackgroundColor(cCard2);
        panel.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView sv = new ScrollView(this);
        menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(menuList);
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        wrap.addView(panel);
        return wrap;
    }

    LinearLayout menuRow(String icon, String text, boolean dimIcon) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(14), dp(13));
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(17);
        ic.setTextColor(dimIcon ? cDim : cText);
        ic.setGravity(Gravity.CENTER);
        int sz = dp(30);
        ic.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        row.addView(ic);
        TextView tx = label(text, 14.5f, cText, false);
        tx.setPadding(dp(12), 0, 0, 0);
        row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    void refreshMenu() {
        menuList.removeAllViews();
        LinearLayout r;
        r = menuRow("＋", "New tab", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); newTab(false, ""); });
        menuList.addView(r);
        r = menuRow("🕵", "New Incognito tab", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); newTab(true, ""); });
        menuList.addView(r);
        r = menuRow("🕘", "History", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showPanel(0); });
        menuList.addView(r);
        r = menuRow("⬇", "Downloads", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showPanel(2); });
        menuList.addView(r);
        r = menuRow("★", "Bookmarks", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showPanel(1); });
        menuList.addView(r);
        r = menuRow("🗂", "Recent tabs", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); tabsIncognito = activeTab().priv; showTabs(); });
        menuList.addView(r);
        r = menuRow("🚫", "Ad blocking: " + (prefAdBlock ? "On" : "Off"), false);
        r.setOnClickListener(v -> {
            prefAdBlock = !prefAdBlock;
            bp.edit().putBoolean("adblock", prefAdBlock).apply();
            menuView.setVisibility(View.GONE);
            BTab t = activeTab();
            if (t.wv != null && !t.url.isEmpty()) t.wv.reload();
            toast("Ad blocking: " + (prefAdBlock ? "On" : "Off"));
        });
        menuList.addView(r);
        r = menuRow("⬇", "Page media", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showPanel(3); });
        menuList.addView(r);
        r = menuRow("📄", "Save page", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); savePage(); });
        menuList.addView(r);
        r = menuRow("🗄", "Saved pages", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showPanel(4); });
        menuList.addView(r);
        r = menuRow("⌂", "Add to home screen", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); addToHomeScreen(); });
        menuList.addView(r);
        r = menuRow("⛶", fsOn ? "Exit full-screen" : "Full-screen", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); toggleFullscreen(); });
        menuList.addView(r);
        r = menuRow("⟨⟩", "View source", false);
        r.setOnClickListener(v -> {
            BTab t = activeTab();
            menuView.setVisibility(View.GONE);
            if (!t.url.isEmpty()) navigate("view-source:" + t.url);
            else toast("Open a page first");
        });
        menuList.addView(r);
        r = menuRow("🌙", prefNight == 1 ? "Night mode: On" : prefNight == 2 ? "Night mode: Off" : "Night mode: Auto", false);
        r.setOnClickListener(v -> {
            prefNight = (prefNight + 1) % 3;
            bp.edit().putInt("night_mode", prefNight).apply();
            applySettingsAll();
            menuView.setVisibility(View.GONE);
            toast("Night mode: " + (prefNight == 1 ? "On" : prefNight == 2 ? "Off" : "Auto"));
        });
        menuList.addView(r);
        r = menuRow("🔍", "Find in page", false);
        r.setOnClickListener(v -> {
            menuView.setVisibility(View.GONE);
            findBar.setVisibility(View.VISIBLE);
            findText.requestFocus();
            showKeyboard();
        });
        menuList.addView(r);
        r = menuRow(desktopMode ? "📱" : "🖥", desktopMode ? "Mobile site" : "Desktop site", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); toggleDesktop(); });
        menuList.addView(r);
        r = menuRow("↗", "Share page", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); sharePage(); });
        menuList.addView(r);
        r = menuRow("⚙", "Settings", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showSettings(); });
        menuList.addView(r);
        r = menuRow("🧹", "Clear data", false);
        r.setOnClickListener(v -> { menuView.setVisibility(View.GONE); showClearPage(); });
        menuList.addView(r);
        r = menuRow("✕", "Exit", false);
        r.setOnClickListener(v -> finish());
        menuList.addView(r);
    }

    // ============================================================ panel (history / bookmarks / downloads)
    LinearLayout panelList;
    EditText panelSearch;
    int panelMode; // 0 history, 1 bookmarks, 2 downloads

    View buildPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(cBg);
        col.setPadding(dp(14), dp(12), dp(14), dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("←", 20);
        back.setOnClickListener(v -> panelView.setVisibility(View.GONE));
        head.addView(back);
        TextView h = label("", 17, cText, true);
        h.setTag("panelTitle");
        head.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = btn("Clear", 13);
        clear.setTag("panelClear");
        head.addView(clear);
        col.addView(head);
        panelSearch = new EditText(this);
        panelSearch.setSingleLine(true);
        panelSearch.setTextSize(13.5f);
        panelSearch.setTextColor(cText);
        panelSearch.setHintTextColor(cDim);
        panelSearch.setHint("Search");
        panelSearch.setBackground(pill(cCard2, 20));
        panelSearch.setPadding(dp(14), dp(8), dp(14), dp(8));
        panelSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { fillPanel(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        LinearLayout.LayoutParams spp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spp.topMargin = dp(8);
        panelSearch.setLayoutParams(spp);
        col.addView(panelSearch);
        ScrollView sv = new ScrollView(this);
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(panelList);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        svp.topMargin = dp(8);
        col.addView(sv, svp);
        return col;
    }

    void showPanel(int mode) {
        panelMode = mode;
        panelSearch.setText("");
        panelView.setVisibility(View.VISIBLE);
        tabsView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        pageView.setVisibility(View.GONE);
        fillPanel();
    }

    void fillPanel() {
        TextView h = panelView.findViewWithTag("panelTitle");
        TextView clear = panelView.findViewWithTag("panelClear");
        String q = panelSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        panelList.removeAllViews();
        if (panelMode == 2) {
            h.setText("Downloads");
            clear.setVisibility(View.VISIBLE);
            clear.setText("Clear");
            clear.setOnClickListener(v -> downloadsClearMenu());
            fillDownloads(q);
            return;
        }
        if (panelMode == 3) {
            h.setText("Page media");
            clear.setVisibility(View.VISIBLE);
            clear.setText("Clear");
            clear.setOnClickListener(v -> {
                BTab t = activeTab();
                synchronized (t.sniffed) { t.sniffed.clear(); }
                fillPanel();
            });
            fillSniffer(q);
            return;
        }
        if (panelMode == 4) {
            h.setText("Saved pages");
            clear.setVisibility(View.GONE);
            fillSavedPages(q);
            return;
        }
        boolean bookmarks = panelMode == 1;
        h.setText(bookmarks ? "Bookmarks" : "History");
        clear.setVisibility(bookmarks ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> {
            BrowserStore.clearHistory(this);
            fillPanel();
            refreshRecent();
        });
        List<BrowserStore.Entry> items = bookmarks
                ? BrowserStore.bookmarks(this) : BrowserStore.history(this);
        if (!bookmarks) {
            TextView link = label("Clear browsing data…", 13.5f, cAccent, false);
            link.setPadding(dp(2), dp(2), 0, dp(8));
            link.setOnClickListener(v -> showClearPage());
            panelList.addView(link);
        }
        int shown = 0;
        String lastDay = null;
        for (final BrowserStore.Entry e : items) {
            if (!q.isEmpty() && !e.title.toLowerCase(Locale.ROOT).contains(q)
                    && !e.url.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown++;
            if (!bookmarks && q.isEmpty()) {
                String dl = dayLabel(e.time);
                if (!dl.equals(lastDay)) {
                    lastDay = dl;
                    TextView dh = label(dl, 12.5f, cDim, true);
                    dh.setPadding(dp(2), dp(10), 0, dp(2));
                    panelList.addView(dh);
                }
            }
            panelList.addView(entryRow(e.title.isEmpty() ? hostOf(e.url) : e.title, e.url,
                    () -> { panelView.setVisibility(View.GONE); navigate(e.url); },
                    () -> {
                        if (panelMode == 1) BrowserStore.removeBookmark(this, e.url);
                        else BrowserStore.removeHistoryItem(this, e.url);
                        fillPanel();
                        refreshRecent();
                    }));
        }
        if (shown == 0) panelList.addView(label(
                items.isEmpty()
                        ? (bookmarks ? "No bookmarks yet. Tap ☆ on a page to save it." : "No history yet.")
                        : "No matches.", 13, cDim, false));
    }

    static String dayLabel(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH);
        String base = f.format(new Date(time));
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
            return "Today - " + base;
        now.add(Calendar.DATE, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
            return "Yesterday - " + base;
        return base;
    }

    LinearLayout entryRow(String title, String sub, Runnable onTap, Runnable onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        TextView dot = new TextView(this);
        dot.setText(title.isEmpty() ? "•" : title.substring(0, 1).toUpperCase(Locale.ROOT));
        dot.setTextSize(13);
        dot.setTextColor(0xFFFFFFFF);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(circle(cCard2));
        int sz = dp(36);
        dot.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        row.addView(dot);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView titleTv = label(title, 13.5f, cText, false);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleTv);
        TextView u = label(sub, 11, cDim, false);
        u.setPadding(0, dp(2), 0, 0);
        u.setSingleLine(true);
        u.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(u);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onDelete != null) {
            TextView x = btn("✕", 13);
            x.setTextColor(cDim);
            x.setOnClickListener(v -> onDelete.run());
            row.addView(x);
        }
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    void refreshDownloadsPanel() {
        if (panelMode == 2 && panelView.getVisibility() == View.VISIBLE) {
            panelList.removeAllViews();
            fillDownloads(panelSearch.getText().toString().trim().toLowerCase(Locale.ROOT));
        }
    }

    void fillDownloads(String q) {
        // live downloads first, with progress + speed + pause/cancel
        ArrayList<TmDownloadService.Task> actives =
                new ArrayList<>(TmDownloadService.ACTIVE.values());
        actives.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        if (!actives.isEmpty()) {
            TextView h = label("Downloading", 13, cAccent, true);
            h.setPadding(dp(2), dp(2), 0, dp(6));
            panelList.addView(h);
            for (TmDownloadService.Task p : actives) panelList.addView(activeDlRow(p));
        }
        ArrayList<TmDownloadService.Task> paused =
                new ArrayList<>(TmDownloadService.PAUSED.values());
        paused.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (!paused.isEmpty()) {
            TextView h = label("Paused", 13, cAccent, true);
            h.setPadding(dp(2), dp(12), 0, dp(6));
            panelList.addView(h);
            for (TmDownloadService.Task p : paused) panelList.addView(pausedDlRow(p));
        }
        ArrayList<String[]> files = queryDownloads();
        int shown = 0;
        if (!files.isEmpty()) {
            TextView h2 = label("Completed", 13, cAccent, true);
            h2.setPadding(dp(2), dp(12), 0, dp(6));
            panelList.addView(h2);
        }
        for (final String[] f : files) {
            if (!q.isEmpty() && !f[0].toLowerCase(Locale.ROOT).contains(q)) continue;
            shown++;
            panelList.addView(downloadRow(f));
        }
        if (shown == 0 && actives.isEmpty() && paused.isEmpty()) panelList.addView(label(
                "No downloads yet. Files you download with TM Browser are saved "
                        + "to Movies / Music / Downloads › TM Player.", 13, cDim, false));
        boolean recentChange = System.currentTimeMillis() - TmDownloadService.lastChangeMs < 2500;
        if ((!actives.isEmpty() || recentChange) && panelMode == 2
                && panelView.getVisibility() == View.VISIBLE) {
            ui.postDelayed(this::refreshDownloadsPanel, 800);
        }
    }

    String glyphFor(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("video") || isMediaUrl(name) && !isAudioUrl(name)) return "▶";
        if (m.startsWith("audio") || isAudioUrl(name)) return "♪";
        if (m.startsWith("image")) return "🖼";
        if (m.contains("pdf")) return "📄";
        if (m.contains("zip") || m.contains("archive") || m.contains("android.package")) return "📦";
        return "⬇";
    }

    View thumbBox(String name, String mime, String uriStr) {
        FrameLayout box = new FrameLayout(this);
        int sz = dp(46);
        box.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        TextView glyph = new TextView(this);
        glyph.setText(glyphFor(name, mime));
        glyph.setTextSize(17);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(circle(cCard2));
        box.addView(glyph, new FrameLayout.LayoutParams(sz, sz));
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (Build.VERSION.SDK_INT >= 29 && uriStr != null && uriStr.startsWith("content://")
                && (m.startsWith("video") || m.startsWith("image"))) {
            final android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setVisibility(View.GONE);
            box.addView(iv, new FrameLayout.LayoutParams(sz, sz));
            final Uri uri = Uri.parse(uriStr);
            new Thread(() -> {
                try {
                    Bitmap bm = getContentResolver().loadThumbnail(
                            uri, new android.util.Size(120, 120), null);
                    if (bm != null) ui.post(() -> { iv.setImageBitmap(bm); iv.setVisibility(View.VISIBLE); });
                } catch (Exception ignored) {}
            }).start();
        }
        return box;
    }

    LinearLayout downloadRow(final String[] f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        row.addView(thumbBox(f[0], f[3], f[2]));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView nm = label(f[0], 13.5f, cText, false);
        nm.setMaxLines(2);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(nm);
        TextView sz = label(f[1], 11, cDim, false);
        sz.setPadding(0, dp(2), 0, 0);
        texts.addView(sz);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // ✕ removes the entry from the list only — the file itself is kept.
        TextView del = btn("✕", 14);
        del.setTextColor(cDim);
        del.setOnClickListener(v -> {
            hideDownloadEntry(f[2]);
            toast("Removed from list — file kept");
            refreshDownloadsPanel();
        });
        row.addView(del);
        row.setOnClickListener(v -> openDownloaded(f[2], f[3]));
        // Long-press deletes the actual file, after confirmation.
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete file?")
                    .setMessage(f[0] + "\n\nThis deletes the file from your phone.")
                    .setPositiveButton("Delete", (d, w) -> { deleteDownloaded(f); refreshDownloadsPanel(); })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        return row;
    }

    java.util.Set<String> hiddenDownloads() {
        return new java.util.HashSet<>(bp.getStringSet("dl_hidden", new java.util.HashSet<>()));
    }

    void hideDownloadEntry(String uriStr) {
        java.util.Set<String> s = hiddenDownloads();
        s.add(uriStr);
        bp.edit().putStringSet("dl_hidden", s).apply();
    }

    void copyText(String s) {
        ClipboardManager cbm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cbm != null) cbm.setPrimaryClip(ClipData.newPlainText("text", s));
        toast("Copied: " + s);
    }

    String dlLocationLabel() {
        String u = dlTreeUri(this);
        if (u.isEmpty()) return "Internal storage (TM Player folders)";
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(u));
            int c = id.indexOf(':');
            String p = c >= 0 ? id.substring(c + 1) : id;
            return "Folder: " + (p.isEmpty() ? id : p);
        } catch (Exception e) {
            return "Custom folder";
        }
    }

    void pickDownloadFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("Folder picker not available on this device");
        }
    }

    void downloadsClearMenu() {
        ArrayList<String[]> files = queryDownloads();
        if (files.isEmpty()) { toast("Nothing to clear"); return; }
        chooser("Clear downloads", null,
                new String[]{"🧹  Clear list only (keep the files)",
                        "🗑  Delete all files (" + files.size() + ")"},
                new Runnable[]{
                        () -> {
                            java.util.Set<String> s = hiddenDownloads();
                            for (String[] f : files) s.add(f[2]);
                            bp.edit().putStringSet("dl_hidden", s).apply();
                            toast("List cleared — files kept in the TM Player folders");
                            refreshDownloadsPanel();
                        },
                        () -> new AlertDialog.Builder(this)
                                .setTitle("Delete all downloaded files?")
                                .setMessage(files.size() + " files will be deleted from your phone.")
                                .setPositiveButton("Delete all", (d, w) -> {
                                    int ok = 0;
                                    for (String[] f : files) if (deleteFileQuiet(f)) ok++;
                                    toast("Deleted " + ok + " file" + (ok == 1 ? "" : "s"));
                                    refreshDownloadsPanel();
                                })
                                .setNegativeButton("Cancel", null)
                                .show()
                });
    }

    boolean deleteFileQuiet(String[] f) {
        try {
            Uri u = Uri.parse(f[2]);
            if ("content".equals(u.getScheme()))
                return getContentResolver().delete(u, null, null) > 0;
            if ("file".equals(u.getScheme())) {
                File file = new File(u.getPath());
                return file.exists() && file.delete();
            }
        } catch (Exception ignored) {}
        return false;
    }

    void deleteDownloaded(String[] f) {
        toast(deleteFileQuiet(f) ? "Deleted: " + f[0] : "Could not delete this file");
    }

    LinearLayout pausedDlRow(final TmDownloadService.Task p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        row.addView(thumbBox(p.name, p.mime, null));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView nm = label(p.name, 13.5f, cText, false);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(nm);
        String status = humanSize(p.done);
        if (p.total > 0) status += " / " + humanSize(p.total)
                + " • " + (p.done * 100 / p.total) + "%";
        status += " • Paused";
        TextView st = label(status, 11, cDim, false);
        st.setPadding(0, dp(3), 0, 0);
        texts.addView(st);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView resume = btn("▶", 15);
        resume.setTextColor(cAccent);
        resume.setOnClickListener(v -> {
            TmDownloadService.enqueue(this, p.url, p.name, p.ua, p.referer, this::toastBg);
            ui.postDelayed(this::refreshDownloadsPanel, 350);
        });
        row.addView(resume);
        TextView del = btn("🗑", 14);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete partial download?")
                .setMessage(p.name)
                .setPositiveButton("Delete", (d, w) -> {
                    TmDownloadService.deletePaused(this, p.url);
                    refreshDownloadsPanel();
                })
                .setNegativeButton("Cancel", null)
                .show());
        row.addView(del);
        return row;
    }

    LinearLayout activeDlRow(final TmDownloadService.Task p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        row.addView(thumbBox(p.name, null, null));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView nm = label(p.name, 13.5f, cText, false);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(nm);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        blp.topMargin = dp(5);
        bar.setLayoutParams(blp);
        bar.setMax(1000);
        if (p.total > 0) {
            bar.setIndeterminate(false);
            bar.setProgress((int) (p.done * 1000 / p.total));
        } else {
            bar.setIndeterminate(true);
        }
        try {
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(cAccent));
        } catch (Exception ignored) {}
        texts.addView(bar);
        String status = humanSize(p.done);
        if (p.total > 0) status += " / " + humanSize(p.total)
                + " • " + (p.done * 100 / p.total) + "%";
        status += " • " + humanSize((long) p.speedBps()) + "/s";
        TextView st = label(status, 11, cDim, false);
        st.setPadding(0, dp(3), 0, 0);
        texts.addView(st);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pause = btn("⏸", 14);
        pause.setTextColor(cAccent);
        pause.setOnClickListener(v -> {
            TmDownloadService.pause(p.url);
            ui.postDelayed(this::refreshDownloadsPanel, 350);
        });
        row.addView(pause);
        TextView cancel = btn("✕", 14);
        cancel.setTextColor(cDim);
        cancel.setOnClickListener(v -> {
            TmDownloadService.cancel(p.url);
            ui.postDelayed(this::refreshDownloadsPanel, 350);
        });
        row.addView(cancel);
        return row;
    }

    // rows: {name, sizeLabel, uriString, mime}
    ArrayList<String[]> queryDownloads() {
        ArrayList<String[]> out = new ArrayList<>();
        java.util.Set<String> hidden = hiddenDownloads();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri[] colls = {MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI};
            for (Uri coll : colls) {
                try (Cursor c = getContentResolver().query(coll,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{"%TM Player%"},
                        MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c != null) while (c.moveToNext() && out.size() < 150) {
                        long id = c.getLong(0);
                        String name = c.getString(1);
                        long size = c.getLong(2);
                        String mime = c.getString(3);
                        Uri u = Uri.withAppendedPath(coll, String.valueOf(id));
                        if (hidden.contains(u.toString())) continue;
                        out.add(new String[]{name == null ? "file" : name,
                                humanSize(size), u.toString(), mime == null ? "*/*" : mime});
                    }
                } catch (Exception ignored) {}
            }
        } else {
            File[] dirs = {
                    new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TM Player"),
                    new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "TM Player"),
                    new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TM Player")};
            for (File d : dirs) {
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    String uStr = Uri.fromFile(f).toString();
                    if (hidden.contains(uStr)) continue;
                    out.add(new String[]{f.getName(), humanSize(f.length()),
                            uStr,
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                    f.getName().contains(".")
                                            ? f.getName().substring(f.getName().lastIndexOf('.') + 1) : "")});
                }
            }
        }
        // files in the user's custom (SAF) download folder
        String treeUri = dlTreeUri(this);
        if (!treeUri.isEmpty()) {
            try {
                Uri tree = Uri.parse(treeUri);
                String treeId = android.provider.DocumentsContract.getTreeDocumentId(tree);
                Uri children = android.provider.DocumentsContract
                        .buildChildDocumentsUriUsingTree(tree, treeId);
                try (Cursor c = getContentResolver().query(children,
                        new String[]{
                                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},
                        null, null, null)) {
                    if (c != null) while (c.moveToNext() && out.size() < 200) {
                        String mime = c.getString(3);
                        if (mime == null || mime.equals(
                                android.provider.DocumentsContract.Document.MIME_TYPE_DIR)) continue;
                        Uri docUri = android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(tree, c.getString(0));
                        if (hidden.contains(docUri.toString())) continue;
                        long size = c.isNull(2) ? 0 : c.getLong(2);
                        out.add(new String[]{c.getString(1) == null ? "file" : c.getString(1),
                                humanSize(size), docUri.toString(), mime});
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    static String humanSize(long b) {
        if (b <= 0) return "";
        if (b >= 1_000_000) return String.format(Locale.ROOT, "%.1f MB", b / 1_000_000.0);
        if (b >= 1_000) return String.format(Locale.ROOT, "%.0f KB", b / 1_000.0);
        return b + " B";
    }

    void fillSniffer(String q) {
        BTab t = activeTab();
        TextView scan = label("🔍  Scan this page for videos, images & files", 14, cAccent, false);
        scan.setPadding(dp(2), dp(4), 0, dp(12));
        scan.setOnClickListener(v -> scanPageMedia());
        panelList.addView(scan);
        ArrayList<String> items;
        synchronized (t.sniffed) { items = new ArrayList<>(t.sniffed); }
        Collections.reverse(items);
        int shown = 0;
        for (final String u : items) {
            if (!q.isEmpty() && !u.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown++;
            panelList.addView(entryRow(filenameFromUrl(u), u,
                    () -> {
                        ArrayList<String> labs = new ArrayList<>();
                        ArrayList<Runnable> acts = new ArrayList<>();
                        if (isMediaUrl(u)) {
                            labs.add("▶  Play in TM Player");
                            acts.add(() -> playInTmPlayer(u));
                        }
                        labs.add("⬇  Download");
                        acts.add(() -> DohFetch.download(this, u, null, ui, this::toastBg));
                        labs.add("↗  Open");
                        acts.add(() -> { panelView.setVisibility(View.GONE); navigate(u); });
                        labs.add("⧉  Copy link");
                        acts.add(() -> {
                            ClipboardManager cbm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cbm != null) cbm.setPrimaryClip(ClipData.newPlainText("link", u));
                            toast("Link copied");
                        });
                        chooser(filenameFromUrl(u), u, labs.toArray(new String[0]),
                                acts.toArray(new Runnable[0]));
                    }, null));
        }
        if (shown == 0) panelList.addView(label(
                "Nothing found yet. Play a video or open an image on the page, or tap "
                        + "Scan above — direct video, image and file links the page uses "
                        + "will appear here, ready to download.",
                13, cDim, false));
    }

    // Collect direct media/image/file URLs out of the page itself (video and
    // audio elements, sources, large images, file links, og:video). Chunked
    // player streams (blob:/MSE — YouTube, Facebook) have no direct file URL,
    // so they never appear here.
    void scanPageMedia() {
        BTab t = activeTab();
        if (t.wv == null || t.url.isEmpty()) { toast("Open a page first"); return; }
        String js = "(function(){var out=[];var seen={};"
                + "function add(u){if(!u||u.indexOf('http')!==0||seen[u])return;seen[u]=1;out.push(u);}"
                + "document.querySelectorAll('video,audio').forEach(function(el){add(el.currentSrc);add(el.src);});"
                + "document.querySelectorAll('source').forEach(function(el){add(el.src);});"
                + "document.querySelectorAll('img').forEach(function(el){if(el.naturalWidth>=150||el.naturalWidth===0){add(el.currentSrc);add(el.src);}});"
                + "document.querySelectorAll('a[href]').forEach(function(el){var h=el.href;"
                + "if(/\\.(mp4|mkv|webm|mov|m4v|3gp|avi|mp3|m4a|aac|flac|wav|ogg|opus|jpg|jpeg|png|webp|gif|zip|rar|7z|pdf|apk)(\\?|#|$)/i.test(h))add(h);});"
                + "var og=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:url\"],meta[property=\"og:video:secure_url\"]');"
                + "if(og)add(og.getAttribute('content'));"
                + "return JSON.stringify(out);})()";
        t.wv.evaluateJavascript(js, value -> {
            int added = 0;
            try {
                Object v = new org.json.JSONTokener(value).nextValue();
                if (v instanceof String) {
                    JSONArray arr = new JSONArray((String) v);
                    synchronized (t.sniffed) {
                        for (int i = 0; i < arr.length(); i++) {
                            String u = arr.optString(i);
                            if (!u.isEmpty() && !t.sniffed.contains(u) && t.sniffed.size() < 200) {
                                t.sniffed.add(u);
                                added++;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            toast(added > 0 ? "Found " + added + " new file" + (added == 1 ? "" : "s")
                    : "No new direct files found on this page");
            if (panelMode == 3 && panelView.getVisibility() == View.VISIBLE) fillPanel();
        });
    }

    File savedPagesDir() {
        File d = new File(getFilesDir(), "saved_pages");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    void fillSavedPages(String q) {
        File[] fs = savedPagesDir().listFiles();
        ArrayList<File> files = new ArrayList<>();
        if (fs != null) for (File f : fs) if (f.isFile()) files.add(f);
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int shown = 0;
        for (final File f : files) {
            String name = f.getName().replaceFirst("\\.mht$", "").replace('_', ' ');
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown++;
            panelList.addView(entryRow(name, humanSize(f.length()),
                    () -> {
                        panelView.setVisibility(View.GONE);
                        navigate("file://" + f.getAbsolutePath());
                    },
                    () -> { f.delete(); fillPanel(); }));
        }
        if (shown == 0) panelList.addView(label(
                "No saved pages yet. Open a page, then Menu → Save page to read it offline.",
                13, cDim, false));
    }

    void openDownloaded(String uriStr, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uriStr), mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { toast("No app can open this file"); }
    }

    // ============================================================ tab switcher (cards)
    LinearLayout tabsList;
    boolean tabsIncognito;
    TextView chipNormal, chipIncog;

    View buildTabsView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(cBg);
        col.setPadding(dp(14), dp(12), dp(14), dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = btn("＋", 20);
        plus.setOnClickListener(v -> { newTab(tabsIncognito, ""); tabsView.setVisibility(View.GONE); });
        head.addView(plus);
        head.addView(label("New tab", 16, cText, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView dots = btn("⋮", 18);
        dots.setOnClickListener(v -> showTabsPopup());
        head.addView(dots);
        TextView close = btn("✕", 16);
        close.setOnClickListener(v -> tabsView.setVisibility(View.GONE));
        head.addView(close);
        col.addView(head);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, dp(4));
        chipNormal = new TextView(this);
        chipNormal.setTextSize(13);
        chipNormal.setGravity(Gravity.CENTER);
        chipNormal.setPadding(dp(14), dp(7), dp(14), dp(7));
        chipNormal.setOnClickListener(v -> { tabsIncognito = false; showTabs(); });
        chips.addView(chipNormal);
        chipIncog = new TextView(this);
        chipIncog.setTextSize(13);
        chipIncog.setGravity(Gravity.CENTER);
        chipIncog.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams cip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cip.leftMargin = dp(8);
        chipIncog.setLayoutParams(cip);
        chipIncog.setOnClickListener(v -> { tabsIncognito = true; showTabs(); });
        chips.addView(chipIncog);
        col.addView(chips);
        ScrollView sv = new ScrollView(this);
        tabsList = new LinearLayout(this);
        tabsList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(tabsList);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    void showTabs() {
        captureThumb(activeTab());
        int nNorm = 0, nInc = 0;
        for (BTab t : TABS) { if (t.priv) nInc++; else nNorm++; }
        chipNormal.setText("Tabs · " + nNorm);
        chipIncog.setText("🕵 Incognito · " + nInc);
        chipNormal.setBackground(pill(!tabsIncognito ? cAccent : cCard2, 18));
        chipNormal.setTextColor(!tabsIncognito ? 0xFFFFFFFF : cDim);
        chipIncog.setBackground(pill(tabsIncognito ? cAccent : cCard2, 18));
        chipIncog.setTextColor(tabsIncognito ? 0xFFFFFFFF : cDim);
        tabsList.removeAllViews();
        int shown = 0;
        for (int i = 0; i < TABS.size(); i++) {
            final int idx = i;
            final BTab t = TABS.get(i);
            if (t.priv != tabsIncognito) continue;
            shown++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = pill(i == sActive ? cCard2 : cCard, 12);
            if (i == sActive) bg.setStroke(dp(2), cAccent);
            card.setBackground(bg);
            card.setPadding(dp(10), dp(8), dp(6), dp(10));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(10);
            card.setLayoutParams(cp);

            LinearLayout headRow = new LinearLayout(this);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            String ttl = t.title.isEmpty() ? (t.url.isEmpty() ? "New tab" : hostOf(t.url)) : t.title;
            headRow.addView(label((t.priv ? "🕵 " : "") + ttl, 13.5f, cText, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView x = btn("✕", 13);
            x.setTextColor(cDim);
            x.setOnClickListener(v -> { closeTab(idx); showTabs(); });
            headRow.addView(x);
            card.addView(headRow);

            if (t.thumb != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(t.thumb);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
                ivp.topMargin = dp(6);
                iv.setLayoutParams(ivp);
                card.addView(iv);
            } else {
                TextView u = label(t.url.isEmpty() ? "Start page" : t.url, 11.5f, cDim, false);
                u.setPadding(0, dp(4), 0, 0);
                card.addView(u);
            }
            card.setOnClickListener(v -> {
                sActive = idx;
                tabsView.setVisibility(View.GONE);
                bindActive();
            });
            tabsList.addView(card);
        }
        if (shown == 0) {
            TextView none = label(tabsIncognito
                    ? "No incognito tabs. Use ⋮ → New Incognito tab."
                    : "No tabs.", 13, cDim, false);
            none.setPadding(0, dp(14), 0, 0);
            tabsList.addView(none);
        }
        tabsView.setVisibility(View.VISIBLE);
        panelView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        pageView.setVisibility(View.GONE);
    }

    void showTabsPopup() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(pill(cCard, 14));
        card.setPadding(0, dp(6), 0, dp(6));
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout r;
        r = menuRow("＋", "New tab", false);
        r.setOnClickListener(v -> { dlg[0].dismiss(); newTab(false, ""); tabsView.setVisibility(View.GONE); });
        card.addView(r);
        r = menuRow("🕵", "New Incognito tab", false);
        r.setOnClickListener(v -> { dlg[0].dismiss(); newTab(true, ""); tabsView.setVisibility(View.GONE); });
        card.addView(r);
        r = menuRow("✕", "Close all tabs", false);
        r.setOnClickListener(v -> {
            dlg[0].dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("Close all tabs?")
                    .setMessage("Closed tabs can be restored with Undo right after closing.")
                    .setPositiveButton("Close all tabs", (d, w) -> closeAllTabs())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        card.addView(r);
        r = menuRow("⚙", "Settings", false);
        r.setOnClickListener(v -> { dlg[0].dismiss(); tabsView.setVisibility(View.GONE); showSettings(); });
        card.addView(r);
        dlg[0] = new AlertDialog.Builder(this).setView(card).create();
        if (dlg[0].getWindow() != null) {
            dlg[0].getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            android.view.WindowManager.LayoutParams lp = dlg[0].getWindow().getAttributes();
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(10);
            lp.y = dp(56);
            dlg[0].getWindow().setAttributes(lp);
        }
        dlg[0].show();
    }

    void captureThumb(BTab t) {
        if (t == null || t.wv == null || t.wv.getWidth() == 0 || t.wv.getHeight() == 0) return;
        try {
            int w = 360;
            int h = Math.min(520, Math.max(180, (int) (360f * t.wv.getHeight() / t.wv.getWidth())));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(b);
            float s = (float) w / t.wv.getWidth();
            cv.scale(s, s);
            t.wv.draw(cv);
            t.thumb = b;
        } catch (Exception ignored) {}
    }

    // ============================================================ generic sub-page
    LinearLayout pageList;
    TextView pageTitleTv;
    boolean clrHist = true, clrCookies = true, clrCache = true;
    long clrRangeMs = 0; // 0 = all time

    View buildPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(cBg);
        col.setPadding(dp(14), dp(12), dp(14), dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("←", 20);
        back.setOnClickListener(v -> pageView.setVisibility(View.GONE));
        head.addView(back);
        pageTitleTv = label("", 18, cText, true);
        pageTitleTv.setPadding(dp(6), 0, 0, 0);
        head.addView(pageTitleTv);
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        pageList = new LinearLayout(this);
        pageList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(pageList);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    void showPage(String title) {
        pageTitleTv.setText(title);
        pageList.removeAllViews();
        pageView.setVisibility(View.VISIBLE);
        panelView.setVisibility(View.GONE);
        tabsView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        menuView.setVisibility(View.GONE);
    }

    LinearLayout radioRow(String title, String sub, boolean selected,
            Runnable onTap, Runnable onDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(12), dp(2), dp(12));
        TextView radio = new TextView(this);
        radio.setText(selected ? "●" : "○");
        radio.setTextSize(17);
        radio.setTextColor(selected ? cAccent : cDim);
        radio.setGravity(Gravity.CENTER);
        int rs = dp(32);
        radio.setLayoutParams(new LinearLayout.LayoutParams(rs, rs));
        row.addView(radio);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        texts.addView(label(title, 15, cText, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = label(sub, 12, cDim, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onDelete != null) {
            TextView x = btn("✕", 13);
            x.setTextColor(cDim);
            x.setOnClickListener(v -> onDelete.run());
            row.addView(x);
        }
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    LinearLayout checkRow(String title, String sub, boolean checked, Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(12), dp(2), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(title, 15, cText, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = label(sub, 12, cDim, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView box = new TextView(this);
        box.setText(checked ? "☑" : "☐");
        box.setTextSize(20);
        box.setTextColor(checked ? cAccent : cDim);
        row.addView(box);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    // ---------- search engine page (Kiwi-style + custom engines) ----------
    void showSearchPage() {
        showPage("Search engine");
        for (int i = 0; i < ENGINES.length; i++) {
            final int idx = i;
            pageList.addView(radioRow(ENGINES[i][0], hostOf(ENGINES[i][1]),
                    prefCustomSel < 0 && prefSearch == idx,
                    () -> {
                        prefSearch = idx;
                        prefCustomSel = -1;
                        bp.edit().putInt("search_engine", idx)
                                .putInt("search_custom_sel", -1).apply();
                        showSearchPage();
                    }, null));
        }
        if (!customEngines.isEmpty()) {
            TextView h = label("Custom engines", 13, cAccent, true);
            h.setPadding(0, dp(16), 0, dp(2));
            pageList.addView(h);
            for (int i = 0; i < customEngines.size(); i++) {
                final int idx = i;
                pageList.addView(radioRow(customEngines.get(i)[0], customEngines.get(i)[1],
                        prefCustomSel == idx,
                        () -> {
                            prefCustomSel = idx;
                            bp.edit().putInt("search_custom_sel", idx).apply();
                            showSearchPage();
                        },
                        () -> {
                            customEngines.remove(idx);
                            if (prefCustomSel == idx) prefCustomSel = -1;
                            else if (prefCustomSel > idx) prefCustomSel--;
                            bp.edit().putInt("search_custom_sel", prefCustomSel).apply();
                            saveCustomEngines();
                            showSearchPage();
                        }));
            }
        }
        TextView add = label("＋  Add custom engine", 15, cAccent, false);
        add.setPadding(dp(4), dp(16), 0, dp(8));
        add.setOnClickListener(v -> addCustomEngineDialog());
        pageList.addView(add);
        TextView note = label("A custom engine is a search URL — put %s where the "
                + "search text goes, e.g. https://example.com/search?q=%s",
                12, cDim, false);
        note.setPadding(dp(4), dp(2), 0, 0);
        pageList.addView(note);
    }

    void addCustomEngineDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText nameIn = new EditText(this);
        nameIn.setHint("Engine name (e.g. Brave Search)");
        nameIn.setSingleLine(true);
        nameIn.setTextColor(cText);
        nameIn.setHintTextColor(cDim);
        box.addView(nameIn);
        final EditText urlIn = new EditText(this);
        urlIn.setHint("Search URL with %s (e.g. https://search.brave.com/search?q=%s)");
        urlIn.setSingleLine(true);
        urlIn.setTextColor(cText);
        urlIn.setHintTextColor(cDim);
        box.addView(urlIn);
        new AlertDialog.Builder(this)
                .setTitle("Add custom engine")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String n = nameIn.getText().toString().trim();
                    String u = urlIn.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) { toast("Name and URL needed"); return; }
                    if (!u.contains("://")) u = "https://" + u;
                    customEngines.add(new String[]{n, u});
                    prefCustomSel = customEngines.size() - 1;
                    bp.edit().putInt("search_custom_sel", prefCustomSel).apply();
                    saveCustomEngines();
                    showSearchPage();
                    toast("Custom engine added");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- clear browsing data page (Kiwi-style) ----------
    String rangeName() {
        if (clrRangeMs == 0) return "All time";
        if (clrRangeMs <= 3_600_000L) return "Last hour";
        if (clrRangeMs <= 86_400_000L) return "Last 24 hours";
        if (clrRangeMs <= 7 * 86_400_000L) return "Last 7 days";
        return "Last 4 weeks";
    }

    void showClearPage() {
        showPage("Clear browsing data");
        LinearLayout rangeRow = new LinearLayout(this);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        rangeRow.setGravity(Gravity.CENTER_VERTICAL);
        rangeRow.setPadding(dp(2), dp(12), dp(2), dp(12));
        rangeRow.addView(label("Time range", 15, cText, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rangeRow.addView(label(rangeName() + "  ▾", 14, cDim, false));
        rangeRow.setOnClickListener(v -> {
            String[] names = {"Last hour", "Last 24 hours", "Last 7 days", "Last 4 weeks", "All time"};
            long[] vals = {3_600_000L, 86_400_000L, 7 * 86_400_000L, 28 * 86_400_000L, 0L};
            Runnable[] acts = new Runnable[names.length];
            for (int i = 0; i < names.length; i++) {
                final long val = vals[i];
                acts[i] = () -> { clrRangeMs = val; showClearPage(); };
            }
            chooser("Time range", null, names, acts);
        });
        pageList.addView(rangeRow);
        int histCount = BrowserStore.history(this).size();
        pageList.addView(checkRow("Browsing history", histCount + " items"
                + (clrRangeMs > 0 ? " (time range applies)" : ""), clrHist,
                () -> { clrHist = !clrHist; showClearPage(); }));
        pageList.addView(checkRow("Cookies and site data",
                "Signs you out of most sites (clears all)", clrCookies,
                () -> { clrCookies = !clrCookies; showClearPage(); }));
        pageList.addView(checkRow("Cached images and files",
                "Frees up space; sites may load slower next visit (clears all)", clrCache,
                () -> { clrCache = !clrCache; showClearPage(); }));
        TextView go = new TextView(this);
        go.setText("Clear data");
        go.setTextSize(15);
        go.setTextColor(0xFFFFFFFF);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(pill(cAccent, 24));
        go.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = dp(22);
        go.setLayoutParams(gp);
        go.setOnClickListener(v -> {
            if (!clrHist && !clrCookies && !clrCache) { toast("Select at least one item"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("Clear browsing data?")
                    .setMessage("The selected data will be deleted from TM Browser.")
                    .setPositiveButton("Clear", (d, w) -> doClearSelected())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        pageList.addView(go);
    }

    void doClearSelected() {
        if (clrHist) {
            if (clrRangeMs > 0) {
                long cutoff = System.currentTimeMillis() - clrRangeMs;
                for (BrowserStore.Entry e : BrowserStore.history(this))
                    if (e.time >= cutoff) BrowserStore.removeHistoryItem(this, e.url);
            } else {
                BrowserStore.clearHistory(this);
            }
            for (BTab t : TABS) if (t.wv != null) t.wv.clearHistory();
            refreshRecent();
        }
        if (clrCookies) {
            try {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {}
        }
        if (clrCache) {
            for (BTab t : TABS) if (t.wv != null) { t.wv.clearCache(true); t.wv.clearFormData(); }
        }
        pageView.setVisibility(View.GONE);
        toast("Browsing data cleared");
    }

    // ---------- undo bar for Close all tabs ----------
    ArrayList<BTab> closedSnapshot;
    TextView undoText;

    View buildUndoBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(pill(cCard2, 0));
        bar.setPadding(dp(16), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(lp);
        undoText = label("", 13.5f, cText, false);
        bar.addView(undoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView undo = label("Undo", 14, cAccent, true);
        undo.setPadding(dp(12), dp(6), dp(12), dp(6));
        undo.setOnClickListener(v -> restoreClosedTabs());
        bar.addView(undo);
        return bar;
    }

    void showUndoBar(ArrayList<BTab> snapshot) {
        closedSnapshot = snapshot;
        undoText.setText(snapshot.size() + (snapshot.size() == 1 ? " tab closed" : " tabs closed"));
        undoBar.setVisibility(View.VISIBLE);
        final ArrayList<BTab> snap = snapshot;
        ui.postDelayed(() -> {
            if (closedSnapshot == snap) {
                undoBar.setVisibility(View.GONE);
                closedSnapshot = null;
            }
        }, 6000);
    }

    void restoreClosedTabs() {
        if (closedSnapshot == null) return;
        for (BTab t : TABS) destroyWebView(t);
        TABS.clear();
        TABS.addAll(closedSnapshot);
        closedSnapshot = null;
        undoBar.setVisibility(View.GONE);
        sActive = 0;
        for (BTab t : TABS) t.home = t.url.isEmpty() && prefHomepage;
        showingHome = activeTab().home;
        bindActive();
    }

    void closeAllTabs() {
        ArrayList<BTab> snapshot = new ArrayList<>();
        for (BTab t : TABS) {
            BTab c = new BTab(t.priv);
            c.url = t.url;
            c.title = t.title;
            c.home = t.home;
            snapshot.add(c);
        }
        for (BTab t : TABS) destroyWebView(t);
        TABS.clear();
        BTab fresh = new BTab(false);
        fresh.home = prefHomepage;
        TABS.add(fresh);
        sActive = 0;
        showingHome = prefHomepage;
        tabsView.setVisibility(View.GONE);
        bindActive();
        showUndoBar(snapshot);
    }

    LinearLayout settingsList;

    View buildSettings() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(cBg);
        col.setPadding(dp(14), dp(12), dp(14), dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("←", 20);
        back.setOnClickListener(v -> settingsView.setVisibility(View.GONE));
        head.addView(back);
        head.addView(label("Settings", 18, cText, true));
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(settingsList);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    void section(String title) {
        TextView t = label(title, 13, cAccent, true);
        t.setPadding(0, dp(18), 0, dp(4));
        settingsList.addView(t);
    }

    void settingRow(String title, String sub, Runnable onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(11), dp(2), dp(11));
        row.addView(label(title, 15, cText, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = label(sub, 12.5f, cDim, false);
            s.setPadding(0, dp(2), 0, 0);
            row.addView(s);
        }
        if (onTap != null) row.setOnClickListener(v -> onTap.run());
        settingsList.addView(row);
        View div = new View(this);
        div.setBackgroundColor(cCard2);
        settingsList.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    void showSettings() {
        settingsList.removeAllViews();
        section("Basics");
        settingRow("Search engine", "Default Search (" + searchName() + ")", () -> showSearchPage());
        settingRow("Homepage", prefHomepage ? "On — TM Browser start page" : "Off — blank new tabs", () -> {
            prefHomepage = !prefHomepage;
            bp.edit().putBoolean("homepage", prefHomepage).apply();
            showSettings();
        });
        settingRow("Desktop site", desktopMode ? "On" : "Off", () -> { toggleDesktop(); showSettings(); });
        settingRow("User-agent", uaName(), () -> {
            String[] names = {"Default (Android)", "Desktop", "iPhone"};
            Runnable[] acts = new Runnable[names.length];
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                acts[i] = () -> {
                    prefUaMode = idx;
                    desktopMode = idx == 1;
                    bp.edit().putInt("ua_mode", idx).putBoolean("desktop", desktopMode).apply();
                    applySettingsAll();
                    showSettings();
                };
            }
            chooser("User-agent", null, names, acts);
        });

        section("Appearance");
        settingRow("Night mode", prefNight == 1 ? "On" : prefNight == 2 ? "Off" : "Auto (follow system)", () -> {
            String[] names = {"Auto", "On", "Off"};
            Runnable[] acts = new Runnable[names.length];
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                acts[i] = () -> {
                    prefNight = idx;
                    bp.edit().putInt("night_mode", idx).apply();
                    applySettingsAll();
                    showSettings();
                };
            }
            chooser("Night mode", null, names, acts);
        });
        settingRow("Theme", dark ? "Dark" : "Light", () -> {
            bp.edit().putInt("theme_override", dark ? 0 : 1).apply();
            recreate();
        });
        settingRow("Text size", prefTextZoom + "%", () -> {
            String[] names = {"75%", "90%", "100%", "115%", "130%", "150%"};
            int[] vals = {75, 90, 100, 115, 130, 150};
            Runnable[] acts = new Runnable[names.length];
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                acts[i] = () -> {
                    prefTextZoom = vals[idx];
                    bp.edit().putInt("text_zoom", prefTextZoom).apply();
                    applySettingsAll();
                    showSettings();
                };
            }
            chooser("Text size", null, names, acts);
        });
        settingRow("Toolbar", prefToolbarBottom ? "Bottom" : "Top", () -> {
            prefToolbarBottom = !prefToolbarBottom;
            bp.edit().putBoolean("toolbar_bottom", prefToolbarBottom).apply();
            recreate();
        });
        settingRow("Orientation", prefOrientation == 1 ? "Portrait" : prefOrientation == 2 ? "Landscape" : "Auto", () -> {
            prefOrientation = (prefOrientation + 1) % 3;
            bp.edit().putInt("orientation", prefOrientation).apply();
            applyOrientation();
            showSettings();
        });

        section("Privacy and security");
        settingRow("Ad blocking", prefAdBlock ? "On" : "Off", () -> {
            prefAdBlock = !prefAdBlock;
            bp.edit().putBoolean("adblock", prefAdBlock).apply();
            showSettings();
        });
        settingRow("Custom DNS (DoH)", dnsName(), () -> showDnsDialog());
        settingRow("System-wide ad-block DNS", "Private DNS: dns.adguard-dns.com", () -> {
            chooser("Block ads in ALL apps (system DNS)",
                    "WebView page loads use the phone's system DNS — set it under "
                            + "Android Settings → Network → Private DNS and ad domains "
                            + "die for every app, including while browsing here.",
                    new String[]{"⧉  Copy: dns.adguard-dns.com (blocks ads)",
                            "⧉  Copy: family.adguard-dns.com (ads + adult sites)",
                            "↗  Open network settings"},
                    new Runnable[]{
                            () -> copyText("dns.adguard-dns.com"),
                            () -> copyText("family.adguard-dns.com"),
                            () -> {
                                try {
                                    startActivity(new Intent(
                                            android.provider.Settings.ACTION_WIRELESS_SETTINGS));
                                } catch (Exception e) {
                                    toast("Open Settings → Network → Private DNS");
                                }
                            }});
        });
        settingRow("Clear browsing data", "History, cookies, cache", () -> showClearPage());
        settingRow("Block third-party cookies", prefTpCookies ? "Off (allowed)" : "On (blocked)", () -> {
            prefTpCookies = !prefTpCookies;
            bp.edit().putBoolean("tp_cookies", prefTpCookies).apply();
            applySettingsAll();
            showSettings();
        });
        settingRow("Safe Browsing", prefSafeBrowsing ? "On" : "Off", () -> {
            prefSafeBrowsing = !prefSafeBrowsing;
            bp.edit().putBoolean("safe_browsing", prefSafeBrowsing).apply();
            applySettingsAll();
            showSettings();
        });
        settingRow("Clear history", "Only the TM Browser history list", () -> {
            BrowserStore.clearHistory(this);
            refreshRecent();
            toast("History cleared");
        });

        section("Site settings");
        settingRow("JavaScript", prefJs ? "On" : "Off", () -> {
            prefJs = !prefJs;
            bp.edit().putBoolean("javascript", prefJs).apply();
            applySettingsAll();
            showSettings();
        });
        settingRow("Images", prefImages ? "On" : "Off", () -> {
            prefImages = !prefImages;
            bp.edit().putBoolean("images", prefImages).apply();
            applySettingsAll();
            showSettings();
        });

        section("Downloads");
        settingRow("Download location", dlLocationLabel(), () -> {
            chooser("Download location",
                    "New downloads are saved to the chosen folder. If it becomes "
                            + "unavailable, downloads fall back to internal storage.",
                    new String[]{"Internal storage (TM Player folders)",
                            "Choose folder (SD card / internal)…"},
                    new Runnable[]{
                            () -> {
                                setDlTreeUri(this, "");
                                toast("Download location: internal storage");
                                showSettings();
                            },
                            this::pickDownloadFolder});
        });

        section("Advanced");
        settingRow("Downloads", "Files downloaded with TM Browser", () -> showPanel(2));
        settingRow("Saved pages", "Pages saved for offline reading", () -> showPanel(4));
        settingRow("Page media", "Videos, images & files found on the current page", () -> showPanel(3));
        settingRow("About TM Browser", engineLine(), null);
        settingsView.setVisibility(View.VISIBLE);
        panelView.setVisibility(View.GONE);
        tabsView.setVisibility(View.GONE);
        pageView.setVisibility(View.GONE);
    }

    // ============================================================ WebView engine
    void applySettings(WebView wv, boolean priv) {
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(prefJs);
        st.setLoadsImagesAutomatically(prefImages);
        st.setTextZoom(prefTextZoom);
        st.setUserAgentString(currentUa());
        if (Build.VERSION.SDK_INT >= 26) st.setSafeBrowsingEnabled(prefSafeBrowsing);
        if (Build.VERSION.SDK_INT >= 33) {
            st.setAlgorithmicDarkeningAllowed(prefNight == 1 || (prefNight == 0 && dark));
        } else if (Build.VERSION.SDK_INT >= 29) {
            st.setForceDark(prefNight == 1 || (prefNight == 0 && dark)
                    ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, prefTpCookies && !priv);
    }

    void applySettingsAll() {
        for (BTab t : TABS) if (t.wv != null) applySettings(t.wv, t.priv);
    }

    WebView createWebView(final BTab tab) {
        final WebView wv = new WebView(this);
        wv.setBackgroundColor(cBg);
        WebSettings st = wv.getSettings();
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        st.setAllowFileAccess(true);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setSupportMultipleWindows(false);
        if (tab.priv) st.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        applySettings(wv, tab.priv);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String u = request.getUrl().toString();
                    if (prefAdBlock && !request.isForMainFrame()) {
                        String host = hostOf(u);
                        if (isAdHost(host)) {
                            return new WebResourceResponse("text/plain", "utf-8",
                                    new ByteArrayInputStream(new byte[0]));
                        }
                    }
                    if (isGrabbableUrl(u) && !u.equals(tab.url)) {
                        synchronized (tab.sniffed) {
                            if (!tab.sniffed.contains(u) && tab.sniffed.size() < 200)
                                tab.sniffed.add(u);
                        }
                    }
                } catch (Exception ignored) {}
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                // Popup / pop-under blocking: a main-frame navigation to an ad
                // host WITHOUT a user tap is a popup — kill it.
                if (prefAdBlock && request.isForMainFrame() && !request.hasGesture()
                        && isAdHost(hostOf(u))) {
                    if (tab == activeTab()) toast("Blocked ad / popup: " + hostOf(u));
                    return true;
                }
                if (isMediaUrl(u)) {
                    mediaChooser(u);
                    return true;
                }
                String sch = schemeOf(u);
                if (!(sch.equals("http") || sch.equals("https") || sch.equals("about")
                        || sch.equals("data") || sch.equals("blob") || sch.equals("file")
                        || sch.equals("view-source"))) {
                    openExternal(u);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                tab.home = false;
                if (tab == activeTab()) {
                    progress.setVisibility(View.VISIBLE);
                    if (!urlEditing) syncUrlBar();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.url = url;
                if (tab == activeTab()) {
                    progress.setVisibility(View.INVISIBLE);
                    syncUrlBar();
                    refreshStar();
                    syncBackBtn();
                    ui.postDelayed(() -> captureThumb(tab), 350);
                }
                if (!tab.priv && url.startsWith("http") && !url.equals(tab.lastRecorded)) {
                    tab.lastRecorded = url;
                    String t = tab.title.isEmpty() ? hostOf(url) : tab.title;
                    BrowserStore.addHistory(BrowserActivity.this, t, url);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Page failed to load: " + error.getDescription()
                            + " (code " + error.getErrorCode() + ") — " + request.getUrl());
                    if (tab == activeTab()) progress.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    showError("Server error: HTTP " + response.getStatusCode()
                            + " " + response.getReasonPhrase() + " — " + request.getUrl());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showError("Secure connection failed (SSL): " + error.getPrimaryError()
                        + " — " + error.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                toast("Page renderer restarted");
                if (tab.wv == view) {
                    destroyWebView(tab);
                    ui.post(() -> { if (tab == activeTab()) bindActive(); });
                }
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (tab == activeTab()) {
                    progress.setProgress(p);
                    progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                tab.title = title == null ? "" : title;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        wv.addJavascriptInterface(new BlobBridge(), "TMBlob");

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                downloadBlob(wv, url, mimeType);
                return;
            }
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                toast("This download type is not supported");
                return;
            }
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (name == null || name.isEmpty() || name.equals("download"))
                name = filenameFromUrl(url);
            final String fname = name;
            chooser("Download file", fname,
                    new String[]{"⬇  Download", "▶  Play in TM Player"},
                    new Runnable[]{
                            () -> DohFetch.download(this, url, fname, userAgent, ui, this::toastBg),
                            () -> playInTmPlayer(url)});
        });

        wv.setOnLongClickListener(v -> {
            WebView.HitTestResult hr = wv.getHitTestResult();
            if (hr == null) return false;
            String extra = hr.getExtra();
            if (extra == null || extra.isEmpty()) return false;
            int type = hr.getType();
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    || type == WebView.HitTestResult.IMAGE_TYPE) {
                linkMenu(extra);
                return true;
            }
            return false;
        });

        tab.wv = wv;
        return wv;
    }

    void destroyWebView(BTab tab) {
        if (tab.wv != null) {
            try {
                tab.wv.stopLoading();
                tab.wv.setWebChromeClient(null);
                tab.wv.setWebViewClient(new WebViewClient());
                tab.wv.destroy();
            } catch (Exception ignored) {}
            tab.wv = null;
            tab.started = false;
        }
    }

    // ============================================================ navigation / tabs
    void bindActive() {
        BTab t = activeTab();
        if (t != lastBoundTab) {
            // tab switched: the address bar must show THIS tab's address,
            // never the previous tab's — drop any stale editing state.
            lastBoundTab = t;
            urlEditing = false;
            if (urlText != null) urlText.clearFocus();
            hideKeyboard();
        }
        showingHome = t.home;
        for (int i = stage.getChildCount() - 1; i >= 0; i--) {
            View ch = stage.getChildAt(i);
            if (ch instanceof WebView) stage.removeViewAt(i);
        }
        if (t.wv == null) createWebView(t);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        stage.addView(t.wv, 0, lp);
        if (!t.started && !t.url.isEmpty()) {
            t.started = true;
            t.wv.loadUrl(t.url);
        }
        homeView.setVisibility(showingHome ? View.VISIBLE : View.GONE);
        if (showingHome) refreshRecent();
        syncUrlBar();
        refreshStar();
        syncBackBtn();
        tabCountBtn.setText(String.valueOf(TABS.size()));
    }

    void showHome() {
        captureThumb(activeTab());
        activeTab().home = true;
        showingHome = true;
        homeView.setVisibility(View.VISIBLE);
        panelView.setVisibility(View.GONE);
        tabsView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        menuView.setVisibility(View.GONE);
        refreshRecent();
        runNetTest();
    }

    void navigate(String raw) {
        if (raw == null) return;
        String u = raw.trim();
        if (u.isEmpty()) return;
        if (!u.contains("://")) {
            if (u.contains(".") && !u.contains(" ")) u = "https://" + u;
            else u = searchUrl(u);
        }
        if (isMediaUrl(u)) { mediaChooser(u); return; }
        BTab t = activeTab();
        if (t.wv == null) createWebView(t);
        t.home = false;
        showingHome = false;
        homeView.setVisibility(View.GONE);
        hideOverlays();
        t.started = true;
        t.wv.loadUrl(u);
    }

    void hideOverlays() {
        panelView.setVisibility(View.GONE);
        tabsView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);
        menuView.setVisibility(View.GONE);
        pageView.setVisibility(View.GONE);
    }

    void newTab(boolean priv, String url) {
        if (TABS.size() >= 12) { toast("Max 12 tabs"); return; }
        captureThumb(activeTab());
        BTab t = new BTab(priv);
        TABS.add(t);
        sActive = TABS.size() - 1;
        t.home = prefHomepage && (url == null || url.isEmpty());
        showingHome = t.home;
        bindActive();
        if (url != null && !url.isEmpty()) navigate(url);
    }

    void closeTab(int idx) {
        if (idx < 0 || idx >= TABS.size()) return;
        BTab t = TABS.remove(idx);
        destroyWebView(t);
        if (TABS.isEmpty()) {
            BTab fresh = new BTab(false);
            fresh.home = prefHomepage;
            TABS.add(fresh);
            sActive = 0;
        }
        else if (sActive >= TABS.size()) sActive = TABS.size() - 1;
        else if (idx < sActive) sActive--;
        bindActive();
    }

    void syncUrlBar() {
        if (urlEditing) return;
        BTab t = activeTab();
        String u = t.url;
        if (u.startsWith("https://")) u = u.substring(8);
        else if (u.startsWith("http://")) u = u.substring(7);
        urlText.setText(t.home ? "" : u);
    }

    void syncBackBtn() { /* top-bar layout has no back button; back is system/gesture */ }

    void refreshStar() {
        if (starBtn == null) return;
        BTab t = activeTab();
        boolean bm = !t.url.isEmpty() && BrowserStore.isBookmarked(this, t.url);
        starBtn.setText(bm ? "★" : "☆");
    }

    void toggleStar() {
        BTab t = activeTab();
        if (t.url.isEmpty() || !t.url.startsWith("http")) { toast("Open a page first"); return; }
        boolean now = BrowserStore.toggleBookmark(this,
                t.title.isEmpty() ? hostOf(t.url) : t.title, t.url);
        toast(now ? "Bookmarked" : "Bookmark removed");
        refreshStar();
    }

    void showError(String msg) {
        errorText.setText("⚠ " + msg);
        errorBar.setVisibility(View.VISIBLE);
    }

    void handleBack() {
        if (menuView.getVisibility() == View.VISIBLE) { menuView.setVisibility(View.GONE); return; }
        if (findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            BTab t = activeTab();
            if (t.wv != null) t.wv.clearMatches();
            return;
        }
        if (settingsView.getVisibility() == View.VISIBLE) { settingsView.setVisibility(View.GONE); return; }
        if (pageView.getVisibility() == View.VISIBLE) { pageView.setVisibility(View.GONE); return; }
        if (panelView.getVisibility() == View.VISIBLE) { panelView.setVisibility(View.GONE); return; }
        if (tabsView.getVisibility() == View.VISIBLE) { tabsView.setVisibility(View.GONE); return; }
        if (!showingHome) {
            BTab t = activeTab();
            if (t.wv != null && t.wv.canGoBack()) { t.wv.goBack(); return; }
        }
        finish();
    }

    @Override
    public void onBackPressed() { handleBack(); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                setDlTreeUri(this, uri.toString());
                toast("Download location set: " + dlLocationLabel());
                if (settingsView.getVisibility() == View.VISIBLE) showSettings();
            }
            return;
        }
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            for (BTab t : TABS) destroyWebView(t);
            TABS.clear();
        }
        super.onDestroy();
    }

    // ============================================================ find bar
    View buildFindBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cCard);
        bar.setPadding(dp(10), dp(6), dp(6), dp(6));
        findText = new EditText(this);
        findText.setSingleLine(true);
        findText.setTextSize(14);
        findText.setTextColor(cText);
        findText.setHintTextColor(cDim);
        findText.setHint("Find in page");
        findText.setBackground(null);
        findText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        findText.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                BTab t = activeTab();
                if (t.wv != null) t.wv.findAllAsync(s.toString());
            }
            public void afterTextChanged(android.text.Editable e) {}
        });
        bar.addView(findText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        findCount = label("", 12, cDim, false);
        bar.addView(findCount);
        TextView prev = btn("‹", 20);
        prev.setOnClickListener(v -> { BTab t = activeTab(); if (t.wv != null) t.wv.findNext(false); });
        bar.addView(prev);
        TextView next = btn("›", 20);
        next.setOnClickListener(v -> { BTab t = activeTab(); if (t.wv != null) t.wv.findNext(true); });
        bar.addView(next);
        TextView close = btn("✕", 15);
        close.setOnClickListener(v -> {
            findBar.setVisibility(View.GONE);
            BTab t = activeTab();
            if (t.wv != null) t.wv.clearMatches();
            hideKeyboard();
        });
        bar.addView(close);
        return bar;
    }

    // Custom-view chooser: big tappable rows of our own. The platform list
    // dialog (AlertDialog.setItems) rendered WITHOUT its items on this
    // device/ROM — the download dialog showed only a Cancel button.
    void chooser(String title, String message, String[] labels, Runnable[] actions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(pill(cCard, 14));
        card.setPadding(dp(6), dp(10), dp(6), dp(10));
        if (title != null && !title.isEmpty()) {
            TextView h = label(title, 16, cText, true);
            h.setPadding(dp(12), dp(2), dp(12), dp(2));
            card.addView(h);
        }
        if (message != null && !message.isEmpty()) {
            TextView m = label(message, 13.5f, cDim, false);
            m.setPadding(dp(12), 0, dp(12), dp(6));
            card.addView(m);
        }
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < labels.length; i++) {
            final Runnable act = i < actions.length ? actions[i] : null;
            TextView row = label(labels[i], 15.5f, cText, false);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            row.setOnClickListener(v -> { dlg[0].dismiss(); if (act != null) act.run(); });
            card.addView(row);
        }
        TextView cancel = label("Cancel", 14, cDim, false);
        cancel.setPadding(dp(12), dp(10), dp(12), dp(4));
        card.addView(cancel);
        dlg[0] = new AlertDialog.Builder(this).setView(card).create();
        cancel.setOnClickListener(v -> dlg[0].dismiss());
        if (dlg[0].getWindow() != null)
            dlg[0].getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        dlg[0].show();
    }

    // ============================================================ actions
    void showDnsDialog() {
        String[] names = {"Google (8.8.8.8)", "Cloudflare (1.1.1.1)",
                "AdGuard (blocks ads at DNS level)", "Custom (enter DoH URL)…"};
        Runnable[] acts = new Runnable[names.length];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            acts[i] = () -> {
                prefDnsPreset = idx;
                bp.edit().putInt("dns_preset", idx).apply();
                DohFetch.customDoh = effectiveDoh();
                toast("DNS: " + dnsName());
                if (settingsView.getVisibility() == View.VISIBLE) showSettings();
                runNetTest();
            };
        }
        acts[3] = () -> {
            final EditText in = new EditText(this);
            in.setHint("https://dns.example.com/dns-query");
            in.setSingleLine(true);
            in.setText(prefDohCustom);
            in.setTextColor(cText);
            in.setHintTextColor(cDim);
            new AlertDialog.Builder(this)
                    .setTitle("Custom DoH endpoint")
                    .setMessage("A DNS-over-HTTPS JSON endpoint. TM Browser uses it "
                            + "for its own downloads and the connection test. Tip: for "
                            + "system-wide DNS, set the same provider under Android "
                            + "Settings → Network → Private DNS.")
                    .setView(in)
                    .setPositiveButton("Save", (d2, w2) -> {
                        String u = in.getText().toString().trim();
                        if (!u.isEmpty() && !u.contains("://")) u = "https://" + u;
                        prefDohCustom = u;
                        prefDnsPreset = 3;
                        bp.edit().putString("doh_custom", u)
                                .putInt("dns_preset", 3).apply();
                        DohFetch.customDoh = effectiveDoh();
                        toast("DNS: " + dnsName());
                        if (settingsView.getVisibility() == View.VISIBLE) showSettings();
                        runNetTest();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        };
        chooser("Custom DNS (DNS-over-HTTPS)", null, names, acts);
    }

    // ============================================================ Via-style tools
    void savePage() {
        BTab t = activeTab();
        if (t.wv == null || t.url.isEmpty() || !t.url.startsWith("http")) {
            toast("Open a web page first");
            return;
        }
        String base = t.title.isEmpty() ? hostOf(t.url) : t.title;
        base = base.replaceAll("[^a-zA-Z0-9 _.-]+", "").trim();
        if (base.length() > 40) base = base.substring(0, 40);
        if (base.isEmpty()) base = "page";
        File out = new File(savedPagesDir(), base.replace(' ', '_') + ".mht");
        if (out.exists()) out = new File(savedPagesDir(),
                base.replace(' ', '_') + "_" + System.currentTimeMillis() % 100000 + ".mht");
        final File outFile = out;
        toast("Saving page…");
        t.wv.saveWebArchive(outFile.getAbsolutePath(), false, value -> {
            if (value != null) toast("Page saved — Menu → Saved pages");
            else { toast("Could not save this page"); outFile.delete(); }
        });
    }

    void addToHomeScreen() {
        BTab t = activeTab();
        if (t.url.isEmpty() || !t.url.startsWith("http")) { toast("Open a page first"); return; }
        if (Build.VERSION.SDK_INT < 26) { toast("Needs Android 8+"); return; }
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                toast("Your launcher does not support pinned shortcuts");
                return;
            }
            Intent open = new Intent(this, BrowserActivity.class);
            open.setAction(Intent.ACTION_VIEW);
            open.putExtra("open_url", t.url);
            String title = t.title.isEmpty() ? hostOf(t.url) : t.title;
            Bitmap iconBmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(iconBmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(cAccent);
            cv.drawCircle(96, 96, 96, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(96);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            String initial = title.isEmpty() ? "T" : title.substring(0, 1).toUpperCase(Locale.ROOT);
            cv.drawText(initial, 96, 96 - (p.ascent() + p.descent()) / 2, p);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "web-" + System.currentTimeMillis())
                    .setShortLabel(title.length() > 24 ? title.substring(0, 24) : title)
                    .setLongLabel(title)
                    .setIcon(Icon.createWithBitmap(iconBmp))
                    .setIntent(open)
                    .build();
            sm.requestPinShortcut(info, null);
        } catch (Exception e) {
            toast("Could not add shortcut");
        }
    }

    void toggleFullscreen() {
        fsOn = !fsOn;
        applyFullscreen();
        toast(fsOn ? "Full-screen on (menu → Exit full-screen to leave)" : "Full-screen off");
    }

    void applyFullscreen() {
        int flags;
        if (fsOn) {
            flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        } else {
            flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagservices.com", "googletagmanager.com",
            "ads.yahoo.com", "analytics.yahoo.com", "amazon-adsystem.com",
            "adnxs.com", "advertising.com", "rubiconproject.com", "pubmatic.com",
            "openx.net", "casalemedia.com", "scorecardresearch.com", "moatads.com",
            "zedo.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "mgid.com", "popads.net", "popcash.net", "propellerads.com",
            "adsterra.com", "hilltopads.com", "exoclick.com", "trafficjunky.com",
            "smartadserver.com", "adsafeprotected.com", "doubleverify.com",
            "spotx.tv", "innovid.com", "flashtalking.com", "sizmek.com",
            "undertone.com", "sharethrough.com", "teads.tv", "triplelift.com",
            "indexww.com", "bidswitch.net", "adsrvr.org", "rlcdn.com",
            "bluekai.com", "demdex.net", "everesttech.net", "mathtag.com",
            "quantserve.com", "quantcount.com", "hotjar.com", "mouseflow.com",
            "crazyegg.com", "clicky.com", "statcounter.com", "histats.com",
            "adform.net", "fiftyac.com", "yieldmo.com", "nativo.com",
            "revcontent.com", "zergnet.com", "contentad.net", "newsmaxfeednetwork.com"
    };

    static volatile java.util.HashSet<String> AD_SET;

    static boolean isAdHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : AD_HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        java.util.HashSet<String> s = AD_SET;
        if (s != null) {
            String cur = h;
            while (true) {
                if (s.contains(cur)) return true;
                int dot = cur.indexOf('.');
                if (dot < 0) break;
                cur = cur.substring(dot + 1);
            }
        }
        return false;
    }

    // Loads the bundled ad/tracker domain list (assets/adhosts.txt, ~30k
    // domains from the Steven Black hosts project, MIT) once, off-thread.
    static void loadAdList(Context ctx) {
        if (AD_SET != null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(app.getAssets().open("adhosts.txt"), "UTF-8"));
                java.util.HashSet<String> set = new java.util.HashSet<>();
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) set.add(line);
                }
                r.close();
                AD_SET = set;
            } catch (Exception ignored) {}
        }).start();
    }

    void toggleDesktop() {
        prefUaMode = prefUaMode == 1 ? 0 : 1;
        desktopMode = prefUaMode == 1;
        bp.edit().putInt("ua_mode", prefUaMode).putBoolean("desktop", desktopMode).apply();
        applySettingsAll();
        BTab t = activeTab();
        if (t.wv != null && !t.url.isEmpty()) t.wv.reload();
        toast(desktopMode ? "Desktop site: ON" : "Desktop site: OFF");
    }

    void sharePage() {
        BTab t = activeTab();
        if (t.url.isEmpty()) { toast("Open a page first"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, t.title.isEmpty() ? t.url : t.title);
        i.putExtra(Intent.EXTRA_TEXT, t.url);
        startActivity(Intent.createChooser(i, "Share page"));
    }

    void clearData() {
        for (BTab tb : TABS) {
            if (tb.wv != null) {
                tb.wv.clearCache(true);
                tb.wv.clearHistory();
                tb.wv.clearFormData();
            }
        }
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {}
        BrowserStore.clearHistory(this);
        refreshRecent();
        toast("Browsing data cleared");
    }

    // ============================================================ blob: downloads
    volatile boolean blobArmed;
    String blobName = "download";

    void downloadBlob(WebView wv, String blobUrl, String mimeHint) {
        BTab t = activeTab();
        String base = t.title == null ? "" : t.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (base.length() > 40) base = base.substring(0, 40).trim();
        if (base.isEmpty()) base = "download";
        String ext = mimeHint == null ? null
                : MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeHint.split(";")[0].trim());
        blobName = ext == null ? base : base + "." + ext;
        blobArmed = true;
        toast("Downloading…");
        String js = "(function(){fetch('" + blobUrl + "').then(function(r){return r.blob();})"
                + ".then(function(b){var fr=new FileReader();"
                + "fr.onload=function(){TMBlob.save(fr.result,b.type);};"
                + "fr.onerror=function(){TMBlob.fail('read error');};"
                + "fr.readAsDataURL(b);})"
                + ".catch(function(e){TMBlob.fail(''+e);});})();";
        wv.evaluateJavascript(js, null);
        ui.postDelayed(() -> {
            if (blobArmed) { blobArmed = false; toast("Download failed: no response from page"); }
        }, 30000);
    }

    class BlobBridge {
        @JavascriptInterface
        public void save(String dataUrl, String mime) {
            if (!blobArmed) return;
            blobArmed = false;
            final String name = blobName;
            new Thread(() -> {
                try {
                    int comma = dataUrl.indexOf(',');
                    if (comma < 0) throw new IOException("Bad data");
                    byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                    String m = mime == null || mime.isEmpty() ? null : mime.split(";")[0].trim();
                    String fn = name;
                    if (!DohFetch.hasExtension(fn) && m != null) {
                        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
                        if (ext != null) fn = fn + "." + ext;
                    }
                    saveStream(BrowserActivity.this, new ByteArrayInputStream(bytes), fn, m);
                    toastBg("⬇ Saved: " + fn);
                } catch (Exception e) {
                    toastBg("Download failed: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void fail(String msg) {
            if (!blobArmed) return;
            blobArmed = false;
            toastBg("Download failed: " + msg);
        }
    }

    // ============================================================ media / links
    void mediaChooser(final String url) {
        chooser("Media link", filenameFromUrl(url),
                new String[]{"▶  Play in TM Player", "⬇  Download"},
                new Runnable[]{() -> playInTmPlayer(url),
                        () -> DohFetch.download(this, url, null, ui, this::toastBg)});
    }

    void playInTmPlayer(String url) {
        VideoItem v = new VideoItem();
        v.name = filenameFromUrl(url);
        v.uriStr = url;
        ArrayList<VideoItem> one = new ArrayList<>();
        one.add(v);
        PlayQueue.set(one, 0);
        Intent i = new Intent(this, PlayerActivity.class);
        if (isAudioUrl(url)) i.putExtra("audio_only", true);
        startActivity(i);
    }

    void linkMenu(final String link) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (isMediaUrl(link)) {
            labels.add("▶  Play in TM Player");
            actions.add(() -> playInTmPlayer(link));
            labels.add("⬇  Download");
            actions.add(() -> DohFetch.download(this, link, null, ui, this::toastBg));
        } else {
            labels.add("↗  Open in new tab");
            actions.add(() -> newTab(false, link));
            labels.add("⬇  Download file");
            actions.add(() -> DohFetch.download(this, link, null, ui, this::toastBg));
        }
        labels.add("⧉  Copy link");
        actions.add(() -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("link", link));
            toast("Link copied");
        });
        chooser(link.length() > 60 ? link.substring(0, 60) + "…" : link, null,
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    void openExternal(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            toast("No app can open this link");
        }
    }

    // ============================================================ helpers
    static final String[] MEDIA_EXT = {".mp4", ".mkv", ".webm", ".mov", ".m4v", ".3gp",
            ".avi", ".m3u8", ".ts", ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"};
    static final String[] AUDIO_EXT = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"};

    static boolean endsAny(String u, String[] exts) {
        String p = u.toLowerCase(Locale.ROOT);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        for (String e : exts) if (p.endsWith(e)) return true;
        return false;
    }

    static boolean isMediaUrl(String u) { return u != null && endsAny(u, MEDIA_EXT); }
    static boolean isAudioUrl(String u) { return u != null && endsAny(u, AUDIO_EXT); }

    static final String[] IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif",
            ".bmp", ".svg", ".avif"};
    static final String[] FILE_EXT = {".zip", ".rar", ".7z", ".tar", ".gz", ".pdf",
            ".apk", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv"};

    static boolean isGrabbableUrl(String u) {
        return u != null && (isMediaUrl(u) || endsAny(u, IMAGE_EXT) || endsAny(u, FILE_EXT));
    }

    static String schemeOf(String u) {
        int i = u.indexOf(':');
        return i > 0 ? u.substring(0, i).toLowerCase(Locale.ROOT) : "";
    }

    static String hostOf(String u) {
        try {
            String h = Uri.parse(u).getHost();
            return h == null ? u : h;
        } catch (Exception e) { return u; }
    }

    static String filenameFromUrl(String u) {
        try {
            String p = Uri.parse(u).getLastPathSegment();
            if (p != null && !p.isEmpty()) return Uri.decode(p);
        } catch (Exception ignored) {}
        return "download";
    }

    void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null)
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    void showKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {}
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void toastBg(String s) { ui.post(() -> toast(s)); }

    // ============================================================ net test
    String engineLine() {
        try {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) return "Android WebView (" + pi.packageName + " " + pi.versionName + ")";
            return "⚠ NO WebView provider found on this phone";
        } catch (Exception e) {
            return "Android WebView";
        }
    }

    void runNetTest() {
        if (netInfo == null) return;
        final String engine = "Engine: " + engineLine();
        netInfo.setText(engine + "\nConnection test — running…");
        new Thread(() -> {
            final String dns = testDns();
            final String ip = testDirectIp();
            final String doh = testDoh();
            ui.post(() -> {
                if (netInfo != null) netInfo.setText(engine
                        + "\nConnection test (tap to re-run):"
                        + "\n• System DNS: " + dns
                        + "\n• Direct IP (1.1.1.1): " + ip
                        + "\n• Secure DNS (DoH): " + doh);
            });
        }).start();
    }

    String testDns() {
        try {
            InetAddress[] a = InetAddress.getAllByName("m.facebook.com");
            return a.length > 0 ? "OK (" + a[0].getHostAddress() + ")" : "FAIL";
        } catch (Exception e) { return "FAIL (" + e.getMessage() + ")"; }
    }

    String testDirectIp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://1.1.1.1/").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            int code = c.getResponseCode();
            c.disconnect();
            return "OK (HTTP " + code + ")";
        } catch (Exception e) { return "FAIL (" + e.getMessage() + ")"; }
    }

    String testDoh() {
        try {
            String ip = DohFetch.resolve("facebook.com");
            String host = "8.8.8.8";
            try { host = new URL(DohFetch.customDoh).getHost(); } catch (Exception ignored) {}
            return "OK (" + ip + " via " + host + ")";
        } catch (Exception e) { return "FAIL (" + e.getMessage() + ")"; }
    }

    interface ProgressListener { void onProgress(long done); }

    static void saveStream(Context ctx, InputStream in, String name, String mime) throws IOException {
        saveStream(ctx, in, name, mime, null);
    }

    // Custom download folder (SAF tree URI), shared with the download service.
    static String dlTreeUri(Context ctx) {
        return ctx.getSharedPreferences("tmp_browser", Context.MODE_PRIVATE)
                .getString("dl_tree_uri", "");
    }

    static void setDlTreeUri(Context ctx, String uri) {
        ctx.getSharedPreferences("tmp_browser", Context.MODE_PRIVATE)
                .edit().putString("dl_tree_uri", uri).apply();
    }

    static void copyTo(InputStream in, OutputStream out, ProgressListener pl) throws IOException {
        try {
            byte[] buf = new byte[65536];
            int n;
            long doneBytes = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                doneBytes += n;
                if (pl != null) pl.onProgress(doneBytes);
            }
            out.flush();
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    static void saveStream(Context ctx, InputStream in, String name, String mime,
            ProgressListener pl) throws IOException {
        int kind = kindFor(name, mime);
        String treeUri = dlTreeUri(ctx);
        if (!treeUri.isEmpty()) {
            OutputStream safOut = null;
            try {
                Uri tree = Uri.parse(treeUri);
                Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
                Uri created = android.provider.DocumentsContract.createDocument(
                        ctx.getContentResolver(), parent,
                        mime != null ? mime : "application/octet-stream", name);
                if (created != null) safOut = ctx.getContentResolver().openOutputStream(created);
            } catch (Exception ignored) {}
            if (safOut != null) {
                copyTo(in, safOut, pl);
                return;
            }
            // custom folder unavailable → fall through to internal storage
        }
        OutputStream out;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime != null) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            Uri coll;
            String rel;
            if (kind == 0) { coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MOVIES + "/TM Player"; }
            else if (kind == 1) { coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MUSIC + "/TM Player"; }
            else { coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_DOWNLOADS + "/TM Player"; }
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            Uri uri = ctx.getContentResolver().insert(coll, v);
            if (uri == null) throw new IOException("Could not create file");
            out = ctx.getContentResolver().openOutputStream(uri);
        } else {
            String base = kind == 0 ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getPath()
                    : kind == 1 ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getPath()
                    : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath();
            File dir = new File(base, "TM Player");
            if (!dir.exists()) dir.mkdirs();
            out = new FileOutputStream(new File(dir, name));
        }
        if (out == null) throw new IOException("Could not open output");
        copyTo(in, out, pl);
    }
    static int kindFor(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("video")) return 0;
        if (m.startsWith("audio")) return 1;
        if (isAudioUrl(name)) return 1;
        if (isMediaUrl(name)) return 0;
        return 2;
    }
    static class DohFetch {
        interface Callback { void onMsg(String m); }
        static boolean notifAsked;
        static final String UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

        static void download(final Context ctx, final String urlStr, final String nameHint,
                final Handler ui, final Callback cb) {
            download(ctx, urlStr, nameHint, null, ui, cb);
        }

        // Downloads run in TmDownloadService (foreground service) so they
        // survive leaving the app, and support pause / resume / cancel.
        static void download(final Context ctx, final String urlStr, final String nameHint,
                final String ua, final Handler ui, final Callback cb) {
            String referer = "";
            try {
                if (ctx instanceof BrowserActivity)
                    referer = ((BrowserActivity) ctx).activeTab().url;
            } catch (Exception ignored) {}
            if (ctx instanceof Activity && Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                notifAsked = true;
                try {
                    if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        ((Activity) ctx).requestPermissions(
                                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 991);
                } catch (Exception ignored) {}
            }
            TmDownloadService.enqueue(ctx, urlStr, nameHint, ua, referer, cb);
        }

        static void attachHeaders(HttpURLConnection c, String forUrl, String ua, String referer) {
            c.setRequestProperty("User-Agent", ua == null || ua.isEmpty() ? UA : ua);
            try {
                String cookie = CookieManager.getInstance().getCookie(forUrl);
                if (cookie != null && !cookie.isEmpty())
                    c.setRequestProperty("Cookie", cookie);
            } catch (Exception ignored) {}
            if (referer != null && !referer.isEmpty())
                c.setRequestProperty("Referer", referer);
        }

        static HttpURLConnection openDirect(String urlStr, String ua, String referer,
                long rangeFrom) throws Exception {
            String cur = urlStr;
            for (int hop = 0; hop < 6; hop++) {
                URL u = new URL(cur);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                attachHeaders(c, cur, ua, referer);
                if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new IOException("Redirect without Location");
                    cur = new URL(u, loc).toString();
                    continue;
                }
                return c;
            }
            throw new IOException("Too many redirects");
        }

        static boolean hasExtension(String n) {
            int d = n.lastIndexOf('.');
            return d > 0 && n.length() - d <= 6;
        }

        static String customDoh = "";

        static String resolve(String host) throws Exception {
            ArrayList<String> endpoints = new ArrayList<>();
            if (customDoh != null && !customDoh.trim().isEmpty()) {
                String base = customDoh.trim();
                endpoints.add(base + (base.contains("?") ? "&" : "?") + "name=%s&type=A");
            }
            endpoints.add("https://8.8.8.8/resolve?name=%s&type=A");
            endpoints.add("https://1.1.1.1/dns-query?name=%s&type=A");
            for (String e : endpoints) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            String.format(e, URLEncoder.encode(host, "UTF-8"))).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Accept", "application/dns-json");
                    String body = readAll(c.getInputStream());
                    JSONArray ans = new JSONObject(body).optJSONArray("Answer");
                    if (ans != null) for (int i = 0; i < ans.length(); i++) {
                        JSONObject a = ans.getJSONObject(i);
                        if (a.optInt("type") == 1) return a.getString("data");
                    }
                } catch (Exception ignored) {}
            }
            return InetAddress.getByName(host).getHostAddress();
        }

        static HttpURLConnection open(String urlStr) throws Exception {
            return open(urlStr, "", 0);
        }

        static HttpURLConnection open(String urlStr, String referer, long rangeFrom) throws Exception {
            String cur = urlStr;
            for (int hop = 0; hop < 6; hop++) {
                URL u = new URL(cur);
                String host = u.getHost();
                int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
                boolean ipLiteral = host.matches("[0-9.]+") || host.contains(":");
                String ip = ipLiteral ? host : resolve(host);
                String file = u.getFile() == null || u.getFile().isEmpty() ? "/" : u.getFile();
                URL iu = new URL(u.getProtocol(), ip, port, file);
                HttpURLConnection c = (HttpURLConnection) iu.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Host", port == u.getDefaultPort() ? host : host + ":" + port);
                attachHeaders(c, cur, null, referer);
                if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
                if (c instanceof HttpsURLConnection) {
                    HttpsURLConnection h = (HttpsURLConnection) c;
                    h.setSSLSocketFactory(new SniFactory(host));
                    final String fh = host;
                    h.setHostnameVerifier((hn, sess) -> verifyHost(fh, sess));
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new IOException("Redirect without Location");
                    cur = new URL(u, loc).toString();
                    continue;
                }
                return c;
            }
            throw new IOException("Too many redirects");
        }

        static boolean verifyHost(String host, SSLSession sess) {
            try {
                Certificate[] certs = sess.getPeerCertificates();
                X509Certificate cert = (X509Certificate) certs[0];
                ArrayList<String> names = new ArrayList<>();
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if (sans != null) for (List<?> san : sans)
                    if (san.get(0) instanceof Integer
                            && ((Integer) san.get(0) == 2 || (Integer) san.get(0) == 7))
                        names.add(String.valueOf(san.get(1)));
                if (names.isEmpty()) {
                    String dn = cert.getSubjectX500Principal().getName();
                    for (String part : dn.split(","))
                        if (part.trim().startsWith("CN=")) names.add(part.trim().substring(3));
                }
                String hl = host.toLowerCase(Locale.ROOT);
                for (String n : names) {
                    String nl = n.toLowerCase(Locale.ROOT);
                    if (nl.equals(hl)) return true;
                    if (nl.startsWith("*.") && hl.endsWith(nl.substring(1))
                            && hl.split("\\.").length == nl.split("\\.").length) return true;
                }
            } catch (Exception ignored) {}
            return false;
        }

        static String readAll(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        }

        static class SniFactory extends SSLSocketFactory {
            final SSLSocketFactory base = HttpsURLConnection.getDefaultSSLSocketFactory();
            final String sni;
            SniFactory(String sni) { this.sni = sni; }
            Socket wrap(Socket s) {
                if (s instanceof SSLSocket) {
                    try {
                        SSLParameters p = ((SSLSocket) s).getSSLParameters();
                        p.setServerNames(Collections.singletonList(new SNIHostName(sni)));
                        ((SSLSocket) s).setSSLParameters(p);
                    } catch (Exception ignored) {}
                }
                return s;
            }
            @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
            @Override public Socket createSocket() throws IOException { return wrap(base.createSocket()); }
            @Override public Socket createSocket(String h, int p) throws IOException { return wrap(base.createSocket(h, p)); }
            @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return wrap(base.createSocket(h, p, l, lp)); }
            @Override public Socket createSocket(InetAddress h, int p) throws IOException { return wrap(base.createSocket(h, p)); }
            @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return wrap(base.createSocket(h, p, l, lp)); }
            @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return wrap(base.createSocket(s, sni, p, a)); }
        }
    }}
