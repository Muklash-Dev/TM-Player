package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Full-screen player on Android's built-in MediaPlayer:
 * gesture brightness / volume / seek, double-tap skip, playback speed,
 * Fit/Fill/Stretch, SRT subtitles, PiP, resume, next/previous.
 */
public class PlayerActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final int REQ_SUBTITLE = 201;

    private Prefs prefs;
    private MediaPlayer mp;
    private TextureView surface;
    private Surface videoSurface;
    private TextView hdrBtn;
    private int hdrMode = 0; // 0 off, 1 enhance, 2 vivid
    private FrameLayout surfaceContainer;
    private View topBar, centerControls, bottomBar;
    private TextView titleTv, gestureLabel, subtitleTv, timeCur, timeTotal;
    private TextView statusOverlay;
    private boolean pipMode = false;
    private boolean lpActive = false;
    private float lpPrevSpeed = 1f;
    private final Runnable lpCheck = new Runnable() {
        @Override public void run() {
            if (mp != null && prepared && mp.isPlaying() && gestureMode == 0) {
                lpActive = true;
                lpPrevSpeed = speed;
                applySpeed(prefs.longPressSpeed());
                showGesture(Prefs.fmtSpeedShort(prefs.longPressSpeed()) + " »");
                if (prefs.longPressVibrate()) {
                    try {
                        android.os.Vibrator vib = (android.os.Vibrator)
                                getSystemService(VIBRATOR_SERVICE);
                        if (vib != null) vib.vibrate(25);
                    } catch (Exception ignored) {}
                }
            }
        }
    };
    private TextView speedBtn, resizeBtn, lockBtn, qualityLabel, unlockBtn;
    private TextView bgBtn, repeatBtn, shuffleBtn;
    private android.widget.ImageButton topBgBtn, sideMuteBtn;
    private boolean muted = false;
    private final Handler sleepUi = new Handler(Looper.getMainLooper());
    private Runnable sleepTask;
    private boolean bgMode = false;
    private int repeatMode = 0; // 0 off, 1 all, 2 one
    private boolean shuffle = false;
    private ImageButton playBtn;
    private SeekBar seekBar;

    private VideoItem item;
    private boolean prepared = false;
    private boolean locked = false;
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private float speed = 1.0f;
    private int resizeMode = 0;
    private int videoW = 0, videoH = 0;
    private List<SrtParser.Cue> cues;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 400);
        }
    };
    private final Runnable hideControls = this::hideControls;

    // gesture state
    private float downX, downY;
    private long downPos;
    private int downVolume;
    private float downBrightness;
    private int gestureMode = 0; // 0 none, 1 seek, 2 bright, 3 volume
    private long lastTapTime = 0;
    private float lastTapX = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speed = prefs.defaultSpeed();
        resizeMode = prefs.fixedRatio() ? prefs.defaultResize() : 0;

        surfaceContainer = findViewById(R.id.surface_container);
        surface = findViewById(R.id.surface);
        surface.setSurfaceTextureListener(this);
        topBar = findViewById(R.id.top_bar);
        centerControls = findViewById(R.id.center_controls);
        bottomBar = findViewById(R.id.bottom_bar);
        titleTv = findViewById(R.id.player_title);
        gestureLabel = findViewById(R.id.gesture_label);
        subtitleTv = findViewById(R.id.subtitle_text);
        timeCur = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        seekBar = findViewById(R.id.seek_bar);
        playBtn = findViewById(R.id.btn_play);
        speedBtn = findViewById(R.id.btn_speed);
        resizeBtn = findViewById(R.id.btn_resize);
        lockBtn = findViewById(R.id.btn_lock);
        qualityLabel = findViewById(R.id.quality_label);
        unlockBtn = findViewById(R.id.btn_unlock);

        item = PlayQueue.current();
        if (item == null) { finish(); return; }
        titleTv.setText(item.name);
        speedBtn.setText(trimFloat(speed) + "x");
        resizeBtn.setText(Prefs.RESIZE_NAMES[resizeMode]);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        playBtn.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_next).setOnClickListener(v -> next());
        findViewById(R.id.btn_prev).setOnClickListener(v -> prev());
        speedBtn.setOnClickListener(v -> cycleSpeed());
        resizeBtn.setOnClickListener(v -> {
            resizeMode = (resizeMode + 1) % 3;
            resizeBtn.setText(Prefs.RESIZE_NAMES[resizeMode]);
            applySurfaceSize();
            showControls();
        });
        bgBtn = findViewById(R.id.btn_bg);
        repeatBtn = findViewById(R.id.btn_repeat);
        shuffleBtn = findViewById(R.id.btn_shuffle);
        bgBtn.setOnClickListener(v -> toggleBg());
        topBgBtn = findViewById(R.id.btn_top_bg);
        topBgBtn.setOnClickListener(v -> toggleBg());
        findViewById(R.id.btn_top_queue).setOnClickListener(v -> showQueue());
        findViewById(R.id.btn_top_more).setOnClickListener(v -> showMoreMenu(v));
        sideMuteBtn = findViewById(R.id.btn_side_mute);
        sideMuteBtn.setOnClickListener(v -> {
            muted = !muted;
            if (mp != null) {
                try { mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) {}
            }
            sideMuteBtn.setImageResource(muted ? R.drawable.ic_mute : R.drawable.ic_volume);
            toast(muted ? "Muted" : "Sound on");
            showControls();
        });
        repeatBtn.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            repeatBtn.setText(repeatMode == 1 ? "Repeat: All" : repeatMode == 2 ? "Repeat: One" : "Repeat: Off");
            repeatBtn.setTextColor(repeatMode != 0 ? prefs.accent() : 0xFFFFFFFF);
            showControls();
        });
        shuffleBtn.setOnClickListener(v -> {
            shuffle = !shuffle;
            shuffleBtn.setTextColor(shuffle ? prefs.accent() : 0xFFFFFFFF);
            showControls();
        });
        lockBtn.setOnClickListener(v -> setLocked(true));
        unlockBtn.setOnClickListener(v -> setLocked(false));
        statusOverlay = findViewById(R.id.status_overlay);
        subtitleTv.setTextSize(prefs.subtitleSize() == 0 ? 14f : prefs.subtitleSize() == 2 ? 21f : 17f);
        findViewById(R.id.btn_rotate).setOnClickListener(v -> {
            if (prefs.orientLock()) {
                toast("Screen orientation is locked — change it in Settings → Video");
                showControls();
            } else {
                rotate();
            }
        });
        findViewById(R.id.btn_pip).setOnClickListener(v -> enterPip());
        findViewById(R.id.btn_shot).setOnClickListener(v -> takeScreenshot());
        // Colourful controls: every player icon gets its own friendly colour.
        tint(R.id.btn_back, 0xFF4FC3F7);
        tint(R.id.btn_shot, 0xFFFFB74D);
        tint(R.id.btn_top_bg, 0xFF81C784);
        tint(R.id.btn_top_queue, 0xFFBA68C8);
        tint(R.id.btn_top_more, 0xFFFFCA28);
        tint(R.id.btn_rotate, 0xFF4DB6AC);
        tint(R.id.btn_pip, 0xFFF48FB1);
        tint(R.id.btn_prev, 0xFFCE93D8);
        tint(R.id.btn_next, 0xFFCE93D8);
        if (playBtn != null) playBtn.setColorFilter(prefs.accent());
        if (sideMuteBtn != null) sideMuteBtn.setColorFilter(0xFFFFCC80);
        if (speedBtn != null) speedBtn.setTextColor(prefs.accent());
        if (resizeBtn != null) resizeBtn.setTextColor(0xFF81C784);
        if (lockBtn != null) lockBtn.setTextColor(0xFFFFB74D);
        if (bgBtn != null) bgBtn.setTextColor(0xFF64B5F6);
        if (repeatBtn != null) repeatBtn.setTextColor(0xFFBA68C8);
        if (shuffleBtn != null) shuffleBtn.setTextColor(0xFFF48FB1);
        hdrBtn = findViewById(R.id.btn_hdr);
        hdrBtn.setOnClickListener(v -> {
            hdrMode = (hdrMode + 1) % 3;
            applyHdr();
            toast(hdrMode == 0 ? "HDR Enhance: Off"
                    : hdrMode == 1 ? "HDR Enhance: On — brighter colors & contrast"
                    : "HDR Enhance: Vivid");
            showControls();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) timeCur.setText(Prefs.fmtDuration(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mp != null && prepared) mp.seekTo(sb.getProgress());
                showControls();
            }
        });

        findViewById(R.id.player_root).setOnTouchListener(this::onRootTouch);
        applyOrientation();
    }

    // ------------------------------------------------------------ playback

    private void startPlayback() {
        releasePlayer();
        prepared = false;
        mp = new MediaPlayer();
        try {
            if (item.fromVault && item.filePath != null) mp.setDataSource(item.filePath);
            else mp.setDataSource(this, Uri.parse(item.uriStr));
            attachSurface(mp);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                videoW = p.getVideoWidth();
                videoH = p.getVideoHeight();
                applySurfaceSize();
                applyOrientation();
                qualityLabel.setText(item.quality().isEmpty()
                        ? (videoW > 0 ? videoW + "×" + videoH : "") : item.quality());
                int dur = p.getDuration();
                seekBar.setMax(Math.max(dur, 0));
                timeTotal.setText(Prefs.fmtDuration(dur));
                p.setPlaybackParams(p.getPlaybackParams().setSpeed(speed));
                long resume = prefs.resumeOn() ? prefs.resumePos(item.uriStr) : 0;
                if (resume > 3000 && resume < dur - 3000) {
                    p.seekTo((int) resume);
                    toast("Resumed at " + Prefs.fmtDuration(resume));
                }
                p.start();
                prefs.addHistory(item.uriStr, item.name);
                playBtn.setImageResource(R.drawable.ic_pause);
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
                showControls();
            });
            mp.setOnCompletionListener(p -> {
                prefs.setResumePos(item.uriStr, 0);
                if (repeatMode == 2) {
                    p.seekTo(0);
                    p.start();
                } else if (!prefs.continuous() && repeatMode == 0) {
                    playBtn.setImageResource(R.drawable.ic_play);
                    showControls();
                    toast("Finished — Continuous play is off");
                } else if (PlayQueue.hasNext()) {
                    next();
                } else if (repeatMode == 1 && !PlayQueue.items.isEmpty()) {
                    PlayQueue.index = 0;
                    item = PlayQueue.current();
                    titleTv.setText(item.name);
                    startPlayback();
                } else {
                    BgPlayService.stop(this);
                    finish();
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                toast("This file can't be played — its format or codec is not supported on this device.");
                return true;
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w; videoH = h;
                applySurfaceSize();
            });
            mp.prepareAsync();
        } catch (Exception e) {
            toast("Could not open this video");
            finish();
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
    }

    private void saveResume() {
        if (mp != null && prepared && item != null) {
            try {
                int pos = mp.getCurrentPosition();
                int dur = mp.getDuration();
                if (pos > 3000 && pos < dur - 3000) prefs.setResumePos(item.uriStr, pos);
                else if (pos >= dur - 3000 && dur > 0) prefs.setResumePos(item.uriStr, 0);
            } catch (Exception ignored) {}
        }
    }

    private void togglePlay() {
        if (mp == null || !prepared) return;
        if (mp.isPlaying()) {
            mp.pause();
            playBtn.setImageResource(R.drawable.ic_play);
        } else {
            mp.start();
            playBtn.setImageResource(R.drawable.ic_pause);
        }
        showControls();
    }

    private void next() {
        if (shuffle && PlayQueue.items.size() > 1) {
            saveResume();
            int n;
            java.util.Random r = new java.util.Random();
            do { n = r.nextInt(PlayQueue.items.size()); } while (n == PlayQueue.index);
            PlayQueue.index = n;
            item = PlayQueue.current();
            titleTv.setText(item.name);
            cues = null;
            subtitleTv.setText("");
            startPlayback();
            return;
        }
        if (!PlayQueue.hasNext()) { toast("No next video"); return; }
        saveResume();
        PlayQueue.index++;
        item = PlayQueue.current();
        titleTv.setText(item.name);
        cues = null;
        subtitleTv.setText("");
        startPlayback();
    }

    private void prev() {
        if (!PlayQueue.hasPrev()) { toast("No previous video"); return; }
        saveResume();
        PlayQueue.index--;
        item = PlayQueue.current();
        titleTv.setText(item.name);
        cues = null;
        subtitleTv.setText("");
        startPlayback();
    }

    private void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < Prefs.SPEEDS.length; i++) if (Prefs.SPEEDS[i] == speed) idx = i;
        speed = Prefs.SPEEDS[(idx + 1) % Prefs.SPEEDS.length];
        speedBtn.setText(trimFloat(speed) + "x");
        if (mp != null && prepared) {
            boolean wasPlaying = mp.isPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
            if (!wasPlaying) mp.pause();
        }
        showControls();
    }

    private String trimFloat(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    // ------------------------------------------------------------ surface

    private void tint(int id, int color) {
        View v = findViewById(id);
        if (v instanceof ImageButton) ((ImageButton) v).setColorFilter(color);
    }

    private void attachSurface(MediaPlayer p) {
        try {
            if (videoSurface == null && surface.isAvailable()) {
                videoSurface = new Surface(surface.getSurfaceTexture());
            }
            if (videoSurface != null) p.setSurface(videoSurface);
        } catch (Exception ignored) {}
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        videoSurface = new Surface(st);
        if (mp != null) {
            // Returning from background play: re-attach the video surface.
            try {
                mp.setSurface(videoSurface);
                applySurfaceSize();
                playBtn.setImageResource(mp.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
                return;
            } catch (Exception ignored) {}
        }
        startPlayback();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applySurfaceSize(); }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        saveResume();
        if (bgMode && mp != null) {
            // Keep the audio alive in the background; drop only the picture.
            try { mp.setSurface(null); } catch (Exception ignored) {}
        } else {
            releasePlayer();
        }
        if (videoSurface != null) {
            try { videoSurface.release(); } catch (Exception ignored) {}
            videoSurface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------ screenshot + HDR enhance

    private void takeScreenshot() {
        Bitmap bmp = null;
        try { bmp = surface.getBitmap(); } catch (Exception ignored) {}
        if (bmp == null) { toast("Screenshot failed — no frame yet"); return; }
        final Bitmap shot = bmp;
        toast("Saving screenshot…");
        new Thread(() -> {
            boolean ok = false;
            String name = "TM_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date()) + ".png";
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/TM Player");
                    cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Images.Media
                                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                    if (uri != null) {
                        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) ok = shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        cv.clear();
                        cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, cv, null, null);
                    }
                } else {
                    java.io.File dir = new java.io.File(android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                            "TM Player");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                        ok = shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    android.media.MediaScannerConnection.scanFile(this,
                            new String[]{f.getAbsolutePath()}, null, null);
                }
            } catch (Exception ignored) {}
            final boolean done = ok;
            runOnUiThread(() -> toast(done
                    ? "Screenshot saved to Pictures/TM Player"
                    : "Screenshot failed"));
        }).start();
        showControls();
    }

    /** "HDR Enhance": a color/contrast lift over the video (not HDR10 decoding). */
    private void applyHdr() {
        if (hdrMode == 0) {
            try { surface.setLayerPaint(null); } catch (Exception ignored) {}
            hdrBtn.setText("HDR");
            hdrBtn.setTextColor(0xFF7FB3E8);
            return;
        }
        float contrast = hdrMode == 1 ? 1.18f : 1.32f;
        float saturation = hdrMode == 1 ? 1.30f : 1.55f;
        float bright = hdrMode == 1 ? 2f : 5f;
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(saturation);
        float t = (1 - contrast) * 127.5f + bright;
        ColorMatrix con = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, t,
                0, contrast, 0, 0, t,
                0, 0, contrast, 0, t,
                0, 0, 0, 1, 0});
        ColorMatrix cm = new ColorMatrix(sat);
        cm.postConcat(con);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        try { surface.setLayerPaint(paint); } catch (Exception ignored) {}
        hdrBtn.setText(hdrMode == 1 ? "HDR" : "HDR+");
        hdrBtn.setTextColor(prefs.accent());
    }

    private void applySurfaceSize() {
        if (videoW <= 0 || videoH <= 0) return;
        int cw = surfaceContainer.getWidth();
        int ch = surfaceContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float videoAspect = (float) videoW / videoH;
        float containerAspect = (float) cw / ch;
        int w, h;
        switch (resizeMode) {
            case 1: // Fill (crop)
                if (videoAspect > containerAspect) { h = ch; w = (int) (ch * videoAspect); }
                else { w = cw; h = (int) (cw / videoAspect); }
                break;
            case 2: // Stretch
                w = cw; h = ch;
                break;
            default: // Fit
                if (videoAspect > containerAspect) { w = cw; h = (int) (cw / videoAspect); }
                else { h = ch; w = (int) (ch * videoAspect); }
                break;
        }
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        lp.width = w;
        lp.height = h;
        surface.setLayoutParams(lp);
    }

    private void applyOrientation() {
        int mode = prefs.orientation();
        if (mode == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return;
        }
        if (mode == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            return;
        }
        int w = item != null && item.width > 0 ? item.width : videoW;
        int h = item != null && item.height > 0 ? item.height : videoH;
        if (w > 0 && h > 0) {
            setRequestedOrientation(w >= h
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private void rotate() {
        int o = getResources().getConfiguration().orientation;
        setRequestedOrientation(o == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        showControls();
    }

    // ------------------------------------------------------------ PiP

    private void applySpeed(float f) {
        speed = f;
        speedBtn.setText(trimFloat(speed) + "x");
        if (mp != null && prepared) {
            boolean wasPlaying = mp.isPlaying();
            try {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
                if (!wasPlaying) mp.pause();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (prefs.autoPip() && mp != null && prepared && mp.isPlaying() && !bgMode
                && android.os.Build.VERSION.SDK_INT >= 26) {
            enterPip();
        }
    }

    private void enterPip() {
        hideControlsNow();
        try {
            Rational aspect = videoW > 0 && videoH > 0
                    ? new Rational(videoW, videoH) : new Rational(16, 9);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspect).build();
            enterPictureInPictureMode(params);
        } catch (Exception e) {
            toast("Picture-in-picture is not available");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        pipMode = inPip;
        if (inPip) hideControlsNow();
        else showControls();
    }

    // ------------------------------------------------------------ subtitles

    private void pickSubtitle() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_SUBTITLE);
        } catch (Exception e) {
            toast("File picker not available");
        }
        showControls();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_SUBTITLE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Executors.newSingleThreadExecutor().execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    List<SrtParser.Cue> parsed = in != null ? SrtParser.parse(in) : null;
                    ui.post(() -> {
                        if (parsed != null && !parsed.isEmpty()) {
                            cues = parsed;
                            toast("Subtitles loaded (" + parsed.size() + " lines)");
                        } else toast("No subtitles found in that file (.srt expected)");
                    });
                } catch (Exception e) {
                    ui.post(() -> toast("Could not read that subtitle file"));
                }
            });
        }
    }

    // ------------------------------------------------------------ controls

    private void toggleBg() {
        bgMode = !bgMode;
        bgBtn.setTextColor(bgMode ? prefs.accent() : 0xFFFFFFFF);
        if (topBgBtn != null) topBgBtn.setColorFilter(bgMode ? prefs.accent() : 0xFFFFFFFF);
        if (bgMode) {
            BgPlayService.start(this, item.name);
            toast("Background play ON — press Back and the audio keeps playing");
        } else {
            BgPlayService.stop(this);
            toast("Background play OFF");
        }
        showControls();
    }

    private void showQueue() {
        if (PlayQueue.items.isEmpty()) { toast("Queue is empty"); return; }
        String[] names = new String[PlayQueue.items.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i == PlayQueue.index ? "▶ " : "") + PlayQueue.items.get(i).name;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Up next")
                .setItems(names, (d, w) -> {
                    saveResume();
                    PlayQueue.index = w;
                    item = PlayQueue.current();
                    titleTv.setText(item.name);
                    cues = null;
                    subtitleTv.setText("");
                    startPlayback();
                })
                .show();
    }

    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Details");
        menu.getMenu().add(0, 2, 1, "Add to Playlist");
        menu.getMenu().add(0, 3, 2, "Convert to Audio");
        menu.getMenu().add(0, 4, 3, "Sleep timer");
        menu.getMenu().add(0, 5, 4, "Share");
        menu.getMenu().add(0, 6, 5, "Load subtitle (.srt)");
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Duration: ").append(Prefs.fmtDuration(item.duration)).append('\n');
                    sb.append("Size: ").append(Prefs.fmtSize(item.size)).append('\n');
                    if (item.width > 0) sb.append("Resolution: ").append(item.width)
                            .append('×').append(item.height).append('\n');
                    sb.append("Folder: ").append(item.folderName != null ? item.folderName : "—");
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(item.name).setMessage(sb.toString())
                            .setPositiveButton("OK", null).show();
                    break;
                }
                case 2: {
                    java.util.List<String> names = prefs.playlistNames();
                    java.util.List<String> opts = new java.util.ArrayList<>(names);
                    opts.add("＋ New playlist…");
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Add to playlist")
                            .setItems(opts.toArray(new String[0]), (d, w) -> {
                                if (w == names.size()) {
                                    android.widget.EditText input = new android.widget.EditText(this);
                                    input.setHint("Playlist name");
                                    new android.app.AlertDialog.Builder(this)
                                            .setTitle("New playlist")
                                            .setView(input)
                                            .setPositiveButton("Create & add", (d2, w2) -> {
                                                String n = input.getText().toString().trim();
                                                if (n.isEmpty()) return;
                                                prefs.createPlaylist(n);
                                                prefs.addToPlaylist(n, item.uriStr, item.name);
                                                toast("Added to " + n);
                                            })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                } else {
                                    prefs.addToPlaylist(names.get(w), item.uriStr, item.name);
                                    toast("Added to " + names.get(w));
                                }
                            })
                            .show();
                    break;
                }
                case 3: {
                    toast("Extracting audio…");
                    new Thread(() -> {
                        ConvertAudio.Result r = ConvertAudio.convert(this, item);
                        runOnUiThread(() -> toast(r.message));
                    }).start();
                    break;
                }
                case 4: {
                    String[] opts = {"Off", "10 min", "20 min", "30 min", "45 min", "60 min"};
                    int[] mins = {0, 10, 20, 30, 45, 60};
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Sleep timer — pause after")
                            .setItems(opts, (d, w) -> {
                                if (sleepTask != null) sleepUi.removeCallbacks(sleepTask);
                                if (mins[w] == 0) { toast("Sleep timer off"); return; }
                                sleepTask = () -> {
                                    if (mp != null && prepared && mp.isPlaying()) {
                                        mp.pause();
                                        playBtn.setImageResource(R.drawable.ic_play);
                                    }
                                    toast("Sleep timer — playback paused");
                                };
                                sleepUi.postDelayed(sleepTask, mins[w] * 60_000L);
                                toast("Will pause in " + mins[w] + " min");
                            })
                            .show();
                    break;
                }
                case 6: pickSubtitle(); break;
                case 5: {
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    send.setType("video/*");
                    send.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(item.uriStr));
                    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(send, "Share video"));
                    break;
                }
            }
            return true;
        });
        menu.show();
    }

    private void showControls() {
        if (locked) return;
        controlsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        centerControls.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        if (sideMuteBtn != null) sideMuteBtn.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, 3500);
    }

    private void hideControls() {
        if (mp != null && prepared && !mp.isPlaying()) return; // keep visible while paused
        hideControlsNow();
    }

    private void hideControlsNow() {
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        centerControls.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        if (sideMuteBtn != null) sideMuteBtn.setVisibility(View.GONE);
    }

    private void setLocked(boolean lock) {
        locked = lock;
        if (lock) {
            hideControlsNow();
            unlockBtn.setVisibility(View.VISIBLE);
            ui.postDelayed(() -> unlockBtn.setVisibility(View.GONE), 1800);
        } else {
            unlockBtn.setVisibility(View.GONE);
            showControls();
        }
    }

    private void updateProgress() {
        updateStatusOverlay();
        if (mp == null || !prepared || userSeeking) return;
        try {
            int pos = mp.getCurrentPosition();
            seekBar.setProgress(pos);
            timeCur.setText(Prefs.fmtDuration(pos));
            if (cues != null) {
                SrtParser.Cue c = SrtParser.at(cues, pos);
                subtitleTv.setText(c != null ? c.text : "");
            }
            playBtn.setImageResource(mp.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        } catch (Exception ignored) {}
    }

    private void updateStatusOverlay() {
        if (statusOverlay == null) return;
        if (!prefs.showStatus() || controlsVisible || pipMode) {
            statusOverlay.setVisibility(View.GONE);
            return;
        }
        try {
            android.content.Intent batt = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int pct = -1;
            if (batt != null) {
                int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0) pct = Math.round(level * 100f / Math.max(scale, 1));
            }
            String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date());
            statusOverlay.setText(pct >= 0 ? time + "  •  " + pct + "%" : time);
            statusOverlay.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ gestures

    private boolean onRootTouch(View v, MotionEvent e) {
        if (locked) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                unlockBtn.setVisibility(View.VISIBLE);
                ui.postDelayed(() -> unlockBtn.setVisibility(View.GONE), 1800);
            }
            return true;
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                gestureMode = 0;
                lpActive = false;
                if (prefs.longPressOn()) ui.postDelayed(lpCheck, 450);
                if (mp != null && prepared) downPos = mp.getCurrentPosition();
                downVolume = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
                downBrightness = getWindow().getAttributes().screenBrightness;
                if (downBrightness < 0) downBrightness = 0.5f;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                int threshold = 24;
                if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) ui.removeCallbacks(lpCheck);
                if (!prefs.gesturesOn()) return true;
                if (gestureMode == 0 && (Math.abs(dx) > threshold || Math.abs(dy) > threshold)) {
                    if (Math.abs(dx) > Math.abs(dy)) gestureMode = 1;
                    else gestureMode = downX < v.getWidth() / 2f ? 2 : 3;
                }
                float range = Math.max(v.getHeight(), 1);
                if (gestureMode == 1 && mp != null && prepared) {
                    long dur = mp.getDuration();
                    long target = downPos + (long) (dx / Math.max(v.getWidth(), 1) * dur * 0.5);
                    target = Math.max(0, Math.min(target, dur));
                    showGesture(Prefs.fmtDuration(target) + " / " + Prefs.fmtDuration(dur));
                } else if (gestureMode == 2) {
                    float nb = Math.max(0.01f, Math.min(1f, downBrightness - dy / range));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = nb;
                    getWindow().setAttributes(lp);
                    showGesture("Brightness " + Math.round(nb * 100) + "%");
                } else if (gestureMode == 3 && am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int nv = Math.max(0, Math.min(max,
                            downVolume + Math.round(-dy / range * max)));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0);
                    showGesture("Volume " + Math.round((float) nv / max * 100) + "%");
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                ui.removeCallbacks(lpCheck);
                if (lpActive) {
                    lpActive = false;
                    applySpeed(lpPrevSpeed);
                    gestureLabel.setVisibility(View.GONE);
                    gestureMode = 0;
                    return true;
                }
                gestureLabel.setVisibility(View.GONE);
                if (gestureMode == 1 && mp != null && prepared) {
                    long dur = mp.getDuration();
                    long target = downPos + (long) (dx / Math.max(v.getWidth(), 1) * dur * 0.5);
                    mp.seekTo((int) Math.max(0, Math.min(target, dur)));
                } else if (gestureMode == 0) {
                    // tap: single toggles controls, double seeks / toggles play
                    long now = System.currentTimeMillis();
                    if (!prefs.doubleTapOn()) {
                        lastTapTime = 0;
                        if (controlsVisible) hideControlsNow(); else showControls();
                    } else if (now - lastTapTime < 300 && Math.abs(e.getX() - lastTapX) < 200) {
                        lastTapTime = 0;
                        handleDoubleTap(e.getX(), v.getWidth());
                    } else {
                        lastTapTime = now;
                        lastTapX = e.getX();
                        ui.postDelayed(() -> {
                            if (lastTapTime != 0 && System.currentTimeMillis() - lastTapTime >= 280) {
                                if (controlsVisible) hideControlsNow(); else showControls();
                                lastTapTime = 0;
                            }
                        }, 300);
                    }
                }
                gestureMode = 0;
                return true;
            }
        }
        return true;
    }

    private void handleDoubleTap(float x, float width) {
        if (mp == null || !prepared) return;
        int secs = prefs.doubleTapSeconds();
        if (x < width / 3f) {
            mp.seekTo(Math.max(0, mp.getCurrentPosition() - secs * 1000));
            showGesture("« " + secs + "s");
        } else if (x > width * 2 / 3f) {
            mp.seekTo(Math.min(mp.getDuration(), mp.getCurrentPosition() + secs * 1000));
            showGesture(secs + "s »");
        } else {
            togglePlay();
        }
        ui.postDelayed(() -> gestureLabel.setVisibility(View.GONE), 700);
    }

    private void showGesture(String s) {
        gestureLabel.setText(s);
        gestureLabel.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (bgMode && mp != null && prepared) {
            moveTaskToBack(true);
            toast("Playing in the background — open the notification to come back");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        saveResume();
        super.onPause();
        if (bgMode) return; // background play: keep going
        if (mp != null && prepared && !isInPictureInPictureMode()) {
            if (mp.isPlaying()) {
                mp.pause();
                playBtn.setImageResource(R.drawable.ic_play);
            }
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(progressTick);
        ui.removeCallbacks(hideControls);
        BgPlayService.stop(this);
        releasePlayer();
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
