package com.muklashdev.tmplayer;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * "Video to MP3" the honest framework way: the audio track is extracted
 * without re-encoding. MP3 tracks are written out as a real .mp3 file;
 * AAC tracks are muxed into an .m4a file. Other codecs report failure
 * instead of pretending.
 */
public final class ConvertAudio {

    public static final class Result {
        public boolean ok;
        public String message;
        public String outName;
    }

    private ConvertAudio() {}

    public static Result convert(Context ctx, VideoItem v) {
        Result r = new Result();
        MediaExtractor ex = new MediaExtractor();
        try {
            if (v.fromVault && v.filePath != null) ex.setDataSource(v.filePath);
            else ex.setDataSource(ctx, Uri.parse(v.uriStr), null);

            int audioTrack = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    fmt = f;
                    break;
                }
            }
            if (audioTrack < 0 || fmt == null) {
                r.message = "No audio track in this video";
                return r;
            }
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            String base = v.name.replaceAll("\\.[^.]+$", "");
            ex.selectTrack(audioTrack);

            if ("audio/mpeg".equals(mime)) {
                // MP3 frames are self-contained: copy samples straight out.
                File tmp = new File(ctx.getCacheDir(), "conv.mp3");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    copySamples(ex, out);
                }
                r.ok = saveToMusic(ctx, tmp, base + ".mp3", "audio/mpeg");
                r.outName = base + ".mp3";
            } else if ("audio/mp4a-latm".equals(mime)) {
                File tmp = new File(ctx.getCacheDir(), "conv.m4a");
                MediaMuxer muxer = new MediaMuxer(tmp.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                try {
                    int outTrack = muxer.addTrack(fmt);
                    muxer.start();
                    ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    while (true) {
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) break;
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = ex.getSampleTime();
                        info.flags = keyFrameFlag(ex.getSampleFlags());
                        muxer.writeSampleData(outTrack, buf, info);
                        ex.advance();
                    }
                } finally {
                    try { muxer.stop(); } catch (Exception ignored) {}
                    muxer.release();
                }
                r.ok = saveToMusic(ctx, tmp, base + ".m4a", "audio/mp4");
                r.outName = base + ".m4a";
            } else {
                r.message = "This video's audio (" + mime + ") can't be extracted without re-encoding";
                return r;
            }
            r.message = r.ok ? "Saved to Music/TM Player" : "Could not save the audio file";
            return r;
        } catch (Exception e) {
            r.ok = false;
            r.message = "Conversion failed: " + e.getMessage();
            return r;
        } finally {
            ex.release();
        }
    }

    private static int keyFrameFlag(int sampleFlags) {
        int f = 0;
        if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            f |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        return f;
    }

    private static void copySamples(MediaExtractor ex, OutputStream out) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(512 * 1024);
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            out.write(buf.array(), 0, size);
            ex.advance();
        }
        out.flush();
    }

    private static boolean saveToMusic(Context ctx, File tmp, String name, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC + "/TM Player");
                cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                if (uri == null) return false;
                try (OutputStream out = ctx.getContentResolver().openOutputStream(uri);
                     java.io.InputStream in = new java.io.FileInputStream(tmp)) {
                    if (out == null) return false;
                    byte[] b = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                cv.clear();
                cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
                return true;
            }
            File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            File folder = new File(music, "TM Player");
            //noinspection ResultOfMethodCallIgnored
            folder.mkdirs();
            File dest = new File(folder, name);
            try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[128 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{dest.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
