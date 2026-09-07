package com.mcpocket.poc;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Small framework-only helper for Android edge-to-edge system bar safety. */
final class SystemBarInsets {
    private SystemBarInsets() { }

    static void apply(View view, boolean includeTop, boolean includeBottom) {
        if (view == null) return;
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusTop;
            int navigationBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
                navigationBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                statusTop = insets.getSystemWindowInsetTop();
                navigationBottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(
                    left,
                    top + (includeTop ? statusTop : 0),
                    right,
                    bottom + (includeBottom ? navigationBottom : 0));
            return insets;
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.requestApplyInsets(); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
    }
}
