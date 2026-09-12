package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** The expanded Pico ball card. Window placement and activity routing stay in the controller. */
final class PicoOrbPanelView extends LinearLayout {
    private final TextView status;
    private final TextView title;
    private final TextView detail;
    private final TextView action;
    private final TextView returnAction;
    private Runnable dismissAction;

    PicoOrbPanelView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(15), dp(18), dp(15));
        setElevation(dp(12));

        status = text("", 10, Typeface.BOLD, Color.rgb(190, 200, 208));
        status.setLetterSpacing(.08f);
        addView(status);
        title = text("", 16, Typeface.BOLD, Color.WHITE);
        title.setPadding(0, dp(7), 0, 0);
        title.setMaxLines(2);
        addView(title);
        detail = text("", 11, Typeface.NORMAL, Color.rgb(193, 202, 210));
        detail.setPadding(0, dp(5), 0, dp(12));
        detail.setMaxLines(2);
        addView(detail);
        action = text("OPEN  →", 11, Typeface.BOLD, Color.WHITE);
        action.setGravity(Gravity.CENTER);
        addView(action, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        returnAction = text("", 11, Typeface.BOLD, Color.WHITE);
        returnAction.setGravity(Gravity.CENTER);
        returnAction.setPadding(0, dp(8), 0, 0);
        addView(returnAction, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        // Keep caller-return plumbing available for future hosts that can provide a
        // trustworthy destination. Most AI hosts cannot identify their own Android
        // app or exact return screen, and the orb already overlays the current app,
        // so exposing this row today would promise precision we cannot guarantee.
        returnAction.setVisibility(GONE);
    }

    void bind(HomePulse.Snapshot snapshot, int themeColor, Runnable primaryAction, Runnable returnToCaller) {
        int accent = PicoOrbState.primary(snapshot.orbMode, themeColor);
        GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(238, 26, 30, 35), Color.argb(232, 13, 16, 20)});
        panel.setCornerRadius(dp(18));
        panel.setStroke(dp(1), Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)));
        setBackground(panel);
        status.setText(String.format(
                Locale.ROOT,
                "●  %s",
                PicoOrbState.label(snapshot.orbMode).toUpperCase(Locale.ROOT)));
        status.setTextColor(accent);
        title.setText(TextUtils.isEmpty(snapshot.title) ? PicoOrbState.label(snapshot.orbMode) : snapshot.title);
        detail.setText(detail(snapshot));
        action.setText(actionLabel(snapshot.orbMode));
        GradientDrawable button = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accent, PicoOrbState.secondary(snapshot.orbMode, accent)});
        button.setCornerRadius(dp(11));
        action.setBackground(button);
        action.setTextColor(Color.luminance(accent) > .5f ? Color.rgb(23, 27, 30) : Color.WHITE);
        action.setOnClickListener(view -> primaryAction.run());
        returnAction.setText(snapshot.caller.label());
        returnAction.setEnabled(snapshot.caller.available());
        returnAction.setAlpha(snapshot.caller.available() ? 1f : .5f);
        returnAction.setOnClickListener(view -> returnToCaller.run());
    }

    void setDismissAction(Runnable dismissAction) {
        this.dismissAction = dismissAction;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (dismissAction != null) dismissAction.run();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) return performClick();
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private String detail(HomePulse.Snapshot snapshot) {
        if (!TextUtils.isEmpty(snapshot.actionDetail)) return snapshot.actionDetail;
        if (!TextUtils.isEmpty(snapshot.agentState)) return snapshot.agentState;
        if (!TextUtils.isEmpty(snapshot.connection)) return snapshot.connection;
        return "Open PickPico for details";
    }

    private String actionLabel(String mode) {
        if (PicoOrbState.COMPLETED.equals(mode)) return "VIEW ACTIVITY  →";
        if (PicoOrbState.BLOCKED.equals(mode)) return "REVIEW ISSUE  →";
        if (PicoOrbState.CONNECTING.equals(mode) || PicoOrbState.CONNECTION_ATTENTION.equals(mode)) return "VIEW CONNECTION  →";
        if (PicoOrbState.RUNNING.equals(mode)) return "OPEN ACTIVITY  →";
        return "OPEN PICKPICO  →";
    }

    private TextView text(String value, float sp, int style, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
