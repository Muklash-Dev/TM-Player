package com.muklashdev.tmplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the process alive while a video plays its audio in the background,
 * with a notification that brings the user back to the player.
 */
public class BgPlayService extends Service {

    private static final String CHANNEL = "tm_playback";
    private static final int NOTIF_ID = 42;
    public static String currentTitle = "TM Player";

    public static void start(Context ctx, String title) {
        currentTitle = title;
        Intent i = new Intent(ctx, BgPlayService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BgPlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, PlayerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("TM Player — background play")
                .setContentText(currentTitle)
                .setSmallIcon(R.drawable.ic_nav_videos)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
