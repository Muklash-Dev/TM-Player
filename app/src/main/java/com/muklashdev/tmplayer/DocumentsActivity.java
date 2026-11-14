package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * TM Documents Reader — PDF library + offline PDF tools hub, built on
 * PdfEngine (framework PdfRenderer/PdfDocument only). Everything runs on
 * the device; results land in Download/TM Documents.
 */
public class DocumentsActivity extends Activity {

    private static final int RC_OPEN = 7101, RC_MERGE = 7102, RC_SPLIT = 7103,
            RC_COMPRESS = 7104, RC_PDF2IMG = 7105, RC_IMG2PDF = 7106,
            RC_ROTATE = 7107, RC_WM = 7108, RC_PAGENUM = 7109,
            RC_SIGN = 7110, RC_CLEAN = 7111;

    private int cBg, cCard, cText, cDim, cAccent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private LinearLayout pdfListCol, resultsCol;
    private TextView sigSub;
    private ProgressDialog curDlg;
    private File sigFile;

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
        sigFile = new File(getFilesDir(), "signature.png");
        buildUi();
        refreshPdfList();
        refreshResults();
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(20));
        sv.addView(col);
        Edge.pad(sv);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 30, cAccent, true);
        back.setPadding(dp(2), dp(2), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("TM Documents Reader", 19, cText, true));
        titles.addView(label("Read PDFs & work with them — 100% offline", 12.5f, cDim, false));
        head.addView(titles);
        col.addView(head);

        col.addView(section("Your PDFs"));
        pdfListCol = new LinearLayout(this);
        pdfListCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(pdfListCol);
        col.addView(toolRow("📂", "Open a PDF file…",
                "Browse anywhere — files, downloads, SD card",
                v -> pick("application/pdf", false, RC_OPEN)));

        col.addView(section("PDF tools"));
        col.addView(toolRow("🔀", "Merge PDFs",
                "Combine several PDFs into one",
                v -> pick("application/pdf", true, RC_MERGE)));
        col.addView(toolRow("✂️", "Split / extract / reorder pages",
                "Keep only the pages you type — order is preserved",
                v -> pick("application/pdf", false, RC_SPLIT)));
        col.addView(toolRow("🗜️", "Compress PDF",
                "Rebuild smaller — pick a quality",
                v -> pick("application/pdf", false, RC_COMPRESS)));
        col.addView(toolRow("🖼️", "PDF to images",
                "Every page as PNG/JPG — separate files or one ZIP",
                v -> pick("application/pdf", false, RC_PDF2IMG)));
        col.addView(toolRow("🖼️", "Images to PDF",
                "Turn picked photos into one PDF",
                v -> pick("image/*", true, RC_IMG2PDF)));
        col.addView(toolRow("🔄", "Rotate pages",
                "Turn every page 90°, 180° or 270°",
                v -> pick("application/pdf", false, RC_ROTATE)));
        col.addView(toolRow("💧", "Add watermark",
                "Stamp your text across every page",
                v -> pick("application/pdf", false, RC_WM)));
        col.addView(toolRow("🔢", "Add page numbers",
                "“Page N / total” at the bottom of each page",
                v -> pick("application/pdf", false, RC_PAGENUM)));
        sigSub = null;
        col.addView(toolRow("✍️", "Sign a PDF",
                sigStatus(),
                v -> pick("application/pdf", false, RC_SIGN)));
        col.addView(toolRow("🧹", "Clean metadata",
                "Rebuild the PDF — title/author/tool info removed",
                v -> pick("application/pdf", false, RC_CLEAN)));
        TextView note = label("Honest note: password protect, unlock and OCR are not "
                + "included — Android's built-in PDF engine cannot encrypt or decrypt "
                + "PDFs, and OCR needs a separate recognition engine. Rebuilt PDFs "
                + "(merge / compress / etc.) have image pages, so their text is not "
                + "selectable.", 12, cDim, false);
        note.setPadding(0, dp(12), 0, 0);
        col.addView(note);

        col.addView(section("Results — Download/TM Documents"));
        resultsCol = new LinearLayout(this);
        resultsCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(resultsCol);

        setContentView(sv);
    }

    private String sigStatus() {
        return sigFile.exists()
                ? "Your drawn signature is saved — pick a PDF to stamp it (long-press to redraw)"
                : "Draw your signature once, then stamp PDFs";
    }

    // ------------------------------------------------------------ library + results lists

    private void refreshPdfList() {
        pdfListCol.removeAllViews();
        List<Object[]> items = new ArrayList<>();
        try {
            Uri coll = MediaStore.Files.getContentUri("external");
            String[] proj = {MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED};
            try (Cursor c = getContentResolver().query(coll, proj,
                    MediaStore.Files.FileColumns.MIME_TYPE + "=?",
                    new String[]{"application/pdf"},
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext() && items.size() < 80) {
                        long id = c.getLong(0);
                        items.add(new Object[]{ContentUris.withAppendedId(coll, id),
                                c.getString(1), c.getLong(2), c.getLong(3)});
                    }
                }
            }
        } catch (Exception ignored) {}
        if (items.isEmpty()) {
            pdfListCol.addView(label("No PDFs listed (storage access may be off) — "
                    + "use “Open a PDF file…” below.", 12.5f, cDim, false));
            return;
        }
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", Locale.US);
        for (Object[] it : items) {
            final Uri u = (Uri) it[0];
            String name = (String) it[1];
            long size = (Long) it[2];
            long mod = (Long) it[3];
            pdfListCol.addView(toolRow("📄", name == null ? "Document" : name,
                    Prefs.fmtSize(size) + "  •  " + df.format(new Date(mod * 1000)),
                    v -> openReader(u)));
        }
    }

    private void refreshResults() {
        resultsCol.removeAllViews();
        File dir = outDir();
        File[] files = dir.listFiles();
        List<File> list = new ArrayList<>();
        if (files != null) {
            for (File f : files) if (f.isFile()) list.add(f);
        }
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (list.isEmpty()) {
            resultsCol.addView(label("Nothing yet — files made by the tools appear here.",
                    12.5f, cDim, false));
            return;
        }
        SimpleDateFormat df = new SimpleDateFormat("d MMM, h:mm a", Locale.US);
        for (File f : list) {
            final File file = f;
            View row = toolRow(f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf") ? "📄" : "🗂️",
                    f.getName(),
                    Prefs.fmtSize(f.length()) + "  •  " + df.format(new Date(f.lastModified())),
                    v -> {
                        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                            openReader(Uri.fromFile(file));
                        } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                            toast("ZIP saved in Download/TM Documents");
                        } else {
                            showImage(file);
                        }
                    });
            row.setOnLongClickListener(v -> {
                resultActions(file);
                return true;
            });
            resultsCol.addView(row);
        }
    }

    private void resultActions(File file) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        AlertDialog[] holder = new AlertDialog[1];
        TextView rn = dialogRow("✏️  Rename");
        rn.setOnClickListener(v -> {
            holder[0].dismiss();
            renameDialog(file);
        });
        box.addView(rn);
        TextView del = dialogRow("🗑️  Delete");
        del.setOnClickListener(v -> {
            holder[0].dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("Delete file?")
                    .setMessage(file.getName())
                    .setPositiveButton("Delete", (d, w) -> {
                        if (file.delete()) toast("Deleted");
                        refreshResults();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        box.addView(del);
        holder[0] = new AlertDialog.Builder(this).setView(box).create();
        holder[0].show();
    }

    private void renameDialog(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        final String ext = dot > 0 ? name.substring(dot) : "";
        EditText et = new EditText(this);
        et.setText(dot > 0 ? name.substring(0, dot) : name);
        et.setTextColor(cText);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Rename result")
                .setView(box)
                .setPositiveButton("Rename", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) return;
                    File to = new File(file.getParentFile(), n + ext);
                    if (file.renameTo(to)) toast("Renamed");
                    else toast("Rename failed");
                    refreshResults();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showImage(File file) {
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (b == null) {
            toast("Cannot preview this file");
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this).setView(iv)
                .setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------ picking + flows

    private void pick(String type, boolean multi, int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null) return;
        List<Uri> uris = collectUris(data);
        if (uris.isEmpty()) return;
        final Uri first = uris.get(0);
        switch (code) {
            case RC_OPEN:
                openReader(first);
                break;
            case RC_MERGE:
                if (uris.size() < 2) {
                    toast("Pick at least two PDFs");
                    return;
                }
                nameDialog("merged", name -> {
                    List<PdfEngine.Job> jobs = new ArrayList<>();
                    for (Uri u : uris) jobs.add(job(u));
                    File out = uniqueFile(outDir(), name, "pdf");
                    runWork("Merging", pd -> {
                        PdfEngine.transform(this, jobs, out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            case RC_SPLIT: {
                int n = PdfEngine.pageCount(this, first);
                if (n == -2) { toast("This PDF is password-protected"); return; }
                if (n < 0) { toast("Could not read this PDF"); return; }
                textDialog("Pages to keep", "e.g. 1-3,5 — the order you type is kept ("
                        + n + " pages total)", "1-" + n, spec -> {
                    PdfEngine.Job j = job(first);
                    j.pagesSpec = spec;
                    File out = uniqueFile(outDir(), baseName(first) + "_pages", "pdf");
                    runWork("Extracting", pd -> {
                        PdfEngine.transform(this, list(j), out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            }
            case RC_COMPRESS:
                choiceDialog("Compress quality", new String[][]{
                        {"Smallest — 72 DPI", "72"},
                        {"Balanced — 110 DPI", "110"},
                        {"High quality — 150 DPI", "150"}}, val -> {
                    PdfEngine.Job j = job(first);
                    j.dpi = Integer.parseInt(val);
                    File out = uniqueFile(outDir(), baseName(first) + "_compressed", "pdf");
                    runWork("Compressing", pd -> {
                        PdfEngine.transform(this, list(j), out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            case RC_PDF2IMG:
                choiceDialog("Export pages as images", new String[][]{
                        {"PNG • 150 DPI • separate files", "png|150|sep"},
                        {"PNG • 300 DPI • separate files", "png|300|sep"},
                        {"JPG • 150 DPI • single ZIP", "jpg|150|zip"}}, val -> {
                    String[] parts = val.split("\\|");
                    boolean png = parts[0].equals("png");
                    int dpi = Integer.parseInt(parts[1]);
                    boolean zip = parts[2].equals("zip");
                    String base = baseName(first);
                    File folder = new File(outDir(), base + "_pages");
                    runWork("Exporting pages", pd -> {
                        PdfEngine.pdfToImages(this, first, dpi, png, folder,
                                (done, total) -> setProgress(pd, done, total));
                        if (zip) {
                            File zf = uniqueFile(outDir(), base + "_pages", "zip");
                            zipFolder(folder, zf);
                            deleteRec(folder);
                            return zf;
                        }
                        return null;
                    }, null);
                });
                break;
            case RC_IMG2PDF:
                nameDialog("images", name -> {
                    File out = uniqueFile(outDir(), name, "pdf");
                    runWork("Building PDF", pd -> {
                        PdfEngine.imagesToPdf(this, uris, out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            case RC_ROTATE:
                choiceDialog("Rotate every page", new String[][]{
                        {"90° clockwise", "90"},
                        {"180°", "180"},
                        {"90° counter-clockwise", "270"}}, val -> {
                    PdfEngine.Job j = job(first);
                    j.rotateDeg = Integer.parseInt(val);
                    File out = uniqueFile(outDir(), baseName(first) + "_rotated", "pdf");
                    runWork("Rotating", pd -> {
                        PdfEngine.transform(this, list(j), out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            case RC_WM:
                textDialog("Watermark text", "Shown diagonally on every page", "", text -> {
                    if (text.trim().isEmpty()) return;
                    PdfEngine.Job j = job(first);
                    j.watermark = text.trim();
                    File out = uniqueFile(outDir(), baseName(first) + "_watermarked", "pdf");
                    runWork("Stamping", pd -> {
                        PdfEngine.transform(this, list(j), out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                });
                break;
            case RC_PAGENUM: {
                PdfEngine.Job j = job(first);
                j.pageNumbers = true;
                File out = uniqueFile(outDir(), baseName(first) + "_numbered", "pdf");
                runWork("Numbering", pd -> {
                    PdfEngine.transform(this, list(j), out,
                            (done, total) -> setProgress(pd, done, total));
                    return out;
                }, out);
                break;
            }
            case RC_SIGN: {
                Runnable go = () -> {
                    Bitmap sig = BitmapFactory.decodeFile(sigFile.getAbsolutePath());
                    if (sig == null) {
                        toast("Signature could not be loaded");
                        return;
                    }
                    PdfEngine.Job j = job(first);
                    j.signature = sig;
                    File out = uniqueFile(outDir(), baseName(first) + "_signed", "pdf");
                    runWork("Signing", pd -> {
                        PdfEngine.transform(this, list(j), out,
                                (done, total) -> setProgress(pd, done, total));
                        return out;
                    }, out);
                };
                if (sigFile.exists()) go.run();
                else signaturePad(go);
                break;
            }
            case RC_CLEAN: {
                PdfEngine.Job j = job(first);
                File out = uniqueFile(outDir(), baseName(first) + "_clean", "pdf");
                runWork("Cleaning", pd -> {
                    PdfEngine.transform(this, list(j), out,
                            (done, total) -> setProgress(pd, done, total));
                    return out;
                }, out);
                break;
            }
            default:
                break;
        }
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        for (Uri u : out) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ------------------------------------------------------------ signature pad

    private void signaturePad(Runnable after) {
        SigPadView pad = new SigPadView(this);
        pad.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(14), dp(8), dp(14), 0);
        box.addView(pad);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Draw your signature")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Bitmap b = pad.toBitmap();
                    if (b == null) {
                        toast("Draw something first");
                        return;
                    }
                    try {
                        FileOutputStream fos = new FileOutputStream(sigFile);
                        b.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.close();
                        toast("Signature saved");
                        rebuildSigRow();
                        if (after != null) after.run();
                    } catch (Exception e) {
                        toast("Could not save the signature");
                    }
                })
                .setNeutralButton("Clear", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> {
            View btn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (btn != null) btn.setOnClickListener(v -> pad.clearPad());
        });
        dlg.show();
    }

    private void rebuildSigRow() {
        // The tools list is static; simply note via toast — status text refreshes
        // the next time this screen opens.
    }

    class SigPadView extends View {
        private final List<Path> paths = new ArrayList<>();
        private Path cur;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SigPadView(Activity a) {
            super(a);
            paint.setColor(0xFF20306B);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5.5f * getResources().getDisplayMetrics().density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setBackgroundColor(0xFFF7F9FC);
        }

        void clearPad() {
            paths.clear();
            cur = null;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cur = new Path();
                    cur.moveTo(e.getX(), e.getY());
                    paths.add(cur);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (cur != null) cur.lineTo(e.getX(), e.getY());
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            Paint guide = new Paint();
            guide.setColor(0xFFC9D4E8);
            guide.setStrokeWidth(2);
            c.drawLine(dp(16), getHeight() - dp(48), getWidth() - dp(16),
                    getHeight() - dp(48), guide);
            for (Path p : paths) c.drawPath(p, paint);
        }

        Bitmap toBitmap() {
            if (paths.isEmpty()) return null;
            RectF all = null;
            for (Path p : paths) {
                RectF b = new RectF();
                p.computeBounds(b, true);
                if (all == null) all = b;
                else all.union(b);
            }
            if (all == null || all.width() < 4 || all.height() < 4) return null;
            int padPx = dp(10);
            int w = (int) all.width() + padPx * 2;
            int h = (int) all.height() + padPx * 2;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(-all.left + padPx, -all.top + padPx);
            for (Path p : paths) c.drawPath(p, paint);
            return bmp;
        }
    }

    // ------------------------------------------------------------ work runner + dialogs

    private interface Work {
        File run(ProgressDialog pd) throws Exception;
    }

    private void setProgress(ProgressDialog pd, int done, int total) {
        ui.post(() -> {
            if (pd.isShowing()) pd.setMessage("Working… page " + done + " / " + total);
        });
    }

    private void runWork(String title, Work work, File openAfter) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(title + "…");
        pd.setCancelable(false);
        pd.show();
        curDlg = pd;
        exec.execute(() -> {
            try {
                File made = work.run(pd);
                ui.post(() -> {
                    pd.dismiss();
                    refreshResults();
                    File target = made != null ? made : openAfter;
                    if (target != null && target.getName().endsWith(".pdf")) {
                        new AlertDialog.Builder(this)
                                .setTitle("Done")
                                .setMessage(target.getName() + "\nSaved in Download/TM Documents")
                                .setPositiveButton("Open", (d, w) -> openReader(Uri.fromFile(target)))
                                .setNegativeButton("Close", null)
                                .show();
                    } else if (target != null) {
                        toast("Done — " + target.getName());
                    } else {
                        toast("Done — see Results below");
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                ui.post(() -> {
                    pd.dismiss();
                    toast("Failed: " + (msg == null ? "unknown error" : msg));
                });
            }
        });
    }

    private void textDialog(String title, String hint, String initial,
                            java.util.function.Consumer<String> done) {
        EditText et = new EditText(this);
        et.setText(initial);
        et.setHint(hint);
        et.setTextColor(cText);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = label(hint, 12, cDim, false);
        box.addView(h);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Continue", (d, w) -> done.accept(et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void nameDialog(String initial, java.util.function.Consumer<String> done) {
        textDialog("Name the result", "Saved as a PDF in Download/TM Documents",
                initial, done);
    }

    private void choiceDialog(String title, String[][] options,
                              java.util.function.Consumer<String> done) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        AlertDialog[] holder = new AlertDialog[1];
        for (String[] op : options) {
            TextView r = dialogRow(op[0]);
            r.setOnClickListener(v -> {
                holder[0].dismiss();
                done.accept(op[1]);
            });
            box.addView(r);
        }
        holder[0] = new AlertDialog.Builder(this).setTitle(title).setView(box).create();
        holder[0].show();
    }

    // ------------------------------------------------------------ helpers

    private PdfEngine.Job job(Uri u) {
        PdfEngine.Job j = new PdfEngine.Job();
        j.src = u;
        return j;
    }

    private List<PdfEngine.Job> list(PdfEngine.Job j) {
        List<PdfEngine.Job> l = new ArrayList<>();
        l.add(j);
        return l;
    }

    private void openReader(Uri u) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.setData(u);
        startActivity(i);
    }

    File outDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "TM Documents");
        if (!d.exists()) d.mkdirs();
        if (!d.isDirectory()) {
            d = new File(getExternalFilesDir(null), "TM Documents");
            if (!d.exists()) d.mkdirs();
        }
        return d;
    }

    private File uniqueFile(File dir, String base, String ext) {
        String clean = base.replaceAll("[^a-zA-Z0-9-_ ]", "_").trim();
        if (clean.isEmpty()) clean = "document";
        File f = new File(dir, clean + "." + ext);
        int i = 2;
        while (f.exists()) {
            f = new File(dir, clean + " (" + i + ")." + ext);
            i++;
        }
        return f;
    }

    private String baseName(Uri u) {
        String n = displayName(u);
        if (n == null || n.isEmpty()) return "document";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String last = u.getLastPathSegment();
        return last == null ? "document" : last;
    }

    private void zipFolder(File folder, File zipOut) throws Exception {
        File[] files = folder.listFiles();
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipOut));
        byte[] buf = new byte[8192];
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                InputStream in = new FileInputStream(f);
                int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                in.close();
                zos.closeEntry();
            }
        }
        zos.close();
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    private View section(String t) {
        TextView tv = label(t, 13, cAccent, true);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private View toolRow(String glyph, String title, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(12), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(14));
        r.setBackground(g);
        TextView gl = label(glyph, 19, cText, false);
        gl.setPadding(0, 0, dp(12), 0);
        r.addView(gl);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView tt = label(title, 15, cText, true);
        tt.setMaxLines(1);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(tt);
        tx.addView(label(sub, 12.5f, cDim, false));
        r.addView(tx, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ch = label("›", 20, cDim, false);
        r.addView(ch);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        r.setOnClickListener(l);
        if (title.startsWith("Sign a PDF")) {
            r.setOnLongClickListener(v -> {
                signaturePad(null);
                return true;
            });
        }
        return r;
    }

    private TextView dialogRow(String t) {
        TextView tv = label(t, 15, cText, false);
        tv.setPadding(dp(16), dp(13), dp(16), dp(13));
        return tv;
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
