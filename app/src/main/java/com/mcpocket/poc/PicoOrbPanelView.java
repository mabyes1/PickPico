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
    private final android.widget.ImageButton returnAction;
    private final TextView close;
    private String iconPackage;
    private String surfaceKey;
    private Runnable dismissAction;
    private java.util.function.Consumer<MotionEvent> outsideAction;
    private final TextView settings;

    PicoOrbPanelView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(8), dp(12), dp(8));
        setElevation(dp(12));

        status = text("", 10, Typeface.BOLD, Color.rgb(190, 200, 208));
        status.setLetterSpacing(.08f);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(status, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        close = text("×", 20, Typeface.NORMAL, Color.WHITE);
        close.setContentDescription("Close Pico panel");
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(view -> { if (dismissAction != null) dismissAction.run(); });
        header.addView(close, new LayoutParams(dp(48), dp(48)));
        addView(header);
        title = text("", 16, Typeface.BOLD, Color.WHITE);
        title.setPadding(0, 0, 0, dp(8));
        title.setMaxLines(2);
        addView(title);
        detail = text("", 11, Typeface.NORMAL, Color.rgb(193, 202, 210));
        detail.setPadding(0, dp(5), 0, dp(12));
        detail.setMaxLines(2);
        addView(detail);
        detail.setVisibility(GONE);
        action = text("OPEN  →", 11, Typeface.BOLD, Color.WHITE);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(48));
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(action, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        returnAction = new android.widget.ImageButton(context);
        returnAction.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LayoutParams returnParams = new LayoutParams(dp(48), dp(48));
        returnParams.leftMargin = dp(16);
        actions.addView(returnAction, returnParams);
        addView(actions);
        settings = text("Return app & text size", 11, Typeface.NORMAL, Color.LTGRAY);
        settings.setGravity(Gravity.CENTER);
        addView(settings, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        settings.setOnClickListener(view -> getContext().startActivity(
                new android.content.Intent(getContext(), CallerReturnSettingsActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    void bind(HomePulse.Snapshot snapshot, int themeColor, Runnable primaryAction, CallerReturnTarget caller, Runnable returnToCaller) {
        int accent = PicoOrbState.primary(snapshot.orbMode, themeColor);
        PickPicoTheme.State theme = PickPicoTheme.load(getContext());
        String nextSurfaceKey = theme.gradient + ":" + theme.colorA + ":" + theme.colorB + ":"
                + theme.backgroundIntensity + ":" + theme.glassOpacity + ":" + theme.highlight;
        if (!nextSurfaceKey.equals(surfaceKey)) {
            surfaceKey = nextSurfaceKey;
            setBackground(new PicoPanelSurface(getContext(), theme, dp(12)));
        }
        close.setTextColor(PickPicoTheme.text(theme));
        title.setTextColor(PickPicoTheme.text(theme));
        settings.setTextColor(PickPicoTheme.muted(theme));
        status.setText(String.format(
                Locale.ROOT,
                "●  %s",
                PicoOrbState.label(snapshot.orbMode)));
        status.setTextColor(accent);
        title.setText(snapshot.activeTasks == 0 || TextUtils.isEmpty(snapshot.title) ? PicoOrbState.label(snapshot.orbMode) : snapshot.title);
        detail.setText(detail(snapshot));
        action.setText(actionLabel(snapshot.orbMode));
        action.setBackground(PickPicoTheme.control(theme, dp(11), accent, false));
        action.setTextColor(PickPicoTheme.text(theme));
        action.setOnClickListener(view -> primaryAction.run());
        returnAction.setBackground(PickPicoTheme.control(theme, dp(11), accent, false));
        float scale = PicoTextSettings.scale(getContext());
        status.setTextSize(10 * scale); title.setTextSize(14 * scale); detail.setTextSize(11 * scale);
        action.setTextSize(11 * scale); settings.setTextSize(11 * scale);
        action.setPadding(dp(5), dp(8), dp(5), dp(8));
        returnAction.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (!caller.packageName.equals(iconPackage)) {
            iconPackage = caller.packageName;
            try {
                returnAction.setImageDrawable(getContext().getPackageManager().getApplicationIcon(caller.packageName));
            } catch (android.content.pm.PackageManager.NameNotFoundException | IllegalArgumentException error) {
                returnAction.setImageResource(android.R.drawable.ic_menu_revert);
            }
        }
        String returnLabel = caller.available() ? "Return to " + caller.name : "No return app selected";
        returnAction.setContentDescription(returnLabel);
        returnAction.setTooltipText(returnLabel);
        returnAction.setEnabled(caller.available());
        returnAction.setAlpha(caller.available() ? 1f : .5f);
        returnAction.setOnClickListener(view -> returnToCaller.run());
    }

    void setDismissAction(Runnable dismissAction) {
        this.dismissAction = dismissAction;
    }

    void setOutsideAction(java.util.function.Consumer<MotionEvent> action) { outsideAction = action; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (outsideAction != null) outsideAction.accept(event);
            return true;
        }
        return super.dispatchTouchEvent(event);
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

    private String detail(HomePulse.Snapshot snapshot) { return "View activity or return to your agent"; }

    private String actionLabel(String mode) {
        if (PicoOrbState.BLOCKED.equals(mode)) return "Review issue";
        if (PicoOrbState.HUMAN_HELP.equals(mode)) return "Open help";
        if (PicoOrbState.CONNECTING.equals(mode) || PicoOrbState.CONNECTION_ATTENTION.equals(mode)) return "View connection";
        if (PicoOrbState.RUNNING.equals(mode) || PicoOrbState.COMPLETED.equals(mode)) return "Open Activity";
        return "Open PickPico";
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
