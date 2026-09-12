package com.mcpocket.poc;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Owns the Pico ball overlay windows. The Node service only starts, refreshes, and destroys it. */
final class PicoOrbOverlayController {
    interface StateSource {
        HomePulse.Snapshot snapshot();
    }

    private static final long REFRESH_MS = 250L;
    private static final int PANEL_WIDTH_DP = 260;
    private static final int PANEL_ESTIMATED_HEIGHT_DP = 232;

    private final Context context;
    private final StateSource source;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int orbSize;
    private final int edgeMargin;
    private final int topMargin;
    private final int bottomMargin;
    private final int touchSlop;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            refreshNow();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private OrbWindow orbWindow;
    private PulseOrbView orb;
    private WindowManager.LayoutParams orbParams;
    private PicoOrbPanelView panelWindow;
    private WindowManager.LayoutParams panelParams;
    private PicoOrbDismissTargetView dismissTarget;
    private WindowManager.LayoutParams dismissTargetParams;
    private HomePulse.Snapshot latest;
    private String side;
    private boolean destroyed;
    private boolean dragging;
    private boolean temporarilyHidden;
    private String hiddenWakeKey = "";
    private float downRawX;
    private float downRawY;
    private int downWindowX;
    private int downWindowY;
    private int lastScreenWidth;
    private int lastScreenHeight;

    PicoOrbOverlayController(Context context, StateSource source) {
        this.context = context.getApplicationContext();
        this.source = source;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.orbSize = dp(84);
        this.edgeMargin = dp(6);
        this.topMargin = dp(28);
        this.bottomMargin = dp(58);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.side = McpocketPolicySettings.picoOrbSide(context);
    }

    void start() {
        destroyed = false;
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    void refreshNow() {
        if (destroyed || windowManager == null) return;
        HomePulse.Snapshot snapshot = source.snapshot();
        latest = snapshot;
        if (temporarilyHidden) {
            if (snapshot != null && hiddenWakeKey.equals(snapshot.wakeKey)) {
                removePanel();
                removeOrb();
                return;
            }
            temporarilyHidden = false;
            hiddenWakeKey = "";
        }
        boolean allowed = snapshot != null
                && !PicoOrbState.HIDDEN.equals(snapshot.orbMode)
                && McpocketPolicySettings.isPicoOrbEnabled(context)
                && Settings.canDrawOverlays(context);
        if (!allowed) {
            removePanel();
            removeOrb();
            return;
        }
        ensureOrb();
        if (orb == null) return;
        PickPicoTheme.State theme = PickPicoTheme.load(context);
        orb.setTheme(theme);
        orb.setMode(snapshot.orbMode);
        orbWindow.setContentDescription(PicoOrbState.label(snapshot.orbMode) + ". " + safeDetail(snapshot));
        positionForCurrentDisplay(false);
        if (panelWindow != null) updatePanel(snapshot);
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(refreshTask);
        removePanel();
        removeDismissTarget();
        removeOrb();
    }

    private void ensureOrb() {
        if (orbWindow != null) return;
        try {
            orbWindow = new OrbWindow(context);
            orbWindow.setClipChildren(false);
            orbWindow.setClipToPadding(false);
            orbWindow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            orb = new PulseOrbView(context, PickPicoTheme.load(context));
            orb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            orbWindow.addView(orb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            orbWindow.setOnClickListener(view -> handleOrbClick());
            orbWindow.setOnTouchListener(this::handleOrbTouch);

            orbParams = new WindowManager.LayoutParams(
                    orbSize,
                    orbSize,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            orbParams.gravity = Gravity.TOP | Gravity.START;
            positionForCurrentDisplay(true);
            windowManager.addView(orbWindow, orbParams);
            orb.setResumed(true);
        } catch (RuntimeException error) {
            android.util.Log.w("PickPicoOrb", "Unable to show Pico ball", error);
            removeOrb();
        }
    }

    private boolean handleOrbTouch(View view, MotionEvent event) {
        if (orbParams == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downWindowX = orbParams.x;
                downWindowY = orbParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    removePanel();
                    showDismissTarget();
                }
                if (dragging) {
                    int[] screen = screenSize();
                    orbParams.x = clamp(downWindowX + Math.round(dx), edgeMargin, screen[0] - orbSize - edgeMargin);
                    orbParams.y = clamp(downWindowY + Math.round(dy), topMargin, screen[1] - orbSize - bottomMargin);
                    updateOrbLayout();
                    updateDismissTargetState();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    if (isOverDismissTarget()) temporarilyHide();
                    else snapAndSave();
                } else {
                    view.performClick();
                }
                removeDismissTarget();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeDismissTarget();
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private void handleOrbClick() {
        HomePulse.Snapshot snapshot = latest;
        if (snapshot == null) return;
        if (PicoOrbState.HUMAN_HELP.equals(snapshot.orbMode)
                && !TextUtils.isEmpty(snapshot.pendingRequestId)) {
            removePanel();
            launch(new Intent(context, HumanHelpActivity.class)
                    .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, snapshot.pendingRequestId));
            return;
        }
        if (panelWindow != null) removePanel();
        else showPanel(snapshot);
    }

    private void showPanel(HomePulse.Snapshot snapshot) {
        if (orbParams == null || panelWindow != null) return;
        try {
            panelWindow = new PicoOrbPanelView(context);
            panelWindow.setDismissAction(this::removePanel);

            panelParams = new WindowManager.LayoutParams(
                    panelWidthForScreen(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            panelParams.gravity = Gravity.TOP | Gravity.START;
            positionPanel();
            updatePanel(snapshot);
            windowManager.addView(panelWindow, panelParams);
        } catch (RuntimeException error) {
            android.util.Log.w("PickPicoOrb", "Unable to show Pico ball panel", error);
            removePanel();
        }
    }

    private void updatePanel(HomePulse.Snapshot snapshot) {
        if (panelWindow == null) return;
        panelWindow.bind(snapshot, PickPicoTheme.load(context).colorA, this::openPrimary,
                () -> openCaller(snapshot.caller));
        positionPanel();
        try {
            windowManager.updateViewLayout(panelWindow, panelParams);
        } catch (RuntimeException ignored) {
        }
    }

    private String safeDetail(HomePulse.Snapshot snapshot) {
        if (!TextUtils.isEmpty(snapshot.actionDetail)) return snapshot.actionDetail;
        if (!TextUtils.isEmpty(snapshot.agentState)) return snapshot.agentState;
        if (!TextUtils.isEmpty(snapshot.connection)) return snapshot.connection;
        return "Open PickPico for details";
    }

    private void openPrimary() {
        HomePulse.Snapshot snapshot = latest;
        removePanel();
        if (snapshot == null) return;
        if (PicoOrbState.HUMAN_HELP.equals(snapshot.orbMode)
                && !TextUtils.isEmpty(snapshot.pendingRequestId)) {
            launch(new Intent(context, HumanHelpActivity.class)
                    .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, snapshot.pendingRequestId));
        } else if (PicoOrbState.RUNNING.equals(snapshot.orbMode)
                || PicoOrbState.COMPLETED.equals(snapshot.orbMode)
                || PicoOrbState.BLOCKED.equals(snapshot.orbMode)) {
            launch(new Intent(context, AgentInboxActivity.class));
        } else {
            int page = PicoOrbState.CONNECTING.equals(snapshot.orbMode)
                    || PicoOrbState.CONNECTION_ATTENTION.equals(snapshot.orbMode)
                    ? DashboardActivity.PAGE_REMOTE : DashboardActivity.PAGE_HOME;
            launch(new Intent(context, DashboardActivity.class)
                    .putExtra(DashboardActivity.EXTRA_PAGE, page));
        }
    }

    private void openCaller(CallerReturnTarget caller) {
        if (!caller.available()) return;
        try {
            Intent intent = null;
            if (!caller.url.isEmpty()) {
                intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(caller.url));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                if (!caller.packageName.isEmpty()) intent.setPackage(caller.packageName);
            } else if (!caller.packageName.isEmpty()) {
                intent = context.getPackageManager().getLaunchIntentForPackage(caller.packageName);
            }
            if (intent == null) throw new android.content.ActivityNotFoundException();
            // Preserve the caller's existing activity stack.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            removePanel();
        } catch (RuntimeException error) {
            android.widget.Toast.makeText(context, "無法開啟呼叫端，請確認 App 已安裝或連結可用", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (RuntimeException error) {
            android.util.Log.w("PickPicoOrb", "Unable to open Pico ball destination", error);
        }
    }

    private void snapAndSave() {
        int[] screen = screenSize();
        side = orbParams.x + orbSize / 2 < screen[0] / 2 ? "left" : "right";
        orbParams.x = "left".equals(side) ? edgeMargin : screen[0] - orbSize - edgeMargin;
        orbParams.y = clamp(orbParams.y, topMargin, screen[1] - orbSize - bottomMargin);
        updateOrbLayout();
        int availableY = Math.max(1, screen[1] - topMargin - bottomMargin - orbSize);
        float ratio = (orbParams.y - topMargin) / (float) availableY;
        McpocketPolicySettings.savePicoOrbPosition(context, side, ratio);
    }

    private void temporarilyHide() {
        temporarilyHidden = true;
        hiddenWakeKey = latest == null ? "" : latest.wakeKey;
        removePanel();
        removeDismissTarget();
        removeOrb();
    }

    private void showDismissTarget() {
        if (dismissTarget != null || windowManager == null) return;
        try {
            int size = dp(76);
            int[] screen = screenSize();
            dismissTarget = new PicoOrbDismissTargetView(context);
            dismissTargetParams = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            dismissTargetParams.gravity = Gravity.TOP | Gravity.START;
            dismissTargetParams.x = (screen[0] - size) / 2;
            dismissTargetParams.y = screen[1] - size - bottomMargin;
            windowManager.addView(dismissTarget, dismissTargetParams);
        } catch (RuntimeException error) {
            removeDismissTarget();
        }
    }

    private void updateDismissTargetState() {
        if (dismissTarget != null) dismissTarget.setArmed(isOverDismissTarget());
    }

    private boolean isOverDismissTarget() {
        if (dismissTargetParams == null || orbParams == null) return false;
        float orbCenterX = orbParams.x + orbSize / 2f;
        float orbCenterY = orbParams.y + orbSize / 2f;
        float targetCenterX = dismissTargetParams.x + dismissTargetParams.width / 2f;
        float targetCenterY = dismissTargetParams.y + dismissTargetParams.height / 2f;
        return Math.hypot(orbCenterX - targetCenterX, orbCenterY - targetCenterY) <= dp(62);
    }

    private void removeDismissTarget() {
        PicoOrbDismissTargetView target = dismissTarget;
        dismissTarget = null;
        dismissTargetParams = null;
        if (target == null || windowManager == null) return;
        try { windowManager.removeView(target); } catch (RuntimeException ignored) { }
    }

    private void positionForCurrentDisplay(boolean force) {
        if (orbParams == null) return;
        int[] screen = screenSize();
        if (!force && screen[0] == lastScreenWidth && screen[1] == lastScreenHeight) return;
        lastScreenWidth = screen[0];
        lastScreenHeight = screen[1];
        side = McpocketPolicySettings.picoOrbSide(context);
        int availableY = Math.max(1, screen[1] - topMargin - bottomMargin - orbSize);
        orbParams.x = "left".equals(side) ? edgeMargin : screen[0] - orbSize - edgeMargin;
        orbParams.y = topMargin + Math.round(availableY * McpocketPolicySettings.picoOrbYRatio(context));
        updateOrbLayout();
        if (panelWindow != null) positionPanel();
    }

    private void positionPanel() {
        if (panelParams == null || orbParams == null) return;
        int[] screen = screenSize();
        int panelWidth = panelParams.width;
        int panelHeight = dp(PANEL_ESTIMATED_HEIGHT_DP);
        if (panelWindow != null) {
            panelWindow.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            panelHeight = panelWindow.getMeasuredHeight();
        }
        int gap = dp(3);
        panelParams.x = "left".equals(side)
                ? orbParams.x + orbSize + gap
                : orbParams.x - panelWidth - gap;
        panelParams.x = clamp(panelParams.x, edgeMargin, screen[0] - panelWidth - edgeMargin);
        panelParams.y = clamp(
                orbParams.y + orbSize / 2 - panelHeight / 2,
                topMargin,
                screen[1] - panelHeight - bottomMargin);
    }

    private void updateOrbLayout() {
        if (orbWindow == null || orbParams == null) return;
        try {
            windowManager.updateViewLayout(orbWindow, orbParams);
        } catch (RuntimeException ignored) {
        }
    }

    private void removePanel() {
        PicoOrbPanelView panel = panelWindow;
        panelWindow = null;
        panelParams = null;
        if (panel == null || windowManager == null) return;
        try {
            windowManager.removeView(panel);
        } catch (RuntimeException ignored) {
        }
    }

    private void removeOrb() {
        FrameLayout window = orbWindow;
        if (orb != null) orb.setResumed(false);
        orbWindow = null;
        orb = null;
        orbParams = null;
        lastScreenWidth = lastScreenHeight = 0;
        if (window == null || windowManager == null) return;
        try {
            windowManager.removeView(window);
        } catch (RuntimeException ignored) {
        }
    }

    private int[] screenSize() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private int panelWidthForScreen() {
        int screenWidth = screenSize()[0];
        return Math.min(dp(PANEL_WIDTH_DP), Math.max(dp(210), screenWidth - orbSize - dp(15)));
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class OrbWindow extends FrameLayout {
        OrbWindow(Context context) {
            super(context);
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

}
