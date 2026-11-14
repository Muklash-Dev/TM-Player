package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TM Documents Reader — continuous-scroll PDF viewer on the framework
 * PdfRenderer. Pages render inside a window around the visible one and
 * far pages are recycled, so heavy files stay smooth. Zoom re-renders at
 * 100 / 150 / 200 / 300 percent.
 */
public class ReaderActivity extends Activity {

    private int cBg, cCard, cText, cDim, cAccent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Uri uri;
    private ParcelFileDescriptor fd;
    private PdfRenderer renderer;
    private int pageCount = 0;
    private float[] aspects = new float[0];
    private ImageView[] pageViews = new ImageView[0];
    private Bitmap[] pageBmps = new Bitmap[0];
    private boolean[] rendered = new boolean[0];

    private ScrollView vScroll;
    private LinearLayout pagesCol;
    private TextView pageLabel, zoomBtn;
    private float zoom = 1.0f;
    private int currentPage = 0;
    private boolean closed = false;
    private final Runnable renderTask = this::renderWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs prefs = new Prefs(this);
        boolean dark = prefs.isDark(this);
        cBg = dark ? 0xFF121826 : 0xFFF4F6FA;
        cCard = dark ? 0xFF1A2336 : 0xFFFFFFFF;
        cText = dark ? 0xFFEAF0F8 : 0xFF14202E;
        cDim = dark ? 0xFF93A3BA : 0xFF5A6B80;
        cAccent = prefs.accent();

        Uri data = getIntent() == null ? null : getIntent().getData();
        String extra = getIntent() == null ? null : getIntent().getStringExtra("uri");
        uri = data != null ? data : (extra != null ? Uri.parse(extra) : null);
        if (uri == null) {
            toast("No document to open");
            finish();
            return;
        }
        buildUi(displayName(uri));
        openPdf();
    }

    private void buildUi(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        Edge.pad(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(8), dp(8), dp(4));
        TextView back = label("‹", 30, cAccent, true);
        back.setPadding(dp(2), dp(2), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = label(title == null || title.isEmpty() ? "Document" : title,
                16.5f, cText, true);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pageLabel = label("…", 13.5f, cDim, true);
        pageLabel.setPadding(dp(8), dp(6), dp(4), dp(6));
        pageLabel.setOnClickListener(v -> jumpDialog());
        head.addView(pageLabel);
        zoomBtn = label("100%", 13.5f, cAccent, true);
        zoomBtn.setPadding(dp(10), dp(6), dp(6), dp(6));
        zoomBtn.setOnClickListener(v -> cycleZoom());
        head.addView(zoomBtn);
        root.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setFillViewport(true);
        vScroll = new ScrollView(this);
        vScroll.setFillViewport(true);
        pagesCol = new LinearLayout(this);
        pagesCol.setOrientation(LinearLayout.VERTICAL);
        pagesCol.setGravity(Gravity.CENTER_HORIZONTAL);
        pagesCol.setPadding(dp(6), dp(8), dp(6), dp(16));
        vScroll.addView(pagesCol);
        hs.addView(vScroll);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        vScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            updateCurrentPage();
            ui.removeCallbacks(renderTask);
            ui.postDelayed(renderTask, 130);
        });
        setContentView(root);
    }

    private void openPdf() {
        exec.execute(() -> {
            try {
                ParcelFileDescriptor[] f = new ParcelFileDescriptor[1];
                PdfRenderer r = PdfEngine.open(this, uri, f);
                fd = f[0];
                renderer = r;
                pageCount = r.getPageCount();
                aspects = new float[pageCount];
                for (int i = 0; i < pageCount; i++) {
                    PdfRenderer.Page p = r.openPage(i);
                    aspects[i] = (float) p.getHeight() / Math.max(1, p.getWidth());
                    p.close();
                }
                ui.post(() -> {
                    if (closed) return;
                    buildPages();
                    pageLabel.setText("1 / " + pageCount);
                    renderWindow();
                });
            } catch (SecurityException se) {
                ui.post(() -> {
                    toast("This PDF is password-protected — TM Documents cannot open locked PDFs");
                    finish();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    toast("Could not open this PDF");
                    finish();
                });
            }
        });
    }

    private int pageWidthPx() {
        int vw = vScroll.getWidth() > 0 ? vScroll.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        return Math.min(2600, Math.max(200, Math.round((vw - dp(12)) * zoom)));
    }

    private void buildPages() {
        pagesCol.removeAllViews();
        pageViews = new ImageView[pageCount];
        pageBmps = new Bitmap[pageCount];
        rendered = new boolean[pageCount];
        int w = pageWidthPx();
        for (int i = 0; i < pageCount; i++) {
            ImageView iv = new ImageView(this);
            iv.setBackgroundColor(0xFFFFFFFF);
            int h = Math.round(w * aspects[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.topMargin = i == 0 ? 0 : dp(10);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            pageViews[i] = iv;
            pagesCol.addView(iv);
        }
    }

    private void updateCurrentPage() {
        if (pageCount == 0) return;
        int mid = vScroll.getScrollY() + vScroll.getHeight() / 3;
        int acc = dp(8);
        for (int i = 0; i < pageCount; i++) {
            int h = pageViews[i].getHeight() + (i == 0 ? 0 : dp(10));
            if (mid < acc + h) {
                if (currentPage != i) {
                    currentPage = i;
                    pageLabel.setText((i + 1) + " / " + pageCount);
                }
                return;
            }
            acc += h;
        }
    }

    /** Renders pages current±3 and recycles anything farther away. */
    private void renderWindow() {
        if (renderer == null || closed || pageCount == 0) return;
        final int from = Math.max(0, currentPage - 3);
        final int to = Math.min(pageCount - 1, currentPage + 3);
        for (int i = 0; i < pageCount; i++) {
            if ((i < from || i > to) && rendered[i]) {
                pageViews[i].setImageBitmap(null);
                if (pageBmps[i] != null) pageBmps[i].recycle();
                pageBmps[i] = null;
                rendered[i] = false;
            }
        }
        final int w = pageWidthPx();
        exec.execute(() -> {
            for (int i = from; i <= to && !closed; i++) {
                if (rendered[i] || renderer == null) continue;
                try {
                    PdfRenderer.Page p = renderer.openPage(i);
                    Bitmap b = PdfEngine.renderPage(p, w);
                    p.close();
                    final int fi = i;
                    final Bitmap fb = b;
                    ui.post(() -> {
                        if (closed || rendered[fi]) {
                            fb.recycle();
                            return;
                        }
                        if (fi < from || fi > to) {
                            fb.recycle();
                            return;
                        }
                        pageBmps[fi] = fb;
                        rendered[fi] = true;
                        pageViews[fi].setImageBitmap(fb);
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    private void cycleZoom() {
        zoom = zoom >= 2.9f ? 1.0f : zoom + 0.5f;
        zoomBtn.setText(Math.round(zoom * 100) + "%");
        int keep = currentPage;
        buildPages();
        currentPage = keep;
        ui.post(() -> {
            if (keep < pageViews.length && pageViews[keep] != null) {
                vScroll.scrollTo(0, pageViews[keep].getTop() - dp(8));
            }
            renderWindow();
        });
    }

    private void jumpDialog() {
        if (pageCount == 0) return;
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("Page 1–" + pageCount);
        et.setTextColor(cText);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Go to page")
                .setView(box)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        int p = Integer.parseInt(et.getText().toString().trim());
                        if (p >= 1 && p <= pageCount) {
                            currentPage = p - 1;
                            pageLabel.setText(p + " / " + pageCount);
                            vScroll.scrollTo(0, pageViews[p - 1].getTop() - dp(8));
                            renderWindow();
                        }
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        ui.removeCallbacks(renderTask);
        for (int i = 0; i < pageBmps.length; i++) {
            if (pageBmps[i] != null) pageBmps[i].recycle();
        }
        exec.shutdownNow();
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        try { if (fd != null) fd.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String last = u.getLastPathSegment();
        return last == null ? "Document" : last;
    }

    private TextView label(String t, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
