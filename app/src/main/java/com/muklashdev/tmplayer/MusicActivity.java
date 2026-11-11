package com.muklashdev.tmplayer;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Full-screen music player (the Music tab's expanded view). */
public class MusicActivity extends Activity implements MusicPlayer.Listener {

    private Prefs prefs;
    private MusicPlayer music;
    private TextView titleTv, artistTv, timeCur, timeTotal, shuffleBtn, repeatBtn;
    private ImageButton playBtn;
    private ImageView artImg;
    private android.view.View noteWrap;
    private SeekBar seekBar;
    private boolean userSeeking = false;
    private int cText, cDim, cAccent, cCard2;
    private final java.util.concurrent.ExecutorService exec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler sleepUi =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sleepTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setTheme(prefs.isDark(this) ? R.style.AppThemeDark : R.style.AppThemeLight);
        boolean d = prefs.isDark(this);
        cText = d ? 0xFFF2F6FC : 0xFF0B1220;
        cDim = d ? 0xFF93A3BC : 0xFF5A6B84;
        cAccent = prefs.accent();
        cCard2 = d ? 0xFF182236 : 0xFFE8EEF6;
        music = MusicPlayer.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(30), dp(28), dp(24));
        setContentView(root);

        // Top row: share (PLAYit puts it top-right too)
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView shareBtn = text("Share", 13.5f, cAccent, true);
        shareBtn.setPadding(dp(10), dp(6), dp(2), dp(6));
        shareBtn.setOnClickListener(v -> {
            AudioItem a = music.current();
            if (a == null) return;
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("audio/*");
            send.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(a.uriStr));
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(send, "Share song"));
        });
        topRow.addView(new TextView(this),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topRow.addView(shareBtn);
        root.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Album art (embedded picture when the file has one), else the disc
        android.widget.FrameLayout artWrap = new android.widget.FrameLayout(this);
        LinearLayout discWrap = new LinearLayout(this);
        discWrap.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(cCard2);
        disc.setStroke(dp(3), cAccent);
        discWrap.setBackground(disc);
        ImageView note = new ImageView(this);
        note.setImageResource(R.drawable.ic_nav_music);
        note.setColorFilter(cAccent);
        discWrap.addView(note, new LinearLayout.LayoutParams(dp(84), dp(84)));
        noteWrap = discWrap;
        artWrap.addView(discWrap, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        artImg = new ImageView(this);
        artImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artImg.setVisibility(View.GONE);
        artWrap.addView(artImg, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams awlp = new LinearLayout.LayoutParams(dp(230), dp(230));
        awlp.topMargin = dp(10);
        root.addView(artWrap, awlp);

        titleTv = text("—", 19, cText, true);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, dp(24), 0, 0);
        root.addView(titleTv);
        artistTv = text("", 14, cDim, false);
        artistTv.setGravity(Gravity.CENTER);
        root.addView(artistTv);

        seekBar = new SeekBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(26);
        seekBar.setLayoutParams(slp);
        root.addView(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) timeCur.setText(Prefs.fmtDuration(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                music.seekTo(sb.getProgress());
            }
        });

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        timeCur = text("0:00", 12.5f, cDim, false);
        times.addView(timeCur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timeTotal = text("0:00", 12.5f, cDim, false);
        timeTotal.setGravity(Gravity.RIGHT);
        times.addView(timeTotal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(times);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(18), 0, 0);
        shuffleBtn = text("Shuffle", 13.5f, cDim, true);
        shuffleBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        shuffleBtn.setOnClickListener(v -> { music.shuffle = !music.shuffle; refreshModes(); });
        controls.addView(shuffleBtn);
        ImageButton prev = iconBtn(R.drawable.ic_prev, 54);
        prev.setOnClickListener(v -> music.prev());
        controls.addView(prev);
        playBtn = iconBtn(R.drawable.ic_pause, 72);
        playBtn.setOnClickListener(v -> music.toggle());
        controls.addView(playBtn);
        ImageButton next = iconBtn(R.drawable.ic_next, 54);
        next.setOnClickListener(v -> music.next());
        controls.addView(next);
        repeatBtn = text("Repeat: Off", 13.5f, cDim, true);
        repeatBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        repeatBtn.setOnClickListener(v -> { music.repeat = (music.repeat + 1) % 3; refreshModes(); });
        controls.addView(repeatBtn);
        root.addView(controls);

        LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.HORIZONTAL);
        extra.setGravity(Gravity.CENTER);
        extra.setPadding(0, dp(8), 0, 0);
        TextView timerBtn = text("⏱  Sleep timer", 13.5f, cDim, true);
        timerBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        timerBtn.setOnClickListener(v -> {
            String[] opts = {"Off", "10 min", "20 min", "30 min", "45 min", "60 min"};
            int[] mins = {0, 10, 20, 30, 45, 60};
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Sleep timer — pause after")
                    .setItems(opts, (dlg, w) -> {
                        if (sleepTask != null) sleepUi.removeCallbacks(sleepTask);
                        if (mins[w] == 0) { toast("Sleep timer off"); return; }
                        sleepTask = () -> {
                            if (music.isPlaying()) music.toggle();
                            toast("Sleep timer — music paused");
                        };
                        sleepUi.postDelayed(sleepTask, mins[w] * 60_000L);
                        toast("Will pause in " + mins[w] + " min");
                    })
                    .show();
        });
        extra.addView(timerBtn);
        TextView queueBtn = text("☰  Queue", 13.5f, cDim, true);
        queueBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        queueBtn.setOnClickListener(v -> {
            java.util.List<AudioItem> q = music.queue();
            if (q.isEmpty()) { toast("Queue is empty"); return; }
            String[] names = new String[q.size()];
            for (int i = 0; i < q.size(); i++) {
                names[i] = (i == music.index() ? "▶ " : "") + q.get(i).title;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Play queue")
                    .setItems(names, (dlg, w) -> music.playAt(w))
                    .show();
        });
        extra.addView(queueBtn);
        root.addView(extra);

        TextView hint = text("Music keeps playing while you use other tabs.", 12, cDim, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(20), 0, 0);
        root.addView(hint);

        refreshTrack();
        refreshModes();
    }

    private void refreshModes() {
        shuffleBtn.setTextColor(music.shuffle ? cAccent : cDim);
        repeatBtn.setText(music.repeat == 1 ? "Repeat: All" : music.repeat == 2 ? "Repeat: One" : "Repeat: Off");
        repeatBtn.setTextColor(music.repeat != 0 ? cAccent : cDim);
    }

    private void refreshTrack() {
        AudioItem a = music.current();
        if (a == null) {
            titleTv.setText("Nothing playing");
            artistTv.setText("Pick a song from the Music tab");
            playBtn.setImageResource(R.drawable.ic_play);
            return;
        }
        titleTv.setText(a.title);
        artistTv.setText(a.artist == null || a.artist.isEmpty() ? a.folderName : a.artist);
        seekBar.setMax(Math.max(music.duration(), 1));
        timeTotal.setText(Prefs.fmtDuration(music.duration()));
        playBtn.setImageResource(music.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        loadArt(a);
    }

    private void loadArt(AudioItem a) {
        artImg.setVisibility(View.GONE);
        noteWrap.setVisibility(View.VISIBLE);
        final String uriStr = a.uriStr;
        exec.execute(() -> {
            android.graphics.Bitmap bmp = null;
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, android.net.Uri.parse(uriStr));
                byte[] pic = mmr.getEmbeddedPicture();
                if (pic != null) bmp = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length);
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            final android.graphics.Bitmap fb = bmp;
            runOnUiThread(() -> {
                AudioItem cur = music.current();
                if (fb != null && cur != null && uriStr.equals(cur.uriStr)) {
                    artImg.setImageBitmap(fb);
                    artImg.setVisibility(View.VISIBLE);
                    noteWrap.setVisibility(View.GONE);
                }
            });
        });
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override protected void onStart() { super.onStart(); music.setListener(this); refreshTrack(); }
    @Override protected void onStop() { super.onStop(); }

    @Override public void onTrackChanged() { runOnUiThread(this::refreshTrack); }
    @Override public void onPlayStateChanged() {
        runOnUiThread(() -> playBtn.setImageResource(
                music.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play));
    }
    @Override public void onProgress(int posMs, int durMs) {
        if (userSeeking) return;
        runOnUiThread(() -> {
            if (durMs > 0) seekBar.setMax(durMs);
            seekBar.setProgress(posMs);
            timeCur.setText(Prefs.fmtDuration(posMs));
            timeTotal.setText(Prefs.fmtDuration(durMs));
        });
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private ImageButton iconBtn(int res, int sizeDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(0);
        b.setColorFilter(cText);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
