package com.muklashdev.tmplayer;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * App-wide music playback singleton — keeps playing while the user moves
 * between tabs. Video background-play uses the same idea in PlayerActivity.
 */
public final class MusicPlayer {

    public interface Listener {
        void onTrackChanged();
        void onPlayStateChanged();
        void onProgress(int posMs, int durMs);
    }

    private static MusicPlayer instance;

    public static synchronized MusicPlayer get(Context ctx) {
        if (instance == null) instance = new MusicPlayer(ctx.getApplicationContext());
        return instance;
    }

    private final Context appCtx;
    private MediaPlayer mp;
    private List<AudioItem> queue = new ArrayList<>();
    private int index = -1;
    private boolean prepared = false;
    public boolean shuffle = false;
    public int repeat = 0; // 0 off, 1 all, 2 one
    private Listener listener;
    private final Random random = new Random();
    private final android.os.Handler progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable progressLoop = new Runnable() {
        @Override public void run() {
            if (mp != null && prepared && listener != null) {
                try { listener.onProgress(mp.getCurrentPosition(), mp.getDuration()); }
                catch (Exception ignored) {}
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    private MusicPlayer(Context ctx) {
        appCtx = ctx;
        progressHandler.post(progressLoop);
    }

    public void setListener(Listener l) { listener = l; }
    public List<AudioItem> queue() { return queue; }
    public int index() { return index; }

    public AudioItem current() {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); }
        catch (Exception e) { return false; }
    }

    public int position() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; }
        catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return mp != null && prepared ? mp.getDuration() : 0; }
        catch (Exception e) { return 0; }
    }

    public void playQueue(List<AudioItem> items, int start) {
        queue = new ArrayList<>(items);
        playAt(start);
    }

    public void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        release();
        AudioItem a = queue.get(i);
        mp = new MediaPlayer();
        try {
            mp.setWakeMode(appCtx, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(appCtx, Uri.parse(a.uriStr));
            mp.setOnPreparedListener(p -> {
                prepared = true;
                p.start();
                notifyTrack();
            });
            mp.setOnCompletionListener(p -> onComplete());
            mp.setOnErrorListener((p, w, e) -> { next(); return true; });
            mp.prepareAsync();
            notifyTrack();
        } catch (Exception e) {
            next();
        }
    }

    private void onComplete() {
        if (repeat == 2) { playAt(index); return; }
        if (index < queue.size() - 1) { next(); return; }
        if (repeat == 1 && !queue.isEmpty()) { playAt(shuffle ? randomIndex() : 0); return; }
        notifyPlay();
    }

    private int randomIndex() {
        if (queue.size() <= 1) return 0;
        int n;
        do { n = random.nextInt(queue.size()); } while (n == index);
        return n;
    }

    public void next() {
        if (queue.isEmpty()) return;
        playAt(shuffle ? randomIndex() : (index + 1) % queue.size());
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (position() > 3000) { seekTo(0); return; }
        playAt((index - 1 + queue.size()) % queue.size());
    }

    public void toggle() {
        if (mp == null || !prepared) {
            // Player was released (e.g. notification Close) — restart the track.
            if (index >= 0 && index < queue.size()) playAt(index);
            return;
        }
        try {
            if (mp.isPlaying()) mp.pause(); else mp.start();
            notifyPlay();
        } catch (Exception ignored) {}
    }

    /** Stops playback, releases the player and clears the queue,
        so nothing (mini player, notification) lingers afterwards. */
    public void stopPlayback() {
        release();
        queue.clear();
        index = -1;
        notifyPlay();
    }

    public void seekTo(int ms) {
        if (mp != null && prepared) {
            try { mp.seekTo(ms); } catch (Exception ignored) {}
        }
    }

    private void release() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
    }

    private void notifyTrack() {
        if (listener != null) listener.onTrackChanged();
        MusicService.sync(appCtx);
        notifyPlay();
    }

    private void notifyPlay() {
        if (listener != null) listener.onPlayStateChanged();
        MusicService.sync(appCtx);
    }
}
