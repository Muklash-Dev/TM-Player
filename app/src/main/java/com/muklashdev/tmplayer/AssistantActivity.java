package com.muklashdev.tmplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Private AI Assistant — connect ANY custom AI. The user supplies a base URL,
 * an API key (optional for local servers) and a model name for any service
 * that speaks the OpenAI chat format (OpenAI, OpenRouter, Groq, DeepSeek,
 * Mistral, xAI, Together, Gemini's OpenAI mode, Ollama / LM Studio locally).
 * Voice chat uses the phone's own speech services: SpeechRecognizer for
 * input and TextToSpeech for spoken replies. No TM Player server exists;
 * keys and chats never leave the phone except to the endpoint the user set.
 */
public class AssistantActivity extends Activity {

    private static final String SYSTEM_PROMPT =
            "You are the AI assistant inside TM Player, a media player app. "
            + "Be helpful, friendly and concise. Answer in the same language the user writes in.";

    /** name, base URL, suggested model */
    private static final String[][] PRESETS = {
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
            {"OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"},
            {"Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"},
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"},
            {"Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"},
            {"xAI Grok", "https://api.x.ai/v1", "grok-3-mini"},
            {"Together", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"},
            {"Gemini (OpenAI mode)", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash"},
    };

    private Prefs prefs;
    private int cBg, cCard, cCard2, cText, cDim, cAccent;
    private LinearLayout chatCol, setupCol, modelsBox;
    private ArrayList<TextView> presetChips;
    private ArrayList<String> presetBases;

    private static String normBase(String b) {
        b = b.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private void markPresetActive(String base) {
        if (presetChips == null) return;
        for (int i = 0; i < presetChips.size(); i++) {
            boolean active = normBase(presetBases.get(i)).equals(normBase(base));
            TextView pc = presetChips.get(i);
            pc.setTextColor(active ? 0xFF0B1220 : cDim);
            GradientDrawable g = new GradientDrawable();
            g.setColor(active ? cAccent : cCard2);
            g.setCornerRadius(dp(16));
            pc.setBackground(g);
        }
    }
    private ScrollView chatScroll;
    private EditText input, keyEt, modelEt, baseEt;
    private TextView typingTv, subTv, speakBtn;
    private View chatWrap, setupWrap, hubWrap;
    private LinearLayout hubTiles;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String[]> history = new ArrayList<>(); // {role u/a, text}
    private boolean busy = false;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setTheme(prefs.isDark(this) ? R.style.AppThemeDark : R.style.AppThemeLight);
        boolean d = prefs.isDark(this);
        cBg = d ? 0xFF121826 : 0xFFF4F7FB;
        cCard = d ? 0xFF1A2336 : 0xFFFFFFFF;
        cCard2 = d ? 0xFF2A3650 : 0xFFE8EEF6;
        cText = d ? 0xFFEAF0F8 : 0xFF0B1220;
        cDim = d ? 0xFF93A3BA : 0xFF42536B;
        cAccent = prefs.accent(this);

        migrateOldSettings();
        String sm = prefs.assistantModel().trim();
        if (sm.equalsIgnoreCase("GEMINI") || sm.equalsIgnoreCase("OPENAI")
                || sm.equalsIgnoreCase("CHATGPT") || sm.equalsIgnoreCase("CLAUDE")
                || sm.equalsIgnoreCase("AI")) {
            prefs.setAssistantModel(prefs.assistantBaseUrl().contains("generativelanguage")
                    ? "gemini-2.5-flash" : "gpt-4o-mini");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        setContentView(root);
        Edge.pad(root);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 30, cAccent, true);
        back.setPadding(0, 0, dp(10), dp(4));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("AI Assistant", 20, cText, true));
        subTv = label("", 12, cDim, false);
        titles.addView(subTv);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        speakBtn = label(prefs.assistantSpeak() ? "🔊" : "🔇", 18, cAccent, false);
        speakBtn.setPadding(dp(8), dp(6), dp(4), dp(6));
        speakBtn.setOnClickListener(v -> {
            boolean on = !prefs.assistantSpeak();
            prefs.setAssistantSpeak(on);
            speakBtn.setText(on ? "🔊" : "🔇");
            toast(on ? "Replies will be read aloud" : "Voice replies off");
            if (on) speak("Voice replies on");
        });
        head.addView(speakBtn);
        root.addView(head);

        // action row: the three destinations, always visible and clearly labeled
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, dp(2));
        TextView hubBtn = chip("🌐 AI Hub", false);
        hubBtn.setOnClickListener(v -> showHub());
        actions.addView(hubBtn);
        actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        TextView setupBtn = chip("⚙ Custom AI Setup", false);
        setupBtn.setOnClickListener(v -> showSetup());
        actions.addView(setupBtn);
        actions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        TextView clearBtn = chip("🗑 Clear", false);
        clearBtn.setOnClickListener(v -> {
            history.clear();
            saveHistory();
            renderHistory();
            toast("Chat cleared");
        });
        actions.addView(clearBtn);
        root.addView(actions);

        chatWrap = buildChat();
        root.addView(chatWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setupWrap = buildSetup();
        setupWrap.setVisibility(View.GONE);
        root.addView(setupWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        hubWrap = buildHub();
        hubWrap.setVisibility(View.GONE);
        root.addView(hubWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        initTts();
        loadHistory();
        renderHistory();
        updateSub();
        if (needsSetup()) showSetup();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    /** One-time move from the v0.9/v0.10 Gemini-or-OpenAI settings to the generic ones. */
    private void migrateOldSettings() {
        if (!prefs.assistantKey().isEmpty()) return;
        int oldP = prefs.assistantProvider();
        String oldK = prefs.assistantKey(oldP);
        if (oldK == null || oldK.isEmpty()) return;
        prefs.setAssistantKey(oldK);
        prefs.setAssistantModel(prefs.assistantModel(oldP));
        if (oldP == 0) {
            prefs.setAssistantBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/");
        }
    }

    private boolean needsSetup() {
        String base = prefs.assistantBaseUrl().trim();
        if (base.isEmpty()) return true;
        if (!prefs.assistantKey().trim().isEmpty()) return false;
        // local servers legitimately have no key
        return !(base.contains("localhost") || base.contains("127.0.0.1")
                || base.contains("192.168.") || base.contains("://10.")
                || base.contains("://172."));
    }

    // ------------------------------------------------------------ chat view

    private View buildChat() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        chatScroll = new ScrollView(this);
        chatCol = new LinearLayout(this);
        chatCol.setOrientation(LinearLayout.VERTICAL);
        chatCol.setPadding(0, dp(8), 0, dp(8));
        chatScroll.addView(chatCol);
        wrap.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        typingTv = label("Assistant is typing…", 12.5f, cDim, false);
        typingTv.setPadding(dp(4), dp(2), 0, dp(2));
        typingTv.setVisibility(View.GONE);
        wrap.addView(typingTv);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        input = new EditText(this);
        input.setHint("Message…");
        input.setTextColor(cText);
        input.setHintTextColor(cDim);
        input.setTextSize(15f);
        input.setMaxLines(4);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(cCard);
        ib.setCornerRadius(dp(22));
        input.setBackground(ib);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mic = label("🎤", 18, cText, false);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable();
        mb.setColor(cCard);
        mb.setCornerRadius(dp(22));
        mic.setBackground(mb);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(46), dp(46));
        ml.setMargins(dp(8), 0, 0, 0);
        mic.setLayoutParams(ml);
        mic.setOnClickListener(v -> startListening());
        row.addView(mic);

        TextView send = label("➤", 19, 0xFF0B1220, true);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(cAccent);
        sb.setCornerRadius(dp(22));
        send.setBackground(sb);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(46), dp(46));
        sl.setMargins(dp(8), 0, 0, 0);
        send.setLayoutParams(sl);
        send.setOnClickListener(v -> sendMessage());
        row.addView(send);
        wrap.addView(row);
        return wrap;
    }

    private void updateSub() {
        String base = prefs.assistantBaseUrl().trim();
        String host = base;
        try {
            String h = new URL(base).getHost();
            if (h != null && !h.isEmpty()) host = h;
        } catch (Exception ignored) {}
        subTv.setText("Custom AI • " + prefs.assistantModel() + " • " + host);
    }

    private void showSetup() {
        buildSetupFields();
        chatWrap.setVisibility(View.GONE);
        hubWrap.setVisibility(View.GONE);
        setupWrap.setVisibility(View.VISIBLE);
    }

    private void showChat() {
        setupWrap.setVisibility(View.GONE);
        hubWrap.setVisibility(View.GONE);
        chatWrap.setVisibility(View.VISIBLE);
        updateSub();
    }

    private void showHub() {
        chatWrap.setVisibility(View.GONE);
        setupWrap.setVisibility(View.GONE);
        hubWrap.setVisibility(View.VISIBLE);
        renderHubTiles();
    }

    // ------------------------------------------------------------ AI hub (direct login)

    /** name, url, gradient colors, letter */
    private static final Object[][] AI_SITES = {
            {"ChatGPT", "https://chatgpt.com", 0xFF0EA47A, 0xFF0B7A5C, "C"},
            {"Gemini", "https://gemini.google.com", 0xFF4796E3, 0xFF9177C7, "G"},
            {"Claude", "https://claude.ai", 0xFFD97757, 0xFFB85C38, "C"},
            {"DeepSeek", "https://chat.deepseek.com", 0xFF4D6BFE, 0xFF3143B8, "D"},
            {"Grok", "https://grok.com", 0xFF3A3F4A, 0xFF14161A, "G"},
            {"Perplexity", "https://www.perplexity.ai", 0xFF20808D, 0xFF14555F, "P"},
            {"Copilot", "https://copilot.microsoft.com", 0xFF3B82F6, 0xFF7C5CFF, "C"},
            {"Meta AI", "https://www.meta.ai", 0xFF0082FB, 0xFF0064D1, "M"},
    };

    private View buildHub() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(2), dp(10), dp(2), dp(10));
        col.addView(label("All AIs in one place — direct login", 16, cText, true));
        col.addView(label("Tap a service to open it inside TM Player and log in with "
                + "your own account, exactly like a browser. TM Player never sees "
                + "your password — the login happens on the company's own site.", 13, cDim, false));
        TextView hubSetup = chip("⚙ Custom AI Setup — use your own server / API key instead", false);
        LinearLayout.LayoutParams hsl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsl.topMargin = dp(12);
        hubSetup.setLayoutParams(hsl);
        hubSetup.setOnClickListener(v -> showSetup());
        col.addView(hubSetup);
        hubTiles = new LinearLayout(this);
        hubTiles.setOrientation(LinearLayout.VERTICAL);
        col.addView(hubTiles);
        renderHubTiles();
        TextView note = label("Note: Google sometimes blocks sign-in inside apps "
                + "(“browser may not be secure”). If Gemini refuses to log in here, "
                + "open gemini.google.com in your normal browser instead — or use the "
                + "Gemini preset in ⚙ Custom AI Setup with a free API key.", 12.5f, cDim, false);
        note.setPadding(0, dp(14), 0, 0);
        col.addView(note);
        sv.addView(col);
        return sv;
    }

    private ArrayList<String[]> loadCustomSites() {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.assistantHubCustom());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{o.optString("n"), o.optString("u")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveCustomSites(ArrayList<String[]> list) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] cu : list) {
                arr.put(new JSONObject().put("n", cu[0]).put("u", cu[1]));
            }
            prefs.setAssistantHubCustom(arr.toString());
        } catch (Exception ignored) {}
    }

    private void renderHubTiles() {
        if (hubTiles == null) return;
        hubTiles.removeAllViews();
        ArrayList<Object[]> all = new ArrayList<>();
        for (Object[] b : AI_SITES) {
            all.add(new Object[]{b[0], b[1], b[2], b[3], b[4], false, -1});
        }
        ArrayList<String[]> customs = loadCustomSites();
        int[][] pals = {{0xFF5C6BC0, 0xFF3949AB}, {0xFF26A69A, 0xFF00897B},
                {0xFFAB47BC, 0xFF7C5CFF}, {0xFFF2994A, 0xFFF2784A}, {0xFF42A5F5, 0xFF0288D1}};
        for (int ci = 0; ci < customs.size(); ci++) {
            String[] cu = customs.get(ci);
            int[] pal = pals[ci % pals.length];
            String letter = cu[0].isEmpty() ? "?"
                    : cu[0].substring(0, 1).toUpperCase(Locale.US);
            all.add(new Object[]{cu[0], cu[1], pal[0], pal[1], letter, true, ci});
        }
        all.add(new Object[]{"Add AI", "", 0, 0, "＋", false, -2});
        for (int i = 0; i < all.size(); i += 2) {
            LinearLayout rowH = new LinearLayout(this);
            rowH.setPadding(0, dp(10), 0, 0);
            for (int j = i; j < Math.min(i + 2, all.size()); j++) {
                rowH.addView(hubTile(all.get(j), j == i));
            }
            if (i + 1 >= all.size()) {
                rowH.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            hubTiles.addView(rowH);
        }
    }

    private View hubTile(Object[] e, boolean left) {
        boolean isAdd = Integer.valueOf(-2).equals(e[6]);
        boolean isCustom = Boolean.TRUE.equals(e[5]);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(16), dp(8), dp(14));
        GradientDrawable cg;
        if (isAdd) {
            cg = new GradientDrawable();
            cg.setColor(cCard);
            cg.setStroke(dp(2), cAccent);
        } else {
            cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{(int) e[2], (int) e[3]});
        }
        cg.setCornerRadius(dp(18));
        card.setBackground(cg);
        TextView lg = label((String) e[4], 26, isAdd ? cAccent : 0xFFFFFFFF, true);
        lg.setGravity(Gravity.CENTER);
        card.addView(lg);
        TextView nm = label((String) e[0], 14.5f, isAdd ? cAccent : 0xFFFFFFFF, true);
        nm.setGravity(Gravity.CENTER);
        nm.setPadding(0, dp(6), 0, 0);
        card.addView(nm);
        if (isAdd) {
            card.setOnClickListener(v -> addAiDialog());
        } else {
            final String url = (String) e[1];
            card.setOnClickListener(v -> {
                Intent bi = new Intent(this, BrowserActivity.class);
                bi.putExtra("open_url", url);
                startActivity(bi);
            });
            if (isCustom) {
                final int customIdx = (int) e[6];
                final String cname = (String) e[0];
                card.setOnLongClickListener(v -> {
                    deleteAiDialog(customIdx, cname);
                    return true;
                });
            }
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (left) cl.setMargins(0, 0, dp(5), 0);
        else cl.setMargins(dp(5), 0, 0, 0);
        card.setLayoutParams(cl);
        return card;
    }

    private void addAiDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nameEt = field("AI name (e.g. Poe)");
        box.addView(nameEt);
        EditText urlEt = field("Website (e.g. poe.com)");
        box.addView(urlEt);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Add AI to the Hub")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String n = nameEt.getText().toString().trim();
                    String u = urlEt.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) {
                        toast("Name and website are both needed");
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "https://" + u;
                    }
                    ArrayList<String[]> list = loadCustomSites();
                    list.add(new String[]{n, u});
                    saveCustomSites(list);
                    renderHubTiles();
                    toast(n + " added to the Hub");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAiDialog(int idx, String name) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("This only removes your custom tile from the Hub.")
                .setPositiveButton("Remove", (d, w) -> {
                    ArrayList<String[]> list = loadCustomSites();
                    if (idx >= 0 && idx < list.size()) list.remove(idx);
                    saveCustomSites(list);
                    renderHubTiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ voice

    private void initTts() {
        try {
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    try {
                        int r = tts.setLanguage(Locale.getDefault());
                        if (r == TextToSpeech.LANG_MISSING_DATA
                                || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.US);
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    private void speak(String text) {
        if (tts == null || !ttsReady) {
            toast("No voice (TTS) engine is available on this phone");
            return;
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tmplayer-msg");
        } catch (Exception ignored) {}
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Voice input is not available on this phone (no speech service installed)");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 991);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    ArrayList<String> r = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) {
                        input.setText(r.get(0));
                        sendMessage();
                    }
                }
                @Override public void onError(int error) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        toast("Microphone permission is needed for voice chat");
                    } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                        toast("Voice input is not available on this phone");
                    } else if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        toast("Didn't catch that — try again");
                    }
                }
                @Override public void onReadyForSpeech(Bundle b) { toast("🎤 Listening…"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onEvent(int t, Bundle b) {}
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            toast("Voice input failed to start");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 991 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        }
    }

    // ------------------------------------------------------------ setup view

    private View buildSetup() {
        ScrollView sv = new ScrollView(this);
        setupCol = new LinearLayout(this);
        setupCol.setOrientation(LinearLayout.VERTICAL);
        setupCol.setPadding(dp(2), dp(10), dp(2), dp(10));
        sv.addView(setupCol);
        return sv;
    }

    private void buildSetupFields() {
        setupCol.removeAllViews();
        setupCol.addView(label("Connect any AI", 16, cText, true));
        setupCol.addView(label("Works with any AI service that offers an OpenAI-compatible "
                + "chat API — and with local servers (Ollama, LM Studio) on your own network. "
                + "Your key is stored only on this phone.", 13, cDim, false));

        setupCol.addView(fieldLabel("Popular servers — tap to fill in"));
        presetChips = new ArrayList<>();
        presetBases = new ArrayList<>();
        for (int i = 0; i < PRESETS.length; i += 2) {
            LinearLayout pr = new LinearLayout(this);
            pr.setPadding(0, dp(3), 0, dp(3));
            for (int j = i; j < Math.min(i + 2, PRESETS.length); j++) {
                final String[] pr2 = PRESETS[j];
                TextView pc = chip(pr2[0], false);
                pc.setOnClickListener(v -> {
                    baseEt.setText(pr2[1]);
                    modelEt.setText(pr2[2]);
                    markPresetActive(pr2[1]);
                });
                presetChips.add(pc);
                presetBases.add(pr2[1]);
                pr.addView(pc);
                LinearLayout.LayoutParams pg = new LinearLayout.LayoutParams(dp(8), 1);
                pr.addView(new View(this), pg);
            }
            setupCol.addView(pr);
        }

        setupCol.addView(fieldLabel("Base URL"));
        baseEt = field("https://api.openai.com/v1");
        baseEt.setText(prefs.assistantBaseUrl());
        setupCol.addView(baseEt);

        setupCol.addView(fieldLabel("API key"));
        keyEt = field("API key (leave empty for a local server)");
        keyEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEt.setText(prefs.assistantKey());
        setupCol.addView(keyEt);

        setupCol.addView(fieldLabel("Model"));
        modelEt = field("Model name");
        modelEt.setText(prefs.assistantModel());
        setupCol.addView(modelEt);

        TextView loadBtn = label("Load models for this key", 14, cAccent, true);
        loadBtn.setPadding(0, dp(14), 0, dp(2));
        loadBtn.setOnClickListener(v -> loadModels());
        setupCol.addView(loadBtn);
        modelsBox = new LinearLayout(this);
        modelsBox.setOrientation(LinearLayout.VERTICAL);
        setupCol.addView(modelsBox);

        setupCol.addView(label("Free options: Groq and OpenRouter have free tiers. "
                + "Gemini also works — use the Gemini preset with a free Google AI Studio key. "
                + "Local servers need no key.", 12.5f, cDim, false));

        markPresetActive(prefs.assistantBaseUrl());

        TextView save = label("Save & start chatting", 15, 0xFF0B1220, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(cAccent);
        gb.setCornerRadius(dp(14));
        save.setBackground(gb);
        LinearLayout.LayoutParams sv2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sv2.topMargin = dp(18);
        save.setLayoutParams(sv2);
        save.setOnClickListener(v -> {
            String base = baseEt.getText().toString().trim();
            String model = modelEt.getText().toString().trim();
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                toast("Base URL must start with http:// or https://");
                return;
            }
            if (model.isEmpty()) {
                toast("Enter a model name (or load the list and tap one)");
                return;
            }
            prefs.setAssistantBaseUrl(base);
            prefs.setAssistantKey(keyEt.getText().toString().trim());
            prefs.setAssistantModel(model);
            toast("Saved — the assistant is ready");
            showChat();
        });
        setupCol.addView(save);
        TextView cancel = label("Cancel", 14, cDim, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(12), 0, dp(4));
        cancel.setOnClickListener(v -> showChat());
        setupCol.addView(cancel);
    }

    private void loadModels() {
        String base = baseEt.getText().toString().trim();
        String key = keyEt.getText().toString().trim();
        if (base.isEmpty()) {
            toast("Enter the base URL first");
            return;
        }
        toast("Loading models…");
        new Thread(() -> {
            try {
                ArrayList<String> names = fetchModels(base, key);
                ui.post(() -> renderModelChips(names));
            } catch (Exception e) {
                final String err = friendlyError(e);
                ui.post(() -> toast(err));
            }
        }).start();
    }

    private ArrayList<String> fetchModels(String base, String key) throws Exception {
        String b = base;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        JSONObject resp = getJson(b + "/models", key.isEmpty() ? null : key);
        ArrayList<String> out = new ArrayList<>();
        JSONArray arr = resp.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getJSONObject(i).optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
    }

    private void renderModelChips(ArrayList<String> names) {
        modelsBox.removeAllViews();
        if (names.isEmpty()) {
            modelsBox.addView(label("No models listed by this server.", 12.5f, cDim, false));
            return;
        }
        TextView head = label(names.size() + " models found — tap one to use it:", 12.5f, cDim, false);
        head.setPadding(0, dp(6), 0, dp(4));
        modelsBox.addView(head);
        for (String n : names) {
            TextView mc = chip(n, n.equals(modelEt.getText().toString().trim()));
            mc.setOnClickListener(v -> {
                modelEt.setText(n);
                renderModelChips(names);
            });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ml.topMargin = dp(6);
            mc.setLayoutParams(ml);
            modelsBox.addView(mc);
        }
    }

    // ------------------------------------------------------------ chat logic

    private void sendMessage() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (needsSetup()) {
            toast("Connect an AI first");
            showSetup();
            return;
        }
        input.setText("");
        history.add(new String[]{"u", text});
        saveHistory();
        renderHistory();
        busy = true;
        typingTv.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                final String reply = callApi();
                ui.post(() -> {
                    busy = false;
                    typingTv.setVisibility(View.GONE);
                    history.add(new String[]{"a", reply});
                    saveHistory();
                    renderHistory();
                    if (prefs.assistantSpeak()) speak(reply);
                });
            } catch (Exception e) {
                final String err = friendlyError(e);
                ui.post(() -> {
                    busy = false;
                    typingTv.setVisibility(View.GONE);
                    addBubble("a", "⚠ " + err, true);
                    scrollDown();
                });
            }
        }).start();
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage() == null ? "unknown error" : e.getMessage();
        String low = m.toLowerCase(Locale.US);
        if (m.contains("401") || m.contains("403") || low.contains("api key")) {
            return "The API key was rejected. Check the key in ⚙ Custom AI Setup.";
        }
        if (m.contains("429")) return "Rate limit / quota reached for this key. Try again later.";
        if (m.contains("404") || low.contains("model")) {
            return "Model problem. Open ⚙ Custom AI Setup and tap \"Load models for this key\" "
                    + "to pick a model this server actually offers.";
        }
        return "Could not reach the assistant: " + m;
    }

    /** Sends the last ~20 messages to the configured OpenAI-compatible endpoint. */
    private String callApi() throws Exception {
        String key = prefs.assistantKey().trim();
        String model = prefs.assistantModel().trim();
        String base = prefs.assistantBaseUrl().trim();
        if (base.contains("generativelanguage.googleapis.com")) {
            model = model.toLowerCase(Locale.US);
            if (model.equals("gemini") || model.equals("chatgpt") || model.equals("openai")) {
                model = "gemini-2.5-flash";
            }
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        int from = Math.max(0, history.size() - 20);
        for (int i = from; i < history.size(); i++) {
            String[] msg = history.get(i);
            messages.put(new JSONObject()
                    .put("role", msg[0].equals("u") ? "user" : "assistant")
                    .put("content", msg[1]));
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        JSONObject resp = postJson(base + "/chat/completions", body, key.isEmpty() ? null : key);
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("Empty reply");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String out = msg == null ? "" : msg.optString("content", "").trim();
        if (out.isEmpty()) throw new Exception("Empty reply");
        return out;
    }

    private JSONObject postJson(String urlStr, JSONObject body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        if (code < 200 || code >= 300) throw new Exception(httpError(code, text));
        return new JSONObject(text);
    }

    private JSONObject getJson(String urlStr, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        if (code < 200 || code >= 300) throw new Exception(httpError(code, text));
        return new JSONObject(text);
    }

    private String httpError(int code, String text) {
        String msg = "HTTP " + code;
        try {
            JSONObject e = new JSONObject(text).optJSONObject("error");
            if (e != null) msg += ": " + e.optString("message", "");
        } catch (Exception ignored) {}
        return msg;
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    // ------------------------------------------------------------ rendering

    private void renderHistory() {
        chatCol.removeAllViews();
        if (history.isEmpty()) {
            TextView hello = label("👋 Ask me anything — type, or tap 🎤 and speak.\n"
                    + "Your chats stay on this phone; the assistant answers through "
                    + "the AI server you connected.", 14, cDim, false);
            hello.setGravity(Gravity.CENTER);
            hello.setPadding(dp(16), dp(30), dp(16), 0);
            chatCol.addView(hello);
        }
        for (String[] m : history) addBubble(m[0], m[1], false);
        scrollDown();
    }

    private void addBubble(String role, String text, boolean error) {
        boolean user = role.equals("u");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.END : Gravity.START);
        TextView b = label(text, 14.5f, user ? 0xFF0B1220 : (error ? 0xFFFF8A80 : cText), false);
        b.setPadding(dp(13), dp(9), dp(13), dp(9));
        b.setTextIsSelectable(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(user ? cAccent : cCard);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(user ? dp(56) : 0, dp(3), user ? 0 : dp(56), dp(3));
        b.setLayoutParams(bl);
        if (!user && !error) {
            b.setOnClickListener(v -> speak(text));
        }
        row.addView(b);
        chatCol.addView(row);
    }

    private void scrollDown() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------ storage

    private File historyFile() {
        return new File(getFilesDir(), "ai_chat_history.json");
    }

    private void loadHistory() {
        history.clear();
        try {
            File f = historyFile();
            if (!f.exists()) return;
            JSONArray arr = new JSONArray(readAll(new FileInputStream(f)));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                history.add(new String[]{o.getString("r"), o.getString("t")});
            }
        } catch (Exception ignored) {}
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, history.size() - 100);
            for (int i = from; i < history.size(); i++) {
                arr.put(new JSONObject()
                        .put("r", history.get(i)[0])
                        .put("t", history.get(i)[1]));
            }
            FileOutputStream fos = new FileOutputStream(historyFile());
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ helpers

    private TextView label(String t, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView chip(String t, boolean active) {
        TextView tv = label(t, 13.5f, active ? 0xFF0B1220 : cDim, true);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(active ? cAccent : cCard2);
        g.setCornerRadius(dp(16));
        tv.setBackground(g);
        return tv;
    }

    private TextView fieldLabel(String t) {
        TextView tv = label(t, 13, cDim, true);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private EditText field(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(cText);
        et.setHintTextColor(cDim);
        et.setTextSize(14.5f);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(12));
        et.setBackground(g);
        return et;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
