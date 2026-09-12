package com.mcpocket.poc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.util.Base64;

/** Hyper Mode bridge for semantic cross-app Android UI inspection and actions. */
public final class McpAccessibilityService extends AccessibilityService {
    private static volatile McpAccessibilityService activeInstance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Phase A is command-driven. Event streaming is a later Hyper capability.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        activeInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        activeInstance = null;
        super.onDestroy();
    }

    static boolean hasAccess(Context context) {
        if (activeInstance != null) {
            return true;
        }
        String expected = context.getPackageName() + "/" + McpAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        for (String entry : enabled.split(":")) {
            if (expected.equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    static boolean canTakeScreenshot(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && activeInstance != null
                && hasAccess(context);
    }

    static JSONObject screenCapture(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return unavailable("Accessibility screenshots require Android 11 or newer", callCount);
        }

        return screenCapture(service, arguments, callCount, 2500L);
    }

    @android.annotation.TargetApi(30)
    static JSONObject screenCapture(McpAccessibilityService service, JSONObject arguments,
                                    long callCount, long timeoutMs) throws JSONException {
        try (CaptureResources resources = new CaptureResources()) {
            return screenCaptureWithResources(service, arguments, callCount, timeoutMs, resources);
        }
    }

    @android.annotation.TargetApi(30)
    private static JSONObject screenCaptureWithResources(
            McpAccessibilityService service, JSONObject arguments, long callCount,
            long timeoutMs, CaptureResources resources) throws JSONException {

        int quality = clamp(arguments.optInt("quality", 82), 50, 100);
        boolean returnContent = arguments.optBoolean("returnContent", true);
        CountDownLatch latch = new CountDownLatch(1);
        ScreenshotResult[] resultHolder = new ScreenshotResult[1];
        int[] failureCode = new int[]{-1};

        try {
            service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshotResult) {
                            // add() closes late resources immediately after a timeout
                            // or interruption. A delivered result stays owned until
                            // bitmap conversion and encoding have both finished.
                            if (resources.add(screenshotResult.getHardwareBuffer())) {
                                resultHolder[0] = screenshotResult;
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            failureCode[0] = errorCode;
                            latch.countDown();
                        }
                    });
        } catch (RuntimeException error) {
            return new JSONObject()
                    .put("captured", false)
                    .put("error", "accessibility_screen_capture_failed")
                    .put("message", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()))
                    .put("captureMode", "accessibility")
                    .put("toolCallCount", callCount);
        }

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new JSONObject()
                        .put("captured", false)
                        .put("error", "accessibility_screen_capture_timeout")
                        .put("captureMode", "accessibility")
                        .put("toolCallCount", callCount);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new JSONObject()
                    .put("captured", false)
                    .put("error", "accessibility_screen_capture_interrupted")
                    .put("captureMode", "accessibility")
                    .put("toolCallCount", callCount);
        }

        ScreenshotResult screenshotResult = resultHolder[0];
        if (screenshotResult == null) {
            return new JSONObject()
                    .put("captured", false)
                    .put("error", "accessibility_screen_capture_failed")
                    .put("errorCode", failureCode[0])
                    .put("captureMode", "accessibility")
                    .put("toolCallCount", callCount);
        }

        HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
        Bitmap hardwareBitmap = null;
        Bitmap bitmap = null;
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshotResult.getColorSpace());
            if (hardwareBitmap == null) {
                throw new IllegalStateException("Android returned an unreadable screenshot buffer");
            }
            bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) {
                throw new IllegalStateException("Unable to copy accessibility screenshot bitmap");
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) {
                throw new IllegalStateException("Unable to encode accessibility screenshot as JPEG");
            }
            byte[] jpeg = bytes.toByteArray();
            String relativePath = "captures/screen-" + System.currentTimeMillis() + ".jpg";
            File root = new File(service.getFilesDir(), "workspaces");
            File output = new File(root, relativePath);
            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Unable to create screen capture directory");
            }
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(jpeg);
                stream.getFD().sync();
            }

            JSONObject result = new JSONObject()
                    .put("captured", true)
                    .put("width", bitmap.getWidth())
                    .put("height", bitmap.getHeight())
                    .put("mimeType", "image/jpeg")
                    .put("path", relativePath)
                    .put("sizeBytes", jpeg.length)
                    .put("freshFrame", true)
                    .put("frameAgeMs", 0)
                    .put("captureMode", "accessibility")
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
            if (returnContent) {
                result.put("_mcpContent", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", ScreenFramePolicy.describe(true, 0L, relativePath)))
                        .put(new JSONObject()
                                .put("type", "image")
                                .put("mimeType", "image/jpeg")
                                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))));
            }
            return result;
        } catch (Throwable error) {
            return new JSONObject()
                    .put("captured", false)
                    .put("error", "accessibility_screen_capture_failed")
                    .put("message", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()))
                    .put("captureMode", "accessibility")
                    .put("toolCallCount", callCount);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled()) hardwareBitmap.recycle();
            // HardwareBuffer belongs to CaptureResources, including early returns.
        }
    }

    static JSONObject inspect(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        List<WindowRoot> roots = windowRoots(service);
        if (roots.isEmpty()) {
            return unavailable("No active accessibility window/root is available", callCount);
        }
        int maxNodes = clamp(arguments.optInt("maxNodes", 200), 1, 1000);
        int maxDepth = clamp(arguments.optInt("maxDepth", 12), 1, 30);
        boolean includeInvisible = arguments.optBoolean("includeInvisible", false);
        JSONArray nodes = new JSONArray();
        JSONArray windows = new JSONArray();
        Counter counter = new Counter(maxNodes);
        boolean multiWindow = roots.size() > 1;
        for (WindowRoot windowRoot : roots) {
            if (counter.remaining <= 0) break;
            String rootPath = multiWindow ? "w" + windowRoot.windowId + "/0" : "0";
            appendNode(windowRoot.root, rootPath, 0, maxDepth, includeInvisible, nodes, counter);
            windows.put(windowRoot.describe());
        }
        WindowRoot primary = roots.get(0);
        return new JSONObject()
                .put("available", true)
                .put("packageName", safe(primary.root.getPackageName()))
                .put("windowTitle", primary.title)
                .put("windows", windows)
                .put("nodes", nodes)
                .put("count", nodes.length())
                .put("truncated", counter.truncated)
                .put("toolCallCount", callCount);
    }

    static JSONObject action(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        String action = arguments.optString("action", "");
        if ("back".equals(action) || "home".equals(action) || "recents".equals(action)) {
            int globalAction = "back".equals(action)
                    ? GLOBAL_ACTION_BACK
                    : ("home".equals(action) ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_RECENTS);
            boolean performed = service.performGlobalAction(globalAction);
            return new JSONObject()
                    .put("performed", performed)
                    .put("action", action)
                    .put("scope", "global")
                    .put("toolCallCount", callCount);
        }

        AccessibilityNodeInfo node = findNode(service, arguments.optJSONObject("selector"));
        if (node == null) {
            return notFound(arguments.optJSONObject("selector"), callCount);
        }
        int androidAction;
        if ("click".equals(action)) {
            AccessibilityNodeInfo clickable = clickableNode(node);
            boolean performed = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return nodeActionResult(clickable == null ? node : clickable, action, performed, callCount);
        } else if ("long_click".equals(action)) {
            boolean performed = performTouchLongPress(service, node);
            if (!performed) {
                performed = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
            JSONObject result = nodeActionResult(node, action, performed, callCount);
            result.put("method", performed ? "touch_gesture_or_accessibility" : "failed");
            return result;
        } else if ("focus".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_FOCUS;
        } else if ("accessibility_focus".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS;
        } else {
            throw new CommandRuntime.CommandInputException(
                    "ui.action action must be click, long_click, focus, accessibility_focus, back, home, or recents");
        }
        return nodeActionResult(node, action, node.performAction(androidAction), callCount);
    }

    private static boolean performTouchLongPress(McpAccessibilityService service, AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;
        float x = bounds.exactCenterX();
        float y = bounds.exactCenterY();
        Path path = new Path();
        path.moveTo(x, y);
        long duration = Math.max(650L, ViewConfiguration.getLongPressTimeout() + 150L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration))
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] completed = new boolean[]{false};
        boolean accepted;
        try {
            accepted = service.dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    completed[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    latch.countDown();
                }
            }, null);
        } catch (RuntimeException error) {
            return false;
        }
        if (!accepted) return false;
        try {
            latch.await(duration + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return completed[0];
    }

    static JSONObject type(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        AccessibilityNodeInfo node = findNode(service, arguments.optJSONObject("selector"));
        if (node == null) {
            return notFound(arguments.optJSONObject("selector"), callCount);
        }
        String value = arguments.optString("text", "");
        if (arguments.optBoolean("append", false)) {
            CharSequence existing = node.getText();
            value = (existing == null ? "" : existing.toString()) + value;
        }
        Bundle bundle = new Bundle();
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
        JSONObject result = nodeActionResult(node, "set_text", performed, callCount);
        result.put("textCharacters", value.length());
        return result;
    }

    static JSONObject scroll(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        JSONObject selector = arguments.optJSONObject("selector");
        AccessibilityNodeInfo node = selector == null ? findFirstScrollable(service) : findNode(service, selector);
        if (node == null) {
            return notFound(selector, callCount);
        }
        String direction = arguments.optString("direction", "forward");
        int androidAction;
        if ("forward".equals(direction) || "down".equals(direction) || "right".equals(direction)) {
            androidAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        } else if ("backward".equals(direction) || "up".equals(direction) || "left".equals(direction)) {
            androidAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        } else {
            throw new CommandRuntime.CommandInputException(
                    "ui.scroll direction must be forward, backward, up, down, left, or right");
        }
        JSONObject result = nodeActionResult(node, "scroll_" + direction, node.performAction(androidAction), callCount);
        result.put("direction", direction);
        return result;
    }

    private static McpAccessibilityService requireService() {
        McpAccessibilityService service = activeInstance;
        if (service == null) {
            throw new CommandRuntime.CommandInputException(
                    "PickPico Accessibility Service is not connected. Enable Hyper Mode and Accessibility access locally.");
        }
        return service;
    }

    private static void appendNode(
            AccessibilityNodeInfo node,
            String path,
            int depth,
            int maxDepth,
            boolean includeInvisible,
            JSONArray output,
            Counter counter) throws JSONException {
        if (node == null || counter.remaining <= 0) {
            if (counter.remaining <= 0) {
                counter.truncated = true;
            }
            return;
        }
        if (includeInvisible || node.isVisibleToUser()) {
            output.put(describeNode(node, path, depth));
            counter.remaining--;
            if (counter.remaining <= 0) {
                counter.truncated = node.getChildCount() > 0;
                return;
            }
        }
        if (depth >= maxDepth) {
            if (node.getChildCount() > 0) {
                counter.truncated = true;
            }
            return;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                appendNode(
                        child,
                        path + "/" + index,
                        depth + 1,
                        maxDepth,
                        includeInvisible,
                        output,
                        counter);
                if (counter.remaining <= 0) {
                    return;
                }
            }
        }
    }

    private static JSONObject describeNode(AccessibilityNodeInfo node, String path, int depth) throws JSONException {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONArray actions = new JSONArray();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            CharSequence label = action.getLabel();
            actions.put(new JSONObject()
                    .put("id", action.getId())
                    .put("label", label == null ? "" : label.toString()));
        }
        return new JSONObject()
                .put("path", path)
                .put("depth", depth)
                .put("className", safe(node.getClassName()))
                .put("packageName", safe(node.getPackageName()))
                .put("viewId", safe(node.getViewIdResourceName()))
                .put("text", safe(node.getText()))
                .put("contentDescription", safe(node.getContentDescription()))
                .put("visible", node.isVisibleToUser())
                .put("enabled", node.isEnabled())
                .put("clickable", node.isClickable())
                .put("longClickable", node.isLongClickable())
                .put("focusable", node.isFocusable())
                .put("focused", node.isFocused())
                .put("editable", node.isEditable())
                .put("scrollable", node.isScrollable())
                .put("bounds", new JSONObject()
                        .put("left", bounds.left)
                        .put("top", bounds.top)
                        .put("right", bounds.right)
                        .put("bottom", bounds.bottom))
                .put("actions", actions);
    }

    private static AccessibilityNodeInfo findNode(McpAccessibilityService service, JSONObject selector) {
        List<WindowRoot> roots = windowRoots(service);
        if (roots.isEmpty()) {
            return null;
        }
        if (selector == null || selector.length() == 0) {
            return roots.get(0).root;
        }
        String path = selector.optString("path", "");
        if (!path.isEmpty()) {
            WindowPath windowPath = parseWindowPath(path);
            if (windowPath != null) {
                WindowRoot windowRoot = findWindowRoot(roots, windowPath.windowId);
                AccessibilityNodeInfo byPath = windowRoot == null
                        ? null
                        : nodeByPath(windowRoot.root, windowPath.nodePath);
                if (byPath != null && matches(byPath, selector)) {
                    return byPath;
                }
            } else {
                AccessibilityNodeInfo byPath = nodeByPath(roots.get(0).root, path);
                if (byPath != null && matches(byPath, selector)) {
                    return byPath;
                }
            }
        }
        int wantedInstance = Math.max(0, selector.optInt("instance", 0));
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        for (WindowRoot root : roots) {
            collectMatches(root.root, selector, matches, wantedInstance + 1);
            if (matches.size() > wantedInstance) break;
        }
        return matches.size() > wantedInstance ? matches.get(wantedInstance) : null;
    }

    private static List<WindowRoot> windowRoots(McpAccessibilityService service) {
        List<WindowRoot> result = new ArrayList<>();
        AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
        int activeWindowId = windowId(activeRoot);
        if (activeRoot != null) {
            result.add(new WindowRoot(activeRoot, activeRoot.getWindow()));
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) return result;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            int id = window.getId();
            if (id == activeWindowId) continue;
            result.add(new WindowRoot(root, window));
        }
        return result;
    }

    private static int windowId(AccessibilityNodeInfo root) {
        if (root == null || root.getWindow() == null) return Integer.MIN_VALUE;
        return root.getWindow().getId();
    }

    private static WindowRoot findWindowRoot(List<WindowRoot> roots, int windowId) {
        for (WindowRoot root : roots) {
            if (root.windowId == windowId) return root;
        }
        return null;
    }

    private static WindowPath parseWindowPath(String path) {
        if (path == null || path.length() < 4 || path.charAt(0) != 'w') return null;
        int slash = path.indexOf('/');
        if (slash <= 1 || slash >= path.length() - 1) return null;
        try {
            int windowId = Integer.parseInt(path.substring(1, slash));
            return new WindowPath(windowId, path.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static AccessibilityNodeInfo nodeByPath(AccessibilityNodeInfo root, String path) {
        String[] parts = path.split("/");
        AccessibilityNodeInfo current = root;
        int start = parts.length > 0 && "0".equals(parts[0]) ? 1 : 0;
        for (int index = start; index < parts.length; index++) {
            if (parts[index].isEmpty()) {
                continue;
            }
            try {
                int childIndex = Integer.parseInt(parts[index]);
                if (current == null || childIndex < 0 || childIndex >= current.getChildCount()) {
                    return null;
                }
                current = current.getChild(childIndex);
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return current;
    }

    private static void collectMatches(
            AccessibilityNodeInfo node,
            JSONObject selector,
            List<AccessibilityNodeInfo> output,
            int limit) {
        if (node == null || output.size() >= limit) {
            return;
        }
        if (matches(node, selector)) {
            output.add(node);
            if (output.size() >= limit) {
                return;
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectMatches(node.getChild(index), selector, output, limit);
            if (output.size() >= limit) {
                return;
            }
        }
    }

    private static boolean matches(AccessibilityNodeInfo node, JSONObject selector) {
        return matchesField(selector, "viewId", node.getViewIdResourceName())
                && matchesField(selector, "text", node.getText())
                && matchesField(selector, "contentDescription", node.getContentDescription())
                && matchesField(selector, "className", node.getClassName());
    }

    private static boolean matchesField(JSONObject selector, String key, CharSequence actual) {
        String expected = selector.optString(key, "");
        return expected.isEmpty() || expected.equals(actual == null ? "" : actual.toString());
    }

    private static AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return node;
    }

    private static AccessibilityNodeInfo findFirstScrollable(McpAccessibilityService service) {
        for (WindowRoot root : windowRoots(service)) {
            AccessibilityNodeInfo found = findFirstScrollable(root.root);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isScrollable()) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findFirstScrollable(node.getChild(index));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static final class WindowRoot {
        final AccessibilityNodeInfo root;
        final int windowId;
        final String title;
        final int type;
        final int layer;
        final boolean active;
        final boolean focused;

        WindowRoot(AccessibilityNodeInfo root, AccessibilityWindowInfo window) {
            this.root = root;
            this.windowId = window == null ? windowId(root) : window.getId();
            this.title = window == null ? "" : safe(window.getTitle());
            this.type = window == null ? 0 : window.getType();
            this.layer = window == null ? 0 : window.getLayer();
            this.active = window != null && window.isActive();
            this.focused = window != null && window.isFocused();
        }

        JSONObject describe() throws JSONException {
            return new JSONObject()
                    .put("id", windowId)
                    .put("title", title)
                    .put("type", type)
                    .put("layer", layer)
                    .put("active", active)
                    .put("focused", focused)
                    .put("packageName", safe(root.getPackageName()));
        }
    }

    private static final class WindowPath {
        final int windowId;
        final String nodePath;

        WindowPath(int windowId, String nodePath) {
            this.windowId = windowId;
            this.nodePath = nodePath;
        }
    }

    private static JSONObject nodeActionResult(
            AccessibilityNodeInfo node,
            String action,
            boolean performed,
            long callCount) throws JSONException {
        Rect bounds = new Rect();
        if (node != null) {
            node.getBoundsInScreen(bounds);
        }
        return new JSONObject()
                .put("performed", performed)
                .put("action", action)
                .put("target", node == null ? JSONObject.NULL : new JSONObject()
                        .put("className", safe(node.getClassName()))
                        .put("viewId", safe(node.getViewIdResourceName()))
                        .put("text", safe(node.getText()))
                        .put("contentDescription", safe(node.getContentDescription()))
                        .put("bounds", new JSONObject()
                                .put("left", bounds.left)
                                .put("top", bounds.top)
                                .put("right", bounds.right)
                                .put("bottom", bounds.bottom)))
                .put("toolCallCount", callCount);
    }

    private static JSONObject notFound(JSONObject selector, long callCount) throws JSONException {
        return new JSONObject()
                .put("performed", false)
                .put("errorCode", "ui_target_not_found")
                .put("selector", selector == null ? new JSONObject() : new JSONObject(selector.toString()))
                .put("toolCallCount", callCount);
    }

    private static JSONObject unavailable(String reason, long callCount) throws JSONException {
        return new JSONObject()
                .put("available", false)
                .put("errorCode", "accessibility_window_unavailable")
                .put("reason", reason)
                .put("toolCallCount", callCount);
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Counter {
        int remaining;
        boolean truncated;

        Counter(int remaining) {
            this.remaining = remaining;
        }
    }
}
