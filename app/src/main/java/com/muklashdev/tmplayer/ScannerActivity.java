package com.muklashdev.tmplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Vibrator;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-app QR code scanner (Me > QR Scanner). Framework Camera2 preview +
 * ZXing core decoding (Apache License 2.0) from YUV frames — no Play
 * Services, no ML Kit. Results can be opened (web links open inside TM
 * Browser), copied or rescanned.
 */
public class ScannerActivity extends Activity {

    private static final int REQ_CAM = 994;

    private int cBg, cCard, cCard2, cText, cDim, cAccent;
    private Prefs prefs;

    private TextureView preview;
    private View scanWrap, resultWrap;
    private TextView resultText, torchBtn;
    private TextView openBtn;
    private String lastResult = "";

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder reqBuilder;
    private ImageReader reader;
    private String cameraId;
    private Size previewSize;
    private boolean hasFlash = false;
    private boolean torch = false;
    private volatile boolean found = false;
    private MultiFormatReader qrReader;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        boolean dark = prefs.isDark(this);
        cBg = dark ? 0xFF121826 : 0xFFF4F6FA;
        cCard = dark ? 0xFF1A2336 : 0xFFFFFFFF;
        cCard2 = dark ? 0xFF2A3650 : 0xFFE3E9F2;
        cText = dark ? 0xFFEAF0F8 : 0xFF14202E;
        cDim = dark ? 0xFF93A3BA : 0xFF5A6B80;
        cAccent = prefs.accent();

        qrReader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Arrays.asList(com.google.zxing.BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        qrReader.setHints(hints);

        buildUi();
        camThread = new HandlerThread("tm_cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!found && preview != null && preview.isAvailable()
                && checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (camThread != null) camThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (preview != null && preview.isAvailable()) openCamera();
            } else {
                toast("Camera permission is needed to scan");
                finish();
            }
        }
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));
        Edge.pad(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 30, cAccent, true);
        back.setPadding(dp(2), dp(2), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("QR Scanner", 19, cText, true));
        titles.addView(label("Point the camera at a QR code", 12.5f, cDim, false));
        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        torchBtn = label("🔦", 19, cText, false);
        torchBtn.setPadding(dp(10), dp(6), dp(4), dp(6));
        torchBtn.setOnClickListener(v -> toggleTorch());
        head.addView(torchBtn);
        root.addView(head);

        // scanning view: preview + corner frame + hint
        FrameLayout frame = new FrameLayout(this);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(texListener);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View box = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setStroke(dp(3), cAccent);
        bd.setCornerRadius(dp(18));
        bd.setColor(0x00000000);
        box.setBackground(bd);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(240), dp(240));
        bl.gravity = Gravity.CENTER;
        frame.addView(box, bl);
        TextView credit = label("QR decoding by ZXing (Apache 2.0)", 11, cDim, false);
        credit.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.BOTTOM;
        cl.bottomMargin = dp(12);
        frame.addView(credit, cl);
        scanWrap = frame;
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // result view
        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        res.setPadding(0, dp(14), 0, 0);
        res.addView(label("Scanned", 15, cAccent, true));
        ScrollView rsv = new ScrollView(this);
        resultText = label("", 15.5f, cText, false);
        resultText.setTextIsSelectable(true);
        resultText.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable rc = new GradientDrawable();
        rc.setColor(cCard);
        rc.setCornerRadius(dp(14));
        resultText.setBackground(rc);
        rsv.addView(resultText);
        res.addView(rsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        openBtn = bigButton("Open");
        openBtn.setOnClickListener(v -> openResult());
        res.addView(openBtn);
        TextView copyBtn = bigButton("Copy");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("qr", lastResult));
                toast("Copied");
            }
        });
        res.addView(copyBtn);
        TextView againBtn = bigButton("Scan again");
        againBtn.setOnClickListener(v -> {
            found = false;
            resultWrap.setVisibility(View.GONE);
            scanWrap.setVisibility(View.VISIBLE);
            if (preview.isAvailable()) openCamera();
        });
        res.addView(againBtn);
        resultWrap = res;
        res.setVisibility(View.GONE);
        root.addView(res, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void openResult() {
        String t = lastResult.trim();
        try {
            if (t.startsWith("http://") || t.startsWith("https://")) {
                Intent bi = new Intent(this, BrowserActivity.class);
                bi.putExtra("open_url", t);
                startActivity(bi);
            } else if (t.startsWith("tel:") || t.startsWith("mailto:")
                    || t.startsWith("sms:") || t.startsWith("smsto:")
                    || t.startsWith("geo:")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(t)));
            }
        } catch (Exception e) {
            toast("No app can open this");
        }
    }

    private void showResult(String text) {
        lastResult = text;
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(60);
        } catch (Exception ignored) {}
        closeCamera();
        String display = text;
        if (text.startsWith("WIFI:")) display = prettyWifi(text);
        resultText.setText(display);
        boolean openable = text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("tel:") || text.startsWith("mailto:")
                || text.startsWith("sms:") || text.startsWith("smsto:")
                || text.startsWith("geo:");
        openBtn.setVisibility(openable ? View.VISIBLE : View.GONE);
        if (text.startsWith("http://") || text.startsWith("https://")) {
            openBtn.setText("Open in TM Browser");
        } else {
            openBtn.setText("Open");
        }
        scanWrap.setVisibility(View.GONE);
        resultWrap.setVisibility(View.VISIBLE);
    }

    private String prettyWifi(String t) {
        String ssid = wifiField(t, "S:"), pass = wifiField(t, "P:"), type = wifiField(t, "T:");
        StringBuilder sb = new StringBuilder("Wi-Fi network");
        if (!ssid.isEmpty()) sb.append("\nName: ").append(ssid);
        if (!type.isEmpty()) sb.append("\nSecurity: ").append(type);
        if (!pass.isEmpty()) sb.append("\nPassword: ").append(pass);
        sb.append("\n\n(Copy keeps the full code if you need it elsewhere.)");
        return sb.toString();
    }

    private String wifiField(String t, String key) {
        int i = t.indexOf(key);
        if (i < 0) return "";
        int j = t.indexOf(';', i + key.length());
        if (j < 0) j = t.length();
        return t.substring(i + key.length(), j).replace("\\;", ";").replace("\\:", ":");
    }

    // ------------------------------------------------------------ camera

    private final TextureView.SurfaceTextureListener texListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (!found && checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        openCamera();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    configureTransform(w, h);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            };

    private void openCamera() {
        if (camera != null) return;
        try {
            CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (mgr == null) return;
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    hasFlash = flash != null && flash;
                    StreamConfigurationMap map = cc.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class));
                    }
                    break;
                }
            }
            if (cameraId == null) {
                toast("No back camera found");
                return;
            }
            if (previewSize == null) previewSize = new Size(1280, 720);
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configureTransform(preview.getWidth(), preview.getHeight());

            reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, camHandler);

            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    camera = cd;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    camera = null;
                }
            }, camHandler);
        } catch (Exception e) {
            toast("Camera could not be opened");
        }
    }

    private Size chooseSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            if (best == null || s.getWidth() * (long) s.getHeight()
                    > best.getWidth() * (long) best.getHeight()) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void createSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camera == null) return;
            Surface ps = new Surface(st);
            List<Surface> outs = new ArrayList<>();
            outs.add(ps);
            outs.add(reader.getSurface());
            reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            reqBuilder.addTarget(ps);
            reqBuilder.addTarget(reader.getSurface());
            reqBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cs) {
                    session = cs;
                    try {
                        cs.setRepeatingRequest(reqBuilder.build(), null, camHandler);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cs) {
                    toast("Camera preview failed");
                }
            }, camHandler);
        } catch (Exception ignored) {}
    }

    private void toggleTorch() {
        if (!hasFlash) {
            toast("No flash on this camera");
            return;
        }
        if (session == null || reqBuilder == null) return;
        torch = !torch;
        try {
            reqBuilder.set(CaptureRequest.FLASH_MODE,
                    torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(reqBuilder.build(), null, camHandler);
            torchBtn.setText(torch ? "🔦 On" : "🔦");
        } catch (Exception ignored) {}
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        reader = null;
    }

    private void configureTransform(int vw, int vh) {
        if (previewSize == null || vw == 0 || vh == 0) return;
        Matrix m = new Matrix();
        RectF viewRect = new RectF(0, 0, vw, vh);
        RectF bufRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        bufRect.offset(viewRect.centerX() - bufRect.centerX(),
                viewRect.centerY() - bufRect.centerY());
        m.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) vh / previewSize.getHeight(),
                (float) vw / previewSize.getWidth());
        m.postScale(scale, scale, viewRect.centerX(), viewRect.centerY());
        preview.setTransform(m);
    }

    // ------------------------------------------------------------ decoding

    private void onFrame(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        if (found) {
            img.close();
            return;
        }
        try {
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int rowStride = y.getRowStride();
            byte[] data = new byte[w * h];
            if (rowStride == w) {
                buf.get(data, 0, Math.min(data.length, buf.remaining()));
            } else {
                for (int row = 0; row < h; row++) {
                    buf.position(row * rowStride);
                    buf.get(data, row * w, Math.min(w, buf.remaining()));
                }
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            Result res = qrReader.decodeWithState(bmp);
            if (res != null && res.getText() != null && !res.getText().isEmpty()) {
                found = true;
                final String text = res.getText();
                runOnUiThread(() -> showResult(text));
            }
        } catch (NotFoundException ignored) {
            // no code in this frame — keep scanning
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    // ------------------------------------------------------------ helpers

    private TextView label(String t, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private TextView bigButton(String t) {
        TextView b = label(t, 15, cText, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
