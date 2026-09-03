package com.mcpocket.poc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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

/** Product-facing Agent Inbox using the same glass language as DashboardActivity. */
public final class AgentInboxActivity extends Activity {
    static final String EXTRA_ENTRY_ID = "entryId";

    private static final int BG = Color.rgb(6, 8, 10);
    private static final int TEXT = Color.rgb(242, 246, 248);
    private static final int MUTED = Color.rgb(165, 176, 187);
    private static final int DIM = Color.rgb(105, 116, 128);
    private static final int GREEN = Color.rgb(61, 214, 129);
    private static final int BLUE = Color.rgb(92, 177, 255);
    private static final int RED = Color.rgb(255, 91, 99);

    private LinearLayout itemsContainer;
    private TextView inboxCount;
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
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout intro = glassCard(true);
        LinearLayout introHeader = new LinearLayout(this);
        introHeader.setOrientation(LinearLayout.HORIZONTAL);
        introHeader.setGravity(Gravity.CENTER_VERTICAL);
        intro.addView(introHeader);

        LinearLayout introCopy = new LinearLayout(this);
        introCopy.setOrientation(LinearLayout.VERTICAL);
        introHeader.addView(introCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView kicker = text("AGENT INBOX", 10, Typeface.BOLD, PickPicoTheme.accentB(theme));
        kicker.setLetterSpacing(.08f);
        introCopy.addView(kicker);
        TextView subtitle = text("Agent messages stay here after their Android notification disappears.", 13, Typeface.NORMAL, MUTED);
        subtitle.setPadding(0, dp(6), dp(12), 0);
        introCopy.addView(subtitle);

        inboxCount = text("0", 24, Typeface.BOLD, TEXT);
        inboxCount.setGravity(Gravity.CENTER);
        inboxCount.setBackground(PickPicoTheme.control(theme, dp(12), PickPicoTheme.accentA(theme), true));
        introHeader.addView(inboxCount, new LinearLayout.LayoutParams(dp(56), dp(44)));
        root.addView(intro);

        TextView clear = actionButton("CLEAR INBOX", RED);
        clear.setOnClickListener(v -> {
            AgentInboxStore.clear(this);
            renderItems();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        clearParams.topMargin = dp(12);
        root.addView(clear, clearParams);

        TextView section = text("MESSAGES", 10, Typeface.BOLD, MUTED);
        section.setLetterSpacing(.08f);
        section.setPadding(dp(2), dp(22), 0, dp(8));
        root.addView(section);

        itemsContainer = new LinearLayout(this);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemsContainer);
        return stage;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(16), 0);
        bar.setBackground(PickPicoTheme.strongGlass(theme, dp(18)));

        TextView back = text("‹", 34, Typeface.NORMAL, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        titles.addView(text("Agent Inbox", 22, Typeface.BOLD, TEXT));
        TextView meta = text("HUMAN ↔ AGENT HANDOFF", 9, Typeface.BOLD, DIM);
        meta.setLetterSpacing(.08f);
        titles.addView(meta);

        TextView dot = text("●", 13, Typeface.BOLD, GREEN);
        dot.setGravity(Gravity.CENTER);
        bar.addView(dot, new LinearLayout.LayoutParams(dp(28), dp(48)));
        return bar;
    }

    private void renderItems() {
        if (itemsContainer == null) return;
        itemsContainer.removeAllViews();
        JSONArray items = AgentInboxStore.list(this);
        if (inboxCount != null) inboxCount.setText(String.valueOf(items.length()));

        String highlightedId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (items.length() == 0) {
            LinearLayout empty = glassCard(false);
            empty.addView(text("No Agent messages yet", 15, Typeface.BOLD, TEXT));
            TextView detail = text("Notifications and human-help messages retained by PickPico will appear here.", 12, Typeface.NORMAL, MUTED);
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
            String createdAt = item.optString("createdAt", "");
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
            TextView bodyView = text(body, 14, Typeface.NORMAL, TEXT);
            bodyView.setTextIsSelectable(true);
            bodyView.setLineSpacing(0f, 1.08f);
            bodyView.setOnLongClickListener(v -> {
                copyText(body);
                return true;
            });
            bodyView.setPadding(0, dp(7), 0, dp(11));
            card.addView(bodyView);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView copy = actionButton("COPY", BLUE);
            copy.setOnClickListener(v -> copyText(body));
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(40), 1f));

            String openableUrl = extractUrl(body);
            if (openableUrl != null) {
                TextView open = actionButton("OPEN", GREEN);
                open.setOnClickListener(v -> openUrl(openableUrl));
                LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
                openParams.leftMargin = dp(8);
                actions.addView(open, openParams);
            }
            card.addView(actions);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) params.topMargin = dp(12);
            itemsContainer.addView(card, params);
        }
    }

    private LinearLayout glassCard(boolean accented) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(PickPicoTheme.card(theme, dp(18), accented));
        card.setElevation(dp(10));
        return card;
    }

    private TextView actionButton(String label, int accent) {
        TextView button = text(label, 10, Typeface.BOLD, accent == RED ? RED : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(.05f);
        button.setBackground(PickPicoTheme.control(theme, dp(12), accent, true));
        return button;
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PickPico Agent message", value));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
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
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
