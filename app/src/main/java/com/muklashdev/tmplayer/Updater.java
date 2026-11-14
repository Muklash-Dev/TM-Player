package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Self-update for direct installs (GitHub releases). Checks the latest
 * GitHub release of TM-Player, compares dotted versions, and on the user's
 * tap downloads the release APK and hands it to the system installer via
 * UpdateProvider. Nothing installs silently — Android always shows its
 * own install confirmation. Auto-check runs at most once a day and can be
 * switched off in Settings > General.
 */
final class Updater {

    private static final String API =
            "https://api.github.com/repos/Muklash-Dev/TM-Player/releases/latest";
    private static final long THROTTLE_MS = 20L * 60 * 60 * 1000;

    static void checkAuto(Activity act) {
        Prefs prefs = new Prefs(act);
        if (!prefs.updateAuto()) return;
        long now = System.currentTimeMillis();
        if (now - prefs.lastUpdateCheck() < THROTTLE_MS) return;
        prefs.setLastUpdateCheck(now);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!act.isFinishing() && !act.isDestroyed()) check(act, false);
        }, 5000);
    }

    static void check(Activity act, boolean manual) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(9000);
                c.setReadTimeout(9000);
                c.setRequestProperty("User-Agent", "TM-Player");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder sb = new StringBuilder();
                InputStream in = c.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                in.close();
                JSONObject root = new JSONObject(sb.toString());
                String tag = root.optString("tag_name", "");
                String remote = tag.startsWith("v") ? tag.substring(1) : tag;
                String apkUrl = null;
                JSONArray assets = root.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            if (name.contains("TMPlayer")) break;
                        }
                    }
                }
                String notes = root.optString("body", "");
                String current = UpdateProvider.versionName(act);
                boolean newer = isNewer(remote, current);
                final String fApkUrl = apkUrl;
                final boolean fNewer = newer;
                final String fTag = tag, fNotes = notes, fCurrent = current;
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (fNewer && fApkUrl != null) {
                        showUpdateDialog(act, fTag, fNotes, fApkUrl, fCurrent);
                    } else if (manual) {
                        Toast.makeText(act, fNewer
                                        ? "Update found but no APK attached to the release"
                                        : "You are on the latest version (" + fCurrent + ")",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                if (manual) {
                    act.runOnUiThread(() -> {
                        if (!act.isFinishing()) {
                            Toast.makeText(act, "Could not check for updates — check your connection",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private static boolean isNewer(String remote, String current) {
        String[] r = remote.split("\\.");
        String[] c = current.split("\\.");
        for (int i = 0; i < Math.max(r.length, c.length); i++) {
            int rv = i < r.length ? num(r[i]) : 0;
            int cv = i < c.length ? num(c[i]) : 0;
            if (rv != cv) return rv > cv;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void showUpdateDialog(Activity act, String tag, String notes,
                                         String apkUrl, String current) {
        String msg = notes == null ? "" : notes.trim();
        if (msg.length() > 600) msg = msg.substring(0, 600) + "…";
        if (msg.isEmpty()) msg = "A new version of TM Player is ready.";
        msg += "\n\nYour version: " + current;
        new AlertDialog.Builder(act)
                .setTitle("Update available — TM Player " + tag)
                .setMessage(msg)
                .setPositiveButton("Update", (d, w) -> download(act, apkUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void download(Activity act, String apkUrl) {
        ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("Downloading update");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        Executors.newSingleThreadExecutor().execute(() -> {
            File tmp = new File(act.getCacheDir(), "update.apk.tmp");
            File out = new File(act.getCacheDir(), "update.apk");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "TM-Player");
                int len = c.getContentLength();
                InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int n;
                long done = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (len > 0) {
                        final int pct = (int) (done * 100 / len);
                        act.runOnUiThread(() -> pd.setProgress(pct));
                    }
                }
                fos.close();
                in.close();
                if (out.exists()) out.delete();
                if (!tmp.renameTo(out)) throw new Exception("save failed");
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    install(act);
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(act, "Download failed — try again", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void install(Activity act) {
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("Allow app installs")
                    .setMessage("Android needs your permission to let TM Player install "
                            + "its own updates. Tap Open settings, turn on “Allow from "
                            + "this source”, come back and tap Check for updates again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        try {
            Uri uri = Uri.parse("content://com.muklashdev.tmplayer.updates/update.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "The installer could not be opened", Toast.LENGTH_LONG).show();
        }
    }
}
