package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView endpointView;
    private TextView tokenView;
    private TextView recentView;
    private Button startButton;
    private Button stopButton;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("MCPocket", 28, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("Android as a portable MCP execution node", 15, Typeface.NORMAL);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        statusView = valueView();
        endpointView = valueView();
        tokenView = valueView();
        recentView = valueView();

        root.addView(label("STATUS"));
        root.addView(statusView);
        root.addView(label("MCP ENDPOINT"));
        root.addView(endpointView);
        root.addView(label("BEARER TOKEN"));
        root.addView(tokenView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(12));

        startButton = new Button(this);
        startButton.setText(R.string.start_node);
        startButton.setOnClickListener(v -> startNode());
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        stopButton = new Button(this);
        stopButton.setText(R.string.stop_node);
        stopButton.setOnClickListener(v -> stopNode());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        stopParams.setMarginStart(dp(8));
        buttons.addView(stopButton, stopParams);
        root.addView(buttons);

        Button copyButton = new Button(this);
        copyButton.setText(R.string.copy_connection_json);
        copyButton.setOnClickListener(v -> copyConnection());
        root.addView(copyButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("RECENT ACTION"));
        root.addView(recentView);

        TextView note = text(
                "This build exposes only explicit phone capabilities. phone_status is read-only; " +
                        "phone_echo is observable and allowlisted. No arbitrary shell is exposed yet.",
                13,
                Typeface.NORMAL);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void startNode() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_START);
        intent.putExtra(McpNodeService.EXTRA_TOKEN, generateToken());
        startForegroundService(intent);
        Toast.makeText(this, "Starting MCP node…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 400L);
    }

    private void stopNode() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_STOP);
        startService(intent);
        handler.postDelayed(this::refreshStatus, 250L);
    }

    private void copyConnection() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        String endpoint = prefs.getString(McpNodeService.KEY_ENDPOINT, "");
        String token = prefs.getString(McpNodeService.KEY_TOKEN, "");
        if (TextUtils.isEmpty(endpoint) || TextUtils.isEmpty(token)) {
            Toast.makeText(this, "Start the node first", Toast.LENGTH_SHORT).show();
            return;
        }
        String json = "{\n" +
                "  \"url\": \"" + endpoint + "\",\n" +
                "  \"headers\": {\n" +
                "    \"Authorization\": \"Bearer " + token + "\"\n" +
                "  }\n" +
                "}";
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("MCPocket connection", json));
        Toast.makeText(this, "Connection JSON copied", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        boolean running = McpNodeService.isNodeRunning();
        if (!running && prefs.getBoolean(McpNodeService.KEY_RUNNING, false)) {
            prefs.edit()
                    .putBoolean(McpNodeService.KEY_RUNNING, false)
                    .putString(McpNodeService.KEY_ENDPOINT, "")
                    .putString(McpNodeService.KEY_TOKEN, "")
                    .apply();
        }
        String error = prefs.getString(McpNodeService.KEY_ERROR, "");
        statusView.setText(running ? "RUNNING" : (TextUtils.isEmpty(error) ? "STOPPED" : "ERROR: " + error));
        statusView.setTextColor(running ? Color.rgb(0, 120, 60) : Color.rgb(170, 35, 35));
        endpointView.setText(orDash(prefs.getString(McpNodeService.KEY_ENDPOINT, "")));
        tokenView.setText(orDash(prefs.getString(McpNodeService.KEY_TOKEN, "")));
        String recent = prefs.getString(McpNodeService.KEY_RECENT, "No tool calls yet");
        long calls = prefs.getLong(McpNodeService.KEY_CALL_COUNT, 0L);
        recentView.setText(getString(R.string.tool_calls_format, recent, calls));
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private TextView label(String value) {
        TextView view = text(value, 12, Typeface.BOLD);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(14), 0, dp(4));
        return view;
    }

    private TextView valueView() {
        TextView view = text("—", 16, Typeface.NORMAL);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundColor(Color.rgb(238, 240, 243));
        return view;
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

    private static String orDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }
}
