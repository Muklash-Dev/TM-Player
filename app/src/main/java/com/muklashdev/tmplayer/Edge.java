package com.muklashdev.tmplayer;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Edge-to-edge support. Apps targeting Android 15+ (API 35) are drawn behind
 * the system bars, so roots that used to sit below the status bar need the
 * system-bar insets applied as padding. On older Android versions the system
 * already fits the content, so this is a no-op there.
 */
final class Edge {

    private Edge() {}

    /** Pad all four sides with the system-bar insets (API 35+ devices only). */
    static void pad(final View v) {
        pad(v, true, true);
    }

    static void padTop(final View v) {
        pad(v, true, false);
    }

    static void padBottom(final View v) {
        pad(v, false, true);
    }

    private static void pad(final View v, final boolean top, final boolean bottom) {
        if (v == null || Build.VERSION.SDK_INT < 35) return;
        final int pl = v.getPaddingLeft();
        final int pt = v.getPaddingTop();
        final int pr = v.getPaddingRight();
        final int pb = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(
                    pl + bars.left,
                    pt + (top ? bars.top : 0),
                    pr + bars.right,
                    pb + (bottom ? bars.bottom : 0));
            return insets;
        });
        v.requestApplyInsets();
    }
}
