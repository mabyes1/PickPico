package com.mcpocket.poc;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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

    static JSONObject inspect(JSONObject arguments, long callCount) throws JSONException {
        McpAccessibilityService service = requireService();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return unavailable("No active accessibility window/root is available", callCount);
        }
        int maxNodes = clamp(arguments.optInt("maxNodes", 200), 1, 1000);
        int maxDepth = clamp(arguments.optInt("maxDepth", 12), 1, 30);
        boolean includeInvisible = arguments.optBoolean("includeInvisible", false);
        JSONArray nodes = new JSONArray();
        Counter counter = new Counter(maxNodes);
        appendNode(root, "0", 0, maxDepth, includeInvisible, nodes, counter);
        return new JSONObject()
                .put("available", true)
                .put("packageName", safe(root.getPackageName()))
                .put("windowTitle", root.getWindow() == null ? "" : safe(root.getWindow().getTitle()))
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
            androidAction = AccessibilityNodeInfo.ACTION_LONG_CLICK;
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
        AccessibilityNodeInfo node = selector == null
                ? findFirstScrollable(service.getRootInActiveWindow())
                : findNode(service, selector);
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
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        if (selector == null || selector.length() == 0) {
            return root;
        }
        String path = selector.optString("path", "");
        if (!path.isEmpty()) {
            AccessibilityNodeInfo byPath = nodeByPath(root, path);
            if (byPath != null && matches(byPath, selector)) {
                return byPath;
            }
        }
        int wantedInstance = Math.max(0, selector.optInt("instance", 0));
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectMatches(root, selector, matches, wantedInstance + 1);
        return matches.size() > wantedInstance ? matches.get(wantedInstance) : null;
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
