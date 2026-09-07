package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATION = 100;
    private static final int REQUEST_NODE_MEDIA = 101;
    private static final int REQUEST_LOCATION = 102;
    private static final int REQUEST_SCREEN_CAPTURE = 103;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView endpointView;
    private TextView remoteEndpointView;
    private TextView relayStatusView;
    private TextView tokenView;
    private TextView recentView;
    private TextView remoteLockView;
    private TextView notificationAccessView;
    private TextView accessibilityAccessView;
    private TextView screenCaptureAccessView;
    private TextView locationAccessView;
    private TextView appVersionView;
    private TextView updateStatusView;
    private Button startButton;
    private Button stopButton;
    private Button enableRemoteLockButton;
    private Button enableNotificationAccessButton;
    private Button enableAccessibilityButton;
    private Button screenCaptureButton;
    private Button enableLocationButton;
    private Button agentInboxButton;
    private Button updateButton;
    private EditText relayUrlInput;
    private Switch hyperModeSwitch;
    private RadioGroup approvalModeGroup;

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
        refreshMediaForegroundTypesIfRunning();
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

        TextView title = text("PickPico", 28, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("Real-world Mobile Agent Node", 15, Typeface.NORMAL);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        statusView = valueView();
        endpointView = valueView();
        remoteEndpointView = valueView();
        relayStatusView = valueView();
        tokenView = valueView();
        recentView = valueView();
        remoteLockView = valueView();
        notificationAccessView = valueView();
        accessibilityAccessView = valueView();
        screenCaptureAccessView = valueView();
        locationAccessView = valueView();
        appVersionView = valueView();
        updateStatusView = valueView();

        root.addView(label("STATUS"));
        root.addView(statusView);

        root.addView(label("AGENT CONTROL"));
        TextView controlNote = text(
                "Hyper Mode controls advanced Android capabilities and lets urgent Agent handoffs request lock-screen dismissal. Secure PIN/password/biometric locks remain Android-owned. Approval Mode controls whether the Agent must ask before side effects.",
                13,
                Typeface.NORMAL);
        controlNote.setTextColor(Color.DKGRAY);
        controlNote.setPadding(0, 0, 0, dp(8));
        root.addView(controlNote);

        hyperModeSwitch = new Switch(this);
        hyperModeSwitch.setText("⚡ Hyper Mode");
        hyperModeSwitch.setTextSize(17);
        hyperModeSwitch.setChecked(McpocketPolicySettings.isHyperModeEnabled(this));
        hyperModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            McpocketPolicySettings.setHyperModeEnabled(this, checked);
            boolean openedUnlockSetup = checked && AgentAttention.requestHyperUnlockAccessIfNeeded(this);
            Toast.makeText(
                    this,
                    openedUnlockSetup
                            ? "Allow full-screen alerts so Hyper Mode can dismiss the lock screen."
                            : checked
                            ? "Hyper Mode enabled. Android requires the phone owner to finish Special Access setup locally."
                            : "Hyper Mode disabled. Android access may remain granted, but Hyper commands are hidden from the Agent.",
                    Toast.LENGTH_LONG).show();
            if (checked && !McpAccessibilityService.hasAccess(this)) {
                openAppDetailsSettings();
            }
            refreshStatus();
        });
        root.addView(hyperModeSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        TextView approvalLabel = label("APPROVAL MODE");
        approvalLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(approvalLabel);
        approvalModeGroup = new RadioGroup(this);
        approvalModeGroup.setOrientation(RadioGroup.VERTICAL);
        addApprovalOption(approvalModeGroup, "詢問我", McpocketPolicySettings.APPROVAL_ASK);
        addApprovalOption(approvalModeGroup, "代我核准", McpocketPolicySettings.APPROVAL_AUTO);
        addApprovalOption(approvalModeGroup, "YOLO Mode", McpocketPolicySettings.APPROVAL_YOLO);
        approvalModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View selected = group.findViewById(checkedId);
            if (selected != null && selected.getTag() instanceof String) {
                McpocketPolicySettings.setApprovalMode(this, (String) selected.getTag());
            }
        });
        root.addView(approvalModeGroup);

        root.addView(label("LOCAL MCP ENDPOINT"));
        root.addView(endpointView);
        root.addView(label("REMOTE RELAY"));
        relayUrlInput = new EditText(this);
        relayUrlInput.setSingleLine(true);
        relayUrlInput.setHint("https://your-relay.workers.dev");
        relayUrlInput.setText(getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE)
                .getString(McpNodeService.KEY_RELAY_BASE_URL, ""));
        root.addView(relayUrlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button saveRelayButton = new Button(this);
        saveRelayButton.setText("SAVE RELAY URL");
        saveRelayButton.setOnClickListener(v -> saveRelayUrl());
        root.addView(saveRelayButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("RELAY STATUS"));
        root.addView(relayStatusView);
        root.addView(label("REMOTE MCP ENDPOINT"));
        root.addView(remoteEndpointView);
        root.addView(label("BEARER TOKEN"));
        root.addView(tokenView);

        root.addView(label("REMOTE LOCK"));
        root.addView(remoteLockView);

        enableRemoteLockButton = new Button(this);
        enableRemoteLockButton.setText(R.string.enable_remote_lock);
        enableRemoteLockButton.setOnClickListener(v -> requestDeviceAdmin());
        root.addView(enableRemoteLockButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("HYPER UI CONTROL / ACCESSIBILITY"));
        root.addView(accessibilityAccessView);

        TextView accessibilitySetupNote = text(
                "Human setup required. Android may block sideloaded apps from Accessibility until the phone owner explicitly allows Restricted settings. " +
                        "PickPico can open the correct system pages, but it cannot approve these security gates for itself.\n\n" +
                        "Step 1: Open PickPico App info. If Android shows the option, tap the top-right menu (⋮) → Allow restricted settings, then return here.\n" +
                        "Step 2: Open Accessibility settings and enable PickPico Hyper UI Control. Store-installed builds may not require Step 1.",
                13,
                Typeface.NORMAL);
        accessibilitySetupNote.setTextColor(Color.DKGRAY);
        accessibilitySetupNote.setPadding(0, 0, 0, dp(8));
        root.addView(accessibilitySetupNote);

        Button openAppInfoButton = new Button(this);
        openAppInfoButton.setText("STEP 1 · OPEN APP INFO");
        openAppInfoButton.setOnClickListener(v -> openAppDetailsSettings());
        root.addView(openAppInfoButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        enableAccessibilityButton = new Button(this);
        enableAccessibilityButton.setText("STEP 2 · ENABLE ACCESSIBILITY UI CONTROL");
        enableAccessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enableAccessibilityButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("HYPER SCREEN CAPTURE / MEDIAPROJECTION"));
        root.addView(screenCaptureAccessView);
        TextView screenCaptureNote = text(
                "Android requires the phone owner to confirm screen sharing. PickPico can use screenshots only while this user-authorized session is active.",
                13,
                Typeface.NORMAL);
        screenCaptureNote.setTextColor(Color.DKGRAY);
        screenCaptureNote.setPadding(0, 0, 0, dp(6));
        root.addView(screenCaptureNote);
        screenCaptureButton = new Button(this);
        screenCaptureButton.setOnClickListener(v -> toggleScreenCapture());
        root.addView(screenCaptureButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("NOTIFICATION ACCESS"));
        root.addView(notificationAccessView);
        enableNotificationAccessButton = new Button(this);
        enableNotificationAccessButton.setText("ENABLE NOTIFICATION ACCESS");
        enableNotificationAccessButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(enableNotificationAccessButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("LOCATION"));
        root.addView(locationAccessView);
        enableLocationButton = new Button(this);
        enableLocationButton.setText("ENABLE LOCATION");
        enableLocationButton.setOnClickListener(v -> requestLocationPermission());
        root.addView(enableLocationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("AGENT INBOX"));
        agentInboxButton = new Button(this);
        agentInboxButton.setOnClickListener(v ->
                startActivity(new Intent(this, AgentInboxActivity.class)));
        root.addView(agentInboxButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        root.addView(label("APP VERSION"));
        root.addView(appVersionView);
        root.addView(label("SELF UPDATE"));
        root.addView(updateStatusView);

        updateButton = new Button(this);
        updateButton.setText("UPDATE PICKPICO");
        updateButton.setOnClickListener(v -> installStagedUpdate());
        root.addView(updateButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

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
                        "phone_exec remains a restricted compatibility tool; exec_command runs Linux shell " +
                        "commands inside the PickPico app sandbox.",
                13,
                Typeface.NORMAL);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void startNode() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_NODE_MEDIA);
            return;
        }
        startNodeService();
    }

    private void startNodeService() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_START);
        intent.putExtra(McpNodeService.EXTRA_TOKEN, generateToken());
        intent.putExtra(McpNodeService.EXTRA_ENABLE_MEDIA_FGS, true);
        startForegroundService(intent);
        Toast.makeText(this, "Starting MCP node…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 400L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NODE_MEDIA && !McpNodeService.isNodeRunning()) {
            startNodeService();
        } else if (requestCode == REQUEST_LOCATION && McpNodeService.isNodeRunning()) {
            Toast.makeText(this, "Restart the node to apply background location access", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not authorized", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Starting Hyper screen capture session…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 500L);
    }

    private void toggleScreenCapture() {
        if (ScreenCaptureService.isActive()) {
            Intent stop = new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            handler.postDelayed(this::refreshStatus, 250L);
            return;
        }
        if (!McpocketPolicySettings.isHyperModeEnabled(this)) {
            Toast.makeText(this, "Turn on Hyper Mode first", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "MediaProjection is unavailable on this device", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    private void stopNode() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_STOP);
        startService(intent);
        handler.postDelayed(this::refreshStatus, 250L);
    }

    private void copyConnection() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        String remoteEndpoint = prefs.getString(McpNodeService.KEY_REMOTE_ENDPOINT, "");
        String localEndpoint = prefs.getString(McpNodeService.KEY_ENDPOINT, "");
        String endpoint = TextUtils.isEmpty(remoteEndpoint) ? localEndpoint : remoteEndpoint;
        String token = prefs.getString(McpNodeService.KEY_TOKEN, "");
        if (TextUtils.isEmpty(endpoint) || TextUtils.isEmpty(token)) {
            Toast.makeText(this, "Start the node first", Toast.LENGTH_SHORT).show();
            return;
        }
        String json;
        if (!TextUtils.isEmpty(remoteEndpoint)) {
            json = "{\n" +
                    "  \"url\": \"" + endpoint + "\",\n" +
                    "  \"authentication\": \"none\"\n" +
                    "}";
        } else {
            json = "{\n" +
                    "  \"url\": \"" + endpoint + "\",\n" +
                    "  \"headers\": {\n" +
                    "    \"Authorization\": \"Bearer " + token + "\"\n" +
                    "  }\n" +
                    "}";
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PickPico connection", json));
        Toast.makeText(this, "Connection JSON copied", Toast.LENGTH_SHORT).show();
    }

    private void saveRelayUrl() {
        String value = relayUrlInput.getText().toString().trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.isEmpty() && !value.startsWith("https://") && !value.startsWith("http://")) {
            Toast.makeText(this, "Relay URL must start with https://", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE).edit()
                .putString(McpNodeService.KEY_RELAY_BASE_URL, value)
                .apply();
        relayUrlInput.setText(value);
        Toast.makeText(this,
                McpNodeService.isNodeRunning() ? "Relay saved. Restart node to apply." : "Relay saved.",
                Toast.LENGTH_SHORT).show();
    }

    private void requestDeviceAdmin() {
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            Toast.makeText(this, "Remote lock is already enabled", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "PickPico can lock this phone when you explicitly request it through the authenticated node.");
        startActivity(intent);
    }

    private void openAppDetailsSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void installStagedUpdate() {
        try {
            JSONObject state = SelfUpdateManager.installStagedFromForeground(this);
            String status = state.optString("status", "staging");
            Toast.makeText(this, "PickPico update: " + status, Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::refreshStatus, 250L);
        } catch (RuntimeException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        boolean running = McpNodeService.isNodeRunning();
        if (running && !prefs.getBoolean(McpNodeService.KEY_MEDIA_FOREGROUND_REQUESTED, false)) {
            refreshMediaForegroundTypesIfRunning();
        }
        if (!running && prefs.getBoolean(McpNodeService.KEY_RUNNING, false)) {
            prefs.edit()
                    .putBoolean(McpNodeService.KEY_RUNNING, false)
                    .putString(McpNodeService.KEY_ENDPOINT, "")
                    .putString(McpNodeService.KEY_REMOTE_ENDPOINT, "")
                    .putString(McpNodeService.KEY_RELAY_STATUS, "stopped")
                    .apply();
        }
        String error = prefs.getString(McpNodeService.KEY_ERROR, "");
        statusView.setText(running ? "RUNNING" : (TextUtils.isEmpty(error) ? "STOPPED" : "ERROR: " + error));
        statusView.setTextColor(running ? Color.rgb(0, 120, 60) : Color.rgb(170, 35, 35));
        endpointView.setText(orDash(prefs.getString(McpNodeService.KEY_ENDPOINT, "")));
        remoteEndpointView.setText(orDash(prefs.getString(McpNodeService.KEY_REMOTE_ENDPOINT, "")));
        String relayBaseUrl = prefs.getString(McpNodeService.KEY_RELAY_BASE_URL, "");
        if (relayUrlInput != null
                && !relayUrlInput.hasFocus()
                && !TextUtils.equals(relayUrlInput.getText().toString(), relayBaseUrl)) {
            relayUrlInput.setText(relayBaseUrl);
        }
        String relayStatus = prefs.getString(McpNodeService.KEY_RELAY_STATUS, "disabled");
        relayStatusView.setText(relayStatus.toUpperCase());
        relayStatusView.setTextColor("connected".equals(relayStatus)
                ? Color.rgb(0, 120, 60)
                : Color.rgb(150, 90, 0));
        tokenView.setText(orDash(prefs.getString(McpNodeService.KEY_TOKEN, "")));
        String recent = prefs.getString(McpNodeService.KEY_RECENT, "No tool calls yet");
        long calls = prefs.getLong(McpNodeService.KEY_CALL_COUNT, 0L);
        recentView.setText(getString(R.string.tool_calls_format, recent, calls));
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        boolean remoteLockEnabled = policy != null && policy.isAdminActive(admin);
        remoteLockView.setText(remoteLockEnabled ? "ENABLED" : "NOT ENABLED");
        remoteLockView.setTextColor(remoteLockEnabled ? Color.rgb(0, 120, 60) : Color.rgb(170, 35, 35));
        enableRemoteLockButton.setEnabled(!remoteLockEnabled);

        boolean notificationAccess = McpNotificationListenerService.hasAccess(this);
        notificationAccessView.setText(notificationAccess ? "ENABLED" : "NOT ENABLED");
        notificationAccessView.setTextColor(notificationAccess
                ? Color.rgb(0, 120, 60)
                : Color.rgb(170, 35, 35));
        enableNotificationAccessButton.setEnabled(!notificationAccess);

        boolean accessibilityAccess = McpAccessibilityService.hasAccess(this);
        accessibilityAccessView.setText(accessibilityAccess ? "ENABLED" : "NOT ENABLED");
        accessibilityAccessView.setTextColor(accessibilityAccess
                ? Color.rgb(0, 120, 60)
                : Color.rgb(170, 35, 35));
        enableAccessibilityButton.setEnabled(!accessibilityAccess);

        boolean screenCaptureActive = ScreenCaptureService.isActive();
        screenCaptureAccessView.setText(screenCaptureActive ? "ACTIVE" : "NOT ACTIVE");
        screenCaptureAccessView.setTextColor(screenCaptureActive
                ? Color.rgb(0, 120, 60)
                : Color.rgb(150, 90, 0));
        screenCaptureButton.setText(screenCaptureActive
                ? "STOP SCREEN CAPTURE SESSION"
                : "START SCREEN CAPTURE SESSION");

        boolean locationAccess = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        locationAccessView.setText(locationAccess ? "ENABLED" : "NOT ENABLED");
        locationAccessView.setTextColor(locationAccess
                ? Color.rgb(0, 120, 60)
                : Color.rgb(170, 35, 35));
        enableLocationButton.setEnabled(!locationAccess);

        int inboxCount = AgentInboxStore.count(this);
        agentInboxButton.setText("OPEN AGENT INBOX (" + inboxCount + ")");

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);

        JSONObject updateState = SelfUpdateManager.status(this, 0L);
        String currentVersion = updateState.optString("currentVersionName", "unknown");
        long currentVersionCode = updateState.optLong("currentVersionCode", -1L);
        appVersionView.setText(currentVersion + " (" + currentVersionCode + ")");

        String rawUpdateStatus = updateState.optString("status", "idle");
        String updateStatus = rawUpdateStatus;
        String candidateVersion = updateState.optString("candidateVersionName", "");
        if (!TextUtils.isEmpty(candidateVersion)) {
            updateStatus += " → " + candidateVersion;
        }
        if (updateState.has("bytesDownloaded")) {
            updateStatus += "\n" + updateState.optLong("bytesDownloaded", 0L) + " bytes staged";
        }
        updateStatusView.setText(updateStatus);

        boolean hasCandidate = SelfUpdateManager.hasInstallableCandidate(this);
        updateButton.setEnabled(hasCandidate && !"downloading".equals(rawUpdateStatus));
        updateButton.setText(hasCandidate && !TextUtils.isEmpty(candidateVersion)
                ? "UPDATE TO " + candidateVersion
                : "UPDATE PICKPICO");

        if ("pending_user_action".equals(rawUpdateStatus)
                && updateState.optBoolean("startedByUser", false)) {
            Intent confirmation = SelfUpdateManager.takePendingConfirmationIntent();
            if (confirmation != null) {
                try {
                    startActivity(confirmation);
                } catch (Throwable launchError) {
                    Toast.makeText(
                            this,
                            "Tap the PickPico update notification to continue",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void refreshMediaForegroundTypesIfRunning() {
        if (!McpNodeService.isNodeRunning()) {
            return;
        }
        Intent intent = new Intent(this, McpNodeService.class)
                .setAction(McpNodeService.ACTION_REFRESH_MEDIA_FOREGROUND);
        try {
            startService(intent);
        } catch (Throwable ignored) {
            // The base MCP node remains usable even if Android declines a foreground-type refresh.
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        }
    }

    private void requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location is already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_LOCATION);
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

    private void addApprovalOption(RadioGroup group, String label, String mode) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setText(label);
        option.setTextSize(16);
        option.setTag(mode);
        option.setChecked(mode.equals(McpocketPolicySettings.approvalMode(this)));
        group.addView(option, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                dp(46)));
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
