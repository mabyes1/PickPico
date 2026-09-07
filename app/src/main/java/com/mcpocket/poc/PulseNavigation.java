package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.TextView;
import java.util.Locale;

/** Shared tab typography and line icons; both screen hosts use the same recipe. */
final class PulseNavigation {
    static TextView item(Context context, PickPicoTheme.State theme, String label, boolean active, Runnable action) {
        String kind = label.toLowerCase(Locale.ROOT);
        TextView view = new TextView(context);
        view.setText(label.substring(0, 1) + kind.substring(1));
        view.setTextSize(8);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(.055f);
        view.setLineSpacing(0f, 1.18f);
        view.setTag(R.id.theme_hint_role, kind);
        int color = active ? PickPicoTheme.accentA(theme) : PickPicoTheme.dim(theme);
        view.setTextColor(color);
        float density = context.getResources().getDisplayMetrics().density;
        PulseGlyph glyph = new PulseGlyph(kind, color);
        int size = Math.round(20 * density);
        glyph.setBounds(0, 0, size, size);
        view.setCompoundDrawables(null, glyph, null, null);
        view.setCompoundDrawablePadding(Math.round(5 * density));
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
