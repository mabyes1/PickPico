package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.TextView;
import java.util.Locale;

/** Shared tab typography and line icons; both screen hosts use the same recipe. */
final class PulseNavigation {
    static String kind(Context context, String label) {
        if (label.equals(context.getString(R.string.ui_home))) return "home";
        if (label.equals(context.getString(R.string.ui_activity))) return "activity";
        if (label.equals(context.getString(R.string.ui_capabilities))) return "capabilities";
        if (label.equals(context.getString(R.string.ui_settings))) return "settings";
        return label.toLowerCase(Locale.ROOT);
    }

    static TextView item(Context context, PickPicoTheme.State theme, String label, boolean active, Runnable action) {
        String kind = kind(context, label);
        TextView view = new TextView(context);
        view.setText(label.substring(0, 1) + label.substring(1).toLowerCase(Locale.ROOT));
        view.setTextSize(11);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(.02f);
        view.setLineSpacing(0f, 1.18f);
        view.setTag(R.id.theme_hint_role, kind);
        int color = active ? PickPicoTheme.text(theme) : PickPicoTheme.dim(theme);
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
