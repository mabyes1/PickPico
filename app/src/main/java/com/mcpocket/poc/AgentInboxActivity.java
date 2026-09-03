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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AgentInboxActivity extends Activity {
    static final String EXTRA_ENTRY_ID = "entryId";

    private LinearLayout itemsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Agent Inbox");
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderItems();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("Agent Inbox", 26, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("Messages pushed by the Agent stay here even after the Android notification is gone.", 14, Typeface.NORMAL);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        Button clear = new Button(this);
        clear.setText("CLEAR INBOX");
        clear.setOnClickListener(v -> {
            AgentInboxStore.clear(this);
            renderItems();
        });
        root.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        itemsContainer = new LinearLayout(this);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemsContainer);
        return scroll;
    }

    private void renderItems() {
        itemsContainer.removeAllViews();
        JSONArray items = AgentInboxStore.list(this);
        String highlightedId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (items.length() == 0) {
            TextView empty = text("No Agent messages yet.", 16, Typeface.NORMAL);
            empty.setPadding(0, dp(22), 0, 0);
            itemsContainer.addView(empty);
            return;
        }

        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackgroundColor(item.optString("id").equals(highlightedId)
                    ? Color.rgb(230, 242, 255)
                    : Color.rgb(240, 242, 245));

            String source = item.optString("source", "agent");
            String createdAt = item.optString("createdAt", "");
            TextView meta = text(source + (createdAt.isEmpty() ? "" : "  •  " + createdAt), 12, Typeface.NORMAL);
            meta.setTextColor(Color.GRAY);
            card.addView(meta);

            String itemTitle = item.optString("title", "");
            if (!itemTitle.isEmpty()) {
                TextView heading = text(itemTitle, 17, Typeface.BOLD);
                heading.setPadding(0, dp(5), 0, dp(3));
                card.addView(heading);
            }

            String body = item.optString("body", "");
            TextView bodyView = text(body, 16, Typeface.NORMAL);
            bodyView.setTextIsSelectable(true);
            bodyView.setOnLongClickListener(v -> {
                copyText(body);
                return true;
            });
            bodyView.setPadding(0, dp(4), 0, dp(8));
            card.addView(bodyView);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button copy = new Button(this);
            copy.setText("COPY");
            copy.setOnClickListener(v -> copyText(body));
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1f));

            String openableUrl = extractUrl(body);
            if (openableUrl != null) {
                Button open = new Button(this);
                open.setText("OPEN");
                open.setOnClickListener(v -> openUrl(openableUrl));
                LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
                openParams.setMarginStart(dp(8));
                actions.addView(open, openParams);
            }
            card.addView(actions);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(12);
            itemsContainer.addView(card, cardParams);
        }
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MCPocket Agent message", value));
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
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            int whitespace = trimmed.indexOf(' ');
            return whitespace < 0 ? trimmed : trimmed.substring(0, whitespace);
        }
        return null;
    }

    private TextView text(String value, int sp, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
