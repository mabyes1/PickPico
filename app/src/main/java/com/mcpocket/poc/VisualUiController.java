package com.mcpocket.poc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.InputMethod;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.SurroundingText;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Screenshot-based fallback. Shares Android gesture injection with semantic long-press. */
final class VisualUiController {
    private static final VisualObservationStore FRAMES = new VisualObservationStore();
    private static final AtomicLong EDITOR_SESSION = new AtomicLong();
    private VisualUiController() {}

    static void invalidate() { FRAMES.invalidate(); }
    static void editorChanged() { EDITOR_SESSION.incrementAndGet(); invalidate(); }

    static String context(McpAccessibilityService service) {
        Display display = display(service);
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (display == null || root == null) return "";
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return root.getPackageName() + ":" + root.getWindowId() + ":" + metrics.widthPixels + ":"
                + metrics.heightPixels + ":" + display.getRotation() + ":" + EDITOR_SESSION.get();
    }

    static void attach(McpAccessibilityService service, JSONObject capture, String before) throws Exception {
        invalidate();
        if (!capture.optBoolean("captured")) return;
        String current = context(service);
        Display display = display(service);
        DisplayMetrics metrics = new DisplayMetrics();
        if (display != null) display.getRealMetrics(metrics);
        boolean aligned = display != null && capture.optBoolean("freshFrame") && !current.isEmpty()
                && current.equals(before) && metrics.widthPixels == capture.optInt("width")
                && metrics.heightPixels == capture.optInt("height");
        capture.put("visualActionsAvailable", aligned);
        if (!aligned) {
            capture.put("visualActionReason", "Screen context changed, frame is cached, or dimensions differ; capture again before visual actions.");
            return;
        }
        capture.put("observationId", FRAMES.record(current, metrics.widthPixels, metrics.heightPixels, SystemClock.elapsedRealtime()))
                .put("coordinateSpace", "full_display_pixels")
                .put("displayId", Display.DEFAULT_DISPLAY)
                .put("rotation", display.getRotation())
                .put("visualActionHint", "One action within 60s; point/swipe use original width/height pixels. Then capture again. Guard checks context, not every pixel/content change.")
                .put("focusedInput", onMain(() -> focusedState(service)));
    }

    static JSONObject gesture(McpAccessibilityService service, JSONObject args, boolean swipe, long callCount) throws Exception {
        JSONObject start = swipe ? args.getJSONObject("swipe").getJSONObject("start") : args.getJSONObject("point");
        JSONObject end = swipe ? args.getJSONObject("swipe").getJSONObject("end") : start;
        double x = start.getDouble("x"), y = start.getDouble("y");
        double endX = end.getDouble("x"), endY = end.getDouble("y");
        FRAMES.consume(args.getString("observationId"), context(service), SystemClock.elapsedRealtime(), x, y, endX, endY);
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        if (swipe) path.lineTo((float) endX, (float) endY);
        long duration = swipe ? args.getJSONObject("swipe").optInt("durationMs", 400)
                : "long_click".equals(args.optString("action")) ? longPressDuration() : 80L;
        JSONObject result = dispatch(service, path, duration);
        return result.put("method", "screen_coordinates")
                .put("action", swipe ? "swipe" : args.getString("action"))
                .put("start", start).put("end", end).put("durationMs", duration)
                .put("toolCallCount", callCount)
                .put("next", "Capture again and verify the intended result; gesture completion alone is not task completion.");
    }

    static long longPressDuration() { return Math.max(650L, ViewConfiguration.getLongPressTimeout() + 150L); }

    static JSONObject dispatch(McpAccessibilityService service, Path path, long duration) throws Exception {
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration)).build();
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] completed = {false};
        boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { completed[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription description) { latch.countDown(); }
        }, new Handler(Looper.getMainLooper()));
        if (!accepted) return new JSONObject().put("performed", false).put("error", "gesture_not_accepted");
        boolean finished;
        try { finished = latch.await(duration + 2000L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new JSONObject().put("status", "unknown").put("error", "gesture_wait_interrupted");
        }
        if (!finished) return new JSONObject().put("status", "unknown").put("error", "gesture_result_unknown")
                .put("next", "Re-observe before retrying; the gesture may have happened.");
        return new JSONObject().put("performed", completed[0])
                .put("status", completed[0] ? "completed" : "cancelled");
    }

    static JSONObject type(McpAccessibilityService service, JSONObject args, long callCount) throws Exception {
        if (Build.VERSION.SDK_INT < 33) {
            invalidate();
            return new JSONObject().put("performed", false).put("error", "focused_input_requires_android_13");
        }
        return onMain(() -> typeOnMain(service, args, callCount));
    }

    @android.annotation.TargetApi(33)
    private static JSONObject typeOnMain(McpAccessibilityService service, JSONObject args, long callCount) throws Exception {
        FRAMES.consume(args.getString("observationId"), context(service), SystemClock.elapsedRealtime());
        InputMethod method = service.getInputMethod();
        EditorInfo editor = method == null ? null : method.getCurrentInputEditorInfo();
        InputMethod.AccessibilityInputConnection connection = method == null ? null : method.getCurrentInputConnection();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (method == null || !method.getCurrentInputStarted() || editor == null || connection == null)
            return new JSONObject().put("performed", false).put("error", "no_focused_input_connection")
                    .put("next", "Tap the intended text field, capture again and check focusedInput.available. If disabled, reconnect PickPico accessibility service.");
        if (root == null || root.getPackageName() == null || editor.packageName == null || !editor.packageName.contentEquals(root.getPackageName()))
            return new JSONObject().put("performed", false).put("error", "focused_editor_not_foreground");
        String text = args.getString("text"), mode = args.getString("textMode");
        if ("replace".equals(mode)) {
            // Selecting all is non-destructive. Confirm the selection before committing text.
            connection.performContextMenuAction(android.R.id.selectAll);
            SurroundingText selected = connection.getSurroundingText(1, 1, 0);
            if (!wholeFieldSelected(selected))
                return new JSONObject().put("performed", false).put("selectionMayHaveChanged", true)
                        .put("error", "cannot_confirm_full_selection")
                        .put("next", "Capture again. Field content was not written; use insert only if the visible cursor/selection is appropriate.");
        }
        connection.commitText(text, 1, null);
        return new JSONObject().put("performed", true).put("method", "accessibility_input_connection")
                .put("textMode", mode).put("textCharacters", text.length()).put("verified", false)
                .put("editorPackage", editor.packageName).put("toolCallCount", callCount)
                .put("next", "Text was dispatched to the focused editor. Capture again to verify acceptance and exact content before continuing.");
    }

    @android.annotation.TargetApi(33)
    static boolean wholeFieldSelected(SurroundingText text) {
        if (text == null || text.getText() == null || text.getOffset() != 0) return false;
        return Math.min(text.getSelectionStart(), text.getSelectionEnd()) == 0
                && Math.max(text.getSelectionStart(), text.getSelectionEnd()) == text.getText().length();
    }

    private static JSONObject focusedState(McpAccessibilityService service) throws Exception {
        JSONObject state = new JSONObject().put("supported", Build.VERSION.SDK_INT >= 33).put("available", false);
        if (Build.VERSION.SDK_INT < 33) return state.put("reason", "Android 13+ is required");
        InputMethod method = service.getInputMethod();
        EditorInfo info = method == null ? null : method.getCurrentInputEditorInfo();
        if (method != null && method.getCurrentInputStarted() && info != null && method.getCurrentInputConnection() != null)
            return state.put("available", true).put("packageName", info.packageName);
        return state.put("reason", "No active input connection; focus a text field first. After an update the accessibility service may need reconnecting.");
    }

    private static Display display(McpAccessibilityService service) {
        DisplayManager manager = (DisplayManager) service.getSystemService(android.content.Context.DISPLAY_SERVICE);
        return manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private static <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
        FutureTask<T> future = new FutureTask<>(task);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(future);
        try { return future.get(5000L, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException(cause);
        } finally {
            handler.removeCallbacks(future);
            future.cancel(false);
        }
    }
}
