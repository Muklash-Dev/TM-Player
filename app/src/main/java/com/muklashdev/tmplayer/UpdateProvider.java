package com.muklashdev.tmplayer;

import android.app.Activity;
import android.content.pm.PackageInfo;

/** Minimal ContentProvider that serves the downloaded update APK from the
 *  app cache to the system installer (framework-only FileProvider stand-in). */
public class UpdateProvider extends android.content.ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode)
            throws java.io.FileNotFoundException {
        java.io.File f = new java.io.File(getContext().getCacheDir(), "update.apk");
        if (!f.exists()) throw new java.io.FileNotFoundException("no update file");
        return android.os.ParcelFileDescriptor.open(f,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(android.net.Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public android.database.Cursor query(android.net.Uri uri, String[] p, String s,
                                         String[] a, String o) {
        return null;
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues v) {
        return null;
    }

    @Override
    public int delete(android.net.Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(android.net.Uri uri, android.content.ContentValues v, String s,
                      String[] a) {
        return 0;
    }

    static String versionName(Activity a) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }
}
