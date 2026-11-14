package com.muklashdev.tmplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/**
 * Original PDF toolkit for TM Documents Reader, built only on Android's
 * public framework APIs (PdfRenderer + PdfDocument). Feature-inspired by
 * common PDF utilities; no third-party code. Page-based operations render
 * pages and re-emit them, so output pages are images (text is not
 * selectable in re-emitted files) and original metadata is dropped.
 */
final class PdfEngine {

    interface Progress {
        void onProgress(int done, int total);
    }

    /** One source PDF + how to transform it inside a re-emit job. */
    static class Job {
        Uri src;
        String pagesSpec;          // null/empty = all pages, e.g. "1-3,5"
        int dpi = 150;
        int rotateDeg = 0;         // 0 / 90 / 180 / 270
        String watermark;          // null = none
        boolean pageNumbers;
        Bitmap signature;          // stamped bottom-right of the job's last page
    }

    static PdfRenderer open(Context ctx, Uri uri, ParcelFileDescriptor[] fdOut) throws Exception {
        ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) throw new Exception("Cannot open file");
        fdOut[0] = fd;
        return new PdfRenderer(fd); // throws SecurityException on password PDFs
    }

    /** Page count, or -1 when the file cannot be read, -2 when password-protected. */
    static int pageCount(Context ctx, Uri uri) {
        ParcelFileDescriptor[] fds = new ParcelFileDescriptor[1];
        try {
            PdfRenderer r = open(ctx, uri, fds);
            int n = r.getPageCount();
            r.close();
            fds[0].close();
            return n;
        } catch (SecurityException se) {
            return -2;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Renders an open page into a white-backed bitmap of the given pixel width. */
    static Bitmap renderPage(PdfRenderer.Page page, int targetW) {
        float s = (float) targetW / page.getWidth();
        int w = Math.max(2, Math.round(page.getWidth() * s));
        int h = Math.max(2, Math.round(page.getHeight() * s));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Matrix m = new Matrix();
        m.setScale(s, s);
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bmp;
    }

    /** Parses "1-3,5, 8" (1-based) into sorted 0-based page indexes within count. */
    static List<Integer> parsePages(String spec, int count) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (spec == null || spec.trim().isEmpty()) {
            for (int i = 0; i < count; i++) out.add(i);
            return new ArrayList<>(out);
        }
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                if (part.contains("-")) {
                    String[] ab = part.split("-", 2);
                    int a = Integer.parseInt(ab[0].trim());
                    int b = Integer.parseInt(ab[1].trim());
                    for (int p = Math.min(a, b); p <= Math.max(a, b); p++) {
                        if (p >= 1 && p <= count) out.add(p - 1);
                    }
                } else {
                    int p = Integer.parseInt(part);
                    if (p >= 1 && p <= count) out.add(p - 1);
                }
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(out);
    }

    /**
     * The one re-emit pipeline behind merge / extract / compress / rotate /
     * watermark / page numbers / sign / clean-metadata. Jobs run in order;
     * page numbers count across the whole output.
     */
    static void transform(Context ctx, List<Job> jobs, File out, Progress prog) throws Exception {
        // Pre-count for progress + "N / total" page numbers.
        int total = 0;
        List<List<Integer>> plan = new ArrayList<>();
        List<PdfRenderer> renderers = new ArrayList<>();
        List<ParcelFileDescriptor> fds = new ArrayList<>();
        try {
            for (Job job : jobs) {
                ParcelFileDescriptor[] f = new ParcelFileDescriptor[1];
                PdfRenderer r = open(ctx, job.src, f);
                renderers.add(r);
                fds.add(f[0]);
                List<Integer> pages = parsePages(job.pagesSpec, r.getPageCount());
                if (pages.isEmpty()) throw new Exception("No pages selected in one of the files");
                plan.add(pages);
                total += pages.size();
            }
            PdfDocument doc = new PdfDocument();
            int pageNo = 0;
            for (int ji = 0; ji < jobs.size(); ji++) {
                Job job = jobs.get(ji);
                PdfRenderer r = renderers.get(ji);
                List<Integer> pages = plan.get(ji);
                for (int k = 0; k < pages.size(); k++) {
                    PdfRenderer.Page pg = r.openPage(pages.get(k));
                    int targetW = (int) Math.round(pg.getWidth() / 72.0 * job.dpi);
                    Bitmap bmp = renderPage(pg, Math.max(72, targetW));
                    pg.close();
                    if (job.rotateDeg % 360 != 0) {
                        Matrix m = new Matrix();
                        m.postRotate(job.rotateDeg);
                        Bitmap rb = Bitmap.createBitmap(bmp, 0, 0,
                                bmp.getWidth(), bmp.getHeight(), m, true);
                        bmp.recycle();
                        bmp = rb;
                    }
                    Canvas cv = new Canvas(bmp);
                    if (job.watermark != null && !job.watermark.isEmpty()) {
                        drawWatermark(cv, job.watermark);
                    }
                    if (job.pageNumbers) {
                        drawPageNumber(cv, (pageNo + 1) + " / " + total);
                    }
                    if (job.signature != null && k == pages.size() - 1) {
                        drawSignature(cv, job.signature);
                    }
                    int pw = Math.max(36, (int) Math.round(bmp.getWidth() * 72.0 / job.dpi));
                    int ph = Math.max(36, (int) Math.round(bmp.getHeight() * 72.0 / job.dpi));
                    PdfDocument.Page page = doc.startPage(
                            new PdfDocument.PageInfo.Builder(pw, ph, pageNo).create());
                    page.getCanvas().drawBitmap(bmp, null, new Rect(0, 0, pw, ph), null);
                    doc.finishPage(page);
                    bmp.recycle();
                    pageNo++;
                    if (prog != null) prog.onProgress(pageNo, total);
                }
            }
            FileOutputStream fos = new FileOutputStream(out);
            doc.writeTo(fos);
            fos.close();
            doc.close();
        } finally {
            for (PdfRenderer r : renderers) try { r.close(); } catch (Exception ignored) {}
            for (ParcelFileDescriptor f : fds) try { f.close(); } catch (Exception ignored) {}
        }
    }

    private static void drawWatermark(Canvas cv, String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF888888);
        p.setAlpha(64);
        p.setTextSize(cv.getWidth() * 0.085f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        cv.save();
        cv.rotate(-30, cv.getWidth() / 2f, cv.getHeight() / 2f);
        cv.drawText(text, cv.getWidth() / 2f, cv.getHeight() / 2f, p);
        cv.restore();
    }

    private static void drawPageNumber(Canvas cv, String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF555555);
        p.setTextSize(Math.max(11f, cv.getWidth() * 0.028f));
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(text, cv.getWidth() / 2f, cv.getHeight() - cv.getWidth() * 0.03f, p);
    }

    private static void drawSignature(Canvas cv, Bitmap sig) {
        int w = (int) (cv.getWidth() * 0.30f);
        int h = (int) (sig.getHeight() * (w / (float) sig.getWidth()));
        int margin = (int) (cv.getWidth() * 0.05f);
        Rect dst = new Rect(cv.getWidth() - margin - w, cv.getHeight() - margin - h,
                cv.getWidth() - margin, cv.getHeight() - margin);
        cv.drawBitmap(sig, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    /** PDF -> one image file per page. Returns the number of pages written. */
    static int pdfToImages(Context ctx, Uri src, int dpi, boolean png,
                           File dir, Progress prog) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create folder");
        ParcelFileDescriptor[] f = new ParcelFileDescriptor[1];
        PdfRenderer r = open(ctx, src, f);
        try {
            int n = r.getPageCount();
            for (int i = 0; i < n; i++) {
                PdfRenderer.Page pg = r.openPage(i);
                int targetW = (int) Math.round(pg.getWidth() / 72.0 * dpi);
                Bitmap bmp = renderPage(pg, Math.max(72, targetW));
                pg.close();
                File img = new File(dir, "page_" + (i + 1) + (png ? ".png" : ".jpg"));
                FileOutputStream fos = new FileOutputStream(img);
                bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                        92, fos);
                fos.close();
                bmp.recycle();
                if (prog != null) prog.onProgress(i + 1, n);
            }
            return n;
        } finally {
            r.close();
            f[0].close();
        }
    }

    /** Images -> one PDF (each image becomes a page at 150 dpi sizing). */
    static void imagesToPdf(Context ctx, List<Uri> images, File out, Progress prog)
            throws Exception {
        PdfDocument doc = new PdfDocument();
        int done = 0;
        for (Uri u : images) {
            Bitmap bmp = decodeDownsampled(ctx, u, 2200);
            if (bmp == null) continue;
            int pw = Math.max(36, (int) Math.round(bmp.getWidth() * 72.0 / 150));
            int ph = Math.max(36, (int) Math.round(bmp.getHeight() * 72.0 / 150));
            PdfDocument.Page page = doc.startPage(
                    new PdfDocument.PageInfo.Builder(pw, ph, done).create());
            page.getCanvas().drawBitmap(bmp, null, new Rect(0, 0, pw, ph), null);
            doc.finishPage(page);
            bmp.recycle();
            done++;
            if (prog != null) prog.onProgress(done, images.size());
        }
        if (done == 0) {
            doc.close();
            throw new Exception("No readable images selected");
        }
        FileOutputStream fos = new FileOutputStream(out);
        doc.writeTo(fos);
        fos.close();
        doc.close();
    }

    private static Bitmap decodeDownsampled(Context ctx, Uri u, int maxDim) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(u);
            if (is == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, o);
            is.close();
            int sample = 1;
            while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            InputStream is2 = ctx.getContentResolver().openInputStream(u);
            if (is2 == null) return null;
            Bitmap b = BitmapFactory.decodeStream(is2, null, o2);
            is2.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
