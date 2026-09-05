package com.mcpocket.poc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Product-facing Agent Activity history using the same glass language as DashboardActivity. */
public final class AgentInboxActivity extends Activity {
    static final String EXTRA_ENTRY_ID = "entryId";

    private static final int BG = PickPicoTheme.BASE_BG;
    private static final int TEXT = PickPicoTheme.TEXT;
    private static final int MUTED = PickPicoTheme.MUTED;
    private static final int DIM = PickPicoTheme.DIM;
    private static final int GREEN = PickPicoTheme.GREEN;
    private static final int BLUE = PickPicoTheme.BLUE;
    private static final int RED = PickPicoTheme.RED;

    private LinearLayout itemsContainer;
    private LinearLayout pendingContainer;
    private TextView inboxCount;
    private TextView clearAction;
    private TextView topStatusDot;
    private PickPicoTheme.State theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = PickPicoTheme.load(this);
        configureWindow();
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        theme = PickPicoTheme.load(this);
        renderItems();
        refreshTopStatus();
    }

    private void configureWindow() {
        Window window = getWindow();
        boolean light = PickPicoTheme.isLightBackground(theme);
        int barColor = theme != null && !theme.gradient ? theme.colorA : BG;
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);
        int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildContent() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);
        stage.addView(new PickPicoTheme.BackgroundView(this, theme), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        stage.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(14), dp(24), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout summary = glassCard(false);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);

        TextView summaryIcon = text("∿", 26, Typeface.NORMAL, GREEN);
        summaryIcon.setGravity(Gravity.CENTER);
        summaryIcon.setBackground(PickPicoTheme.control(theme, dp(14), GREEN, false));
        summary.addView(summaryIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        inboxCount = text("0 EVENTS", 18, Typeface.BOLD, TEXT);
        inboxCount.setLetterSpacing(.035f);
        inboxCount.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        countParams.leftMargin = dp(14);
        summary.addView(inboxCount, countParams);

        clearAction = actionButton("CLEAR", RED);
        clearAction.setOnClickListener(v -> confirmClearInbox());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(78), dp(36));
        clearParams.leftMargin = dp(8);
        summary.addView(clearAction, clearParams);
        root.addView(summary);

        pendingContainer = new LinearLayout(this);
        pendingContainer.setOrientation(LinearLayout.VERTICAL);
        pendingContainer.setVisibility(View.GONE);
        root.addView(pendingContainer);

        TextView section = text("RECENT", 10, Typeface.BOLD, DIM);
        section.setLetterSpacing(.12f);
        section.setPadding(dp(2), dp(20), 0, dp(10));
        root.addView(section);

        itemsContainer = new LinearLayout(this);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemsContainer);

        View nav = buildBottomNav();
        LinearLayout.LayoutParams navLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        navLayout.setMargins(dp(18), 0, dp(18), dp(8));
        shell.addView(nav, navLayout);
        return stage;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(7), dp(8), dp(7));
        nav.setBackground(PickPicoTheme.strongGlass(theme, dp(26)));
        nav.setElevation(dp(8));
        nav.addView(navItem("⌂", "HOME", false, DashboardActivity.PAGE_HOME), navParams());
        nav.addView(navItem("◷", "ACTIVITY", true, -1), navParams());
        nav.addView(navItem("▦", "CAPABILITIES", false, DashboardActivity.PAGE_CAPABILITIES), navParams());
        nav.addView(navItem("⚙", "SETTINGS", false, DashboardActivity.PAGE_SETTINGS), navParams());
        return nav;
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private TextView navItem(String icon, String label, boolean active, int page) {
        TextView item = text(icon + "\n" + label, 10, Typeface.BOLD, active ? GREEN : MUTED);
        item.setGravity(Gravity.CENTER);
        item.setLetterSpacing(.055f);
        item.setLineSpacing(0f, 1.18f);
        if (active) {
            item.setBackground(PickPicoTheme.control(theme, dp(14), GREEN, true));
        } else {
            item.setOnClickListener(v -> openDashboard(page));
        }
        return item;
    }

    private void openDashboard(int page) {
        Intent intent = new Intent(this, DashboardActivity.class)
                .putExtra(DashboardActivity.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void confirmClearInbox() {
        if (AgentInboxStore.count(this) == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear Agent Activity?")
                .setMessage("This removes every saved request and message from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    AgentInboxStore.clear(this);
                    renderItems();
                    Toast.makeText(this, "Agent Activity cleared", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), dp(14), dp(24), dp(4));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        titles.addView(text("Activity", 27, Typeface.BOLD, TEXT));
        TextView meta = text("AGENT ↔ HUMAN HISTORY", 10, Typeface.BOLD, BLUE);
        meta.setLetterSpacing(.10f);
        meta.setPadding(0, dp(4), 0, 0);
        titles.addView(meta);

        topStatusDot = text("● ACTIVE", 10, Typeface.BOLD, GREEN);
        topStatusDot.setGravity(Gravity.CENTER);
        topStatusDot.setLetterSpacing(.035f);
        topStatusDot.setBackground(PickPicoTheme.control(theme, dp(14), GREEN, false));
        bar.addView(topStatusDot, new LinearLayout.LayoutParams(dp(84), dp(34)));
        return bar;
    }

    private void refreshTopStatus() {
        if (topStatusDot == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        boolean running = McpNodeService.isNodeRunning();
        String relayUrl = prefs.getString(McpNodeService.KEY_RELAY_BASE_URL, "");
        String relayStatus = prefs.getString(McpNodeService.KEY_RELAY_STATUS, "disabled");
        boolean relayConfigured = !TextUtils.isEmpty(relayUrl);
        boolean relayConnected = "connected".equals(relayStatus);
        int color = !running ? RED : relayConfigured && !relayConnected ? Color.rgb(246, 169, 69) : GREEN;
        topStatusDot.setText(running ? "● ACTIVE" : "● STOPPED");
        topStatusDot.setTextColor(resolveThemeTextColor(color));
        topStatusDot.setBackground(PickPicoTheme.control(theme, dp(14), color, false));
        topStatusDot.setContentDescription(!running
                ? "Node stopped"
                : relayConfigured && !relayConnected ? "Node local only; relay disconnected" : "Node ready");
    }

    private void renderItems() {
        if (itemsContainer == null) return;
        renderPending();
        itemsContainer.removeAllViews();
        JSONArray items = AgentInboxStore.list(this);
        if (inboxCount != null) inboxCount.setText(items.length() + (items.length() == 1 ? " EVENT" : " EVENTS"));
        if (clearAction != null) {
            clearAction.setEnabled(items.length() > 0);
            clearAction.setAlpha(items.length() > 0 ? 1f : 0.35f);
        }

        String highlightedId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (items.length() == 0) {
            LinearLayout empty = glassCard(false);
            empty.addView(text("No activity yet", 15, Typeface.BOLD, TEXT));
            TextView detail = text("Agent requests, notifications, and human-help handoffs will appear here.", 12, Typeface.NORMAL, MUTED);
            detail.setPadding(0, dp(6), 0, 0);
            empty.addView(detail);
            itemsContainer.addView(empty);
            return;
        }

        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;

            boolean highlighted = item.optString("id").equals(highlightedId);
            LinearLayout card = glassCard(highlighted);

            String source = item.optString("source", "agent");
            String createdAt = compactTimestamp(item.optString("createdAt", ""));
            TextView meta = text(
                    source.toUpperCase() + (createdAt.isEmpty() ? "" : "  ·  " + createdAt),
                    10,
                    Typeface.BOLD,
                    highlighted ? PickPicoTheme.accentB(theme) : DIM);
            meta.setLetterSpacing(.04f);
            card.addView(meta);

            String itemTitle = item.optString("title", "");
            if (!itemTitle.isEmpty()) {
                TextView heading = text(itemTitle, 16, Typeface.BOLD, TEXT);
                heading.setPadding(0, dp(7), 0, dp(2));
                card.addView(heading);
            }

            String body = item.optString("body", "");
            TextView bodyView = text(body, 14, Typeface.NORMAL, MUTED);
            bodyView.setTextIsSelectable(true);
            bodyView.setLineSpacing(0f, 1.08f);
            bodyView.setOnLongClickListener(v -> {
                copyText(body);
                return true;
            });
            bodyView.setPadding(0, dp(6), 0, dp(8));
            card.addView(bodyView);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView copy = actionButton("COPY", BLUE);
            copy.setOnClickListener(v -> copyText(body));
            actions.addView(copy, new LinearLayout.LayoutParams(dp(72), dp(34)));

            String openableUrl = extractUrl(body);
            if (openableUrl != null) {
                TextView open = actionButton("OPEN", GREEN);
                open.setOnClickListener(v -> openUrl(openableUrl));
                LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(dp(72), dp(34));
                openParams.leftMargin = dp(8);
                actions.addView(open, openParams);
            }
            card.addView(actions);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) params.topMargin = dp(10);
            itemsContainer.addView(card, params);
        }
    }

    private void renderPending() {
        if (pendingContainer == null) return;
        pendingContainer.removeAllViews();
        try {
            JSONObject pending = HumanHelpStore.latestWaiting(this);
            if (pending == null) {
                pendingContainer.setVisibility(View.GONE);
                return;
            }
            pendingContainer.setVisibility(View.VISIBLE);
            TextView label = text("NEEDS RESPONSE", 10, Typeface.BOLD, DIM);
            label.setLetterSpacing(.12f);
            label.setPadding(dp(2), dp(20), 0, dp(10));
            pendingContainer.addView(label);

            LinearLayout card = glassCard(true);
            card.setOnClickListener(v -> {
                String requestId = pending.optString("requestId", "");
                if (TextUtils.isEmpty(requestId)) return;
                startActivity(new Intent(this, HumanHelpActivity.class)
                        .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, requestId));
            });
            TextView state = text(
                    "approval".equals(pending.optString("requestType", "help")) ? "APPROVAL NEEDED" : "HUMAN HELP",
                    11,
                    Typeface.BOLD,
                    BLUE);
            state.setLetterSpacing(.08f);
            card.addView(state);
            TextView title = text(pending.optString("title", "Agent is waiting for you"), 16, Typeface.BOLD, TEXT);
            title.setPadding(0, dp(8), 0, dp(4));
            card.addView(title);
            TextView detail = text("Open to respond and let the Agent continue.", 12, Typeface.NORMAL, MUTED);
            card.addView(detail);
            pendingContainer.addView(card);
        } catch (Exception ignored) {
            pendingContainer.setVisibility(View.GONE);
        }
    }

    private LinearLayout glassCard(boolean accented) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(PickPicoTheme.card(theme, dp(22), accented));
        card.setElevation(dp(6));
        return card;
    }

    private TextView actionButton(String label, int accent) {
        TextView button = text(label, 10, Typeface.BOLD, accent == RED ? RED : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(.05f);
        button.setBackground(PickPicoTheme.control(theme, dp(11), accent, accent != RED));
        return button;
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PickPico Agent message", value));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private static String compactTimestamp(String value) {
        if (value == null || value.isEmpty()) return "";
        int t = value.indexOf('T');
        if (t >= 0 && value.length() >= t + 6) {
            return value.substring(t + 1, t + 6);
        }
        return value;
    }

    private void openUrl(String value) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
        } catch (Throwable error) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show();
        }
    }

    private static String extractUrl(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            int whitespace = trimmed.indexOf(' ');
            return whitespace < 0 ? trimmed : trimmed.substring(0, whitespace);
        }
        return null;
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(resolveThemeTextColor(color));
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int resolveThemeTextColor(int roleColor) {
        if (!PickPicoTheme.isLightBackground(theme)) return roleColor;
        if (roleColor == TEXT) return Color.rgb(22, 26, 29);
        if (roleColor == MUTED) return Color.rgb(73, 82, 90);
        if (roleColor == DIM) return Color.rgb(108, 117, 125);
        if (roleColor == GREEN) return Color.rgb(18, 121, 67);
        if (roleColor == RED) return Color.rgb(194, 39, 49);
        if (roleColor == BLUE) return Color.rgb(24, 101, 174);
        return roleColor;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
