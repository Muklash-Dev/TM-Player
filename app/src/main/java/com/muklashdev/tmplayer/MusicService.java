package com.muklashdev.tmplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;

import java.io.InputStream;

/**
 * Foreground service that gives TM Player's music playback a proper
 * MediaSession + MediaStyle notification: title, artist, album art,
 * previous / play-pause / next / close actions and a lock-screen seek bar.
 *
 * The playback engine stays MusicPlayer (same process); this service is the
 * system-facing shell. It is declared stopWithTask="false" and returns
 * START_STICKY, so swiping TM Player out of Recents does NOT stop the music.
 * Playback ends only via pause + dismiss, or the notification's close button.
 */
public class MusicService extends Service {

    public static final String ACTION_SYNC = "com.muklashdev.tmplayer.music.SYNC";
    public static final String ACTION_PLAY_PAUSE = "com.muklashdev.tmplayer.music.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.muklashdev.tmplayer.music.NEXT";
    public static final String ACTION_PREV = "com.muklashdev.tmplayer.music.PREV";
    public static final String ACTION_CLOSE = "com.muklashdev.tmplayer.music.CLOSE";

    private static final String CHANNEL = "tm_music";
    private static final int NOTIF_ID = 43;
    private static boolean running = false;

    private MediaSession session;
    private Handler ui;
    private long artForAlbum = -2;
    private Bitmap artCache;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publishState();
            ui.postDelayed(this, 1000);
        }
    };

    /** Called by MusicPlayer whenever track or play state changes. */
    public static void sync(android.content.Context ctx) {
        if (MusicPlayer.get(ctx).current() == null) {
            ctx.stopService(new Intent(ctx, MusicService.class));
            return;
        }
        Intent i = new Intent(ctx, MusicService.class);
        i.setAction(ACTION_SYNC);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ui = new Handler(Looper.getMainLooper());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music playback",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music controls");
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "TMPlayerMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { playPause(true); }
            @Override public void onPause() { playPause(false); }
            @Override public void onSkipToNext() { MusicPlayer.get(MusicService.this).next(); }
            @Override public void onSkipToPrevious() { MusicPlayer.get(MusicService.this).prev(); }
            @Override public void onSeekTo(long pos) { MusicPlayer.get(MusicService.this).seekTo((int) pos); }
            @Override public void onStop() { close(); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        ui.post(tick);
    }

    private void playPause(boolean wantPlay) {
        MusicPlayer mp = MusicPlayer.get(this);
        if (mp.isPlaying() != wantPlay) mp.toggle();
    }

    private void close() {
        MusicPlayer.get(this).stopPlayback();
        if (session != null) session.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_PLAY_PAUSE.equals(a)) {
                MusicPlayer.get(this).toggle();
            } else if (ACTION_NEXT.equals(a)) {
                MusicPlayer.get(this).next();
            } else if (ACTION_PREV.equals(a)) {
                MusicPlayer.get(this).prev();
            } else if (ACTION_CLOSE.equals(a)) {
                close();
                return START_NOT_STICKY;
            } else if (Intent.ACTION_MEDIA_BUTTON.equals(a)) {
                KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                    MusicPlayer mp = MusicPlayer.get(this);
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: mp.toggle(); break;
                        case KeyEvent.KEYCODE_MEDIA_NEXT: mp.next(); break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: mp.prev(); break;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE: playPause(false); break;
                        case KeyEvent.KEYCODE_MEDIA_PLAY: playPause(true); break;
                        default: break;
                    }
                }
            }
        }
        publishAll();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep playing — the user asked for exactly this. The notification
        // stays up with working controls; close lives there.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (ui != null) ui.removeCallbacks(tick);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------ publishing
    private void publishAll() {
        publishState();
        Notification n = buildNotification();
        if (n != null) startForeground(NOTIF_ID, n);
    }

    private void publishState() {
        if (session == null) return;
        MusicPlayer mp = MusicPlayer.get(this);
        AudioItem cur = mp.current();
        if (cur == null) return;
        Bitmap art = albumArt(cur);
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        cur.title == null ? "Unknown title" : cur.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        cur.artist == null ? "" : cur.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM,
                        cur.album == null ? "" : cur.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        mp.duration() > 0 ? mp.duration() : (cur.duration > 0 ? cur.duration : 0));
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(mp.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        mp.position(), 1f)
                .build());
    }

    private PendingIntent actionIntent(String action, int req) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, req, i, flags);
    }

    private Notification buildNotification() {
        MusicPlayer mp = MusicPlayer.get(this);
        AudioItem cur = mp.current();
        if (cur == null || session == null) return null;
        boolean playing = mp.isPlaying();
        Intent open = new Intent(this, MusicActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int oflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) oflags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, oflags);
        Bitmap art = albumArt(cur);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle(cur.title == null ? "TM Player" : cur.title)
                .setContentText(cur.artist == null ? "" : cur.artist)
                .setSubText("TM Player")
                .setSmallIcon(R.drawable.ic_nav_music)
                .setContentIntent(content)
                .setDeleteIntent(actionIntent(ACTION_CLOSE, 14))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous",
                        actionIntent(ACTION_PREV, 11))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", actionIntent(ACTION_PLAY_PAUSE, 12))
                .addAction(android.R.drawable.ic_media_next, "Next",
                        actionIntent(ACTION_NEXT, 13))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close",
                        actionIntent(ACTION_CLOSE, 14))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    // ------------------------------------------------------------ album art
    private Bitmap albumArt(AudioItem cur) {
        long albumId = -1;
        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.ALBUM_ID},
                    MediaStore.Audio.Media._ID + "=?",
                    new String[]{String.valueOf(cur.id)}, null);
            if (c != null) {
                if (c.moveToFirst()) albumId = c.getLong(0);
                c.close();
            }
        } catch (Exception ignored) {}
        if (albumId == artForAlbum && artCache != null) return artCache;
        artForAlbum = albumId;
        artCache = null;
        if (albumId >= 0) {
            try {
                Uri artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId);
                InputStream in = getContentResolver().openInputStream(artUri);
                if (in != null) {
                    artCache = BitmapFactory.decodeStream(in);
                    in.close();
                }
            } catch (Exception ignored) {}
        }
        if (artCache == null) artCache = fallbackArt();
        return artCache;
    }

    private Bitmap fallbackArt() {
        int s = 512;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.rgb(16, 24, 40));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(47, 212, 101));
        p.setTextSize(280f);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("♪", s / 2f, s / 2f + 95f, p);
        return bmp;
    }

    static boolean isRunning() { return running; }
}
