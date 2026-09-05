package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public final class HumanHelpActivity extends Activity {
    static final String EXTRA_CONFIRM_BLE_VOICE = "confirm_ble_voice";
    static final String EXTRA_BLE_VOICE_PATH = "ble_voice_path";
    static final String EXTRA_BLE_VOICE_TRANSCRIPT = "ble_voice_transcript";
    static final String EXTRA_BLE_VOICE_STT_STATUS = "ble_voice_stt_status";
    static final String EXTRA_OPEN_DETAILS = "open_details";
    private static final int REQUEST_PICK_IMAGE = 201;
    private static final int REQUEST_CAMERA = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;
    private static final int REQUEST_VOICE_REPLY = 204;
    private static final int MAX_IMAGE_EDGE = 1600;
    private String requestId;
    private JSONObject request;
    private PickPicoTheme.State theme;
    private PickPicoTheme.BackgroundView themeBackgroundView;
    private EditText replyInput;
    private TextView countdownStatus;
    private TextView lifecycleStatus;
    private TextView attachmentStatus;
    private LinearLayout actionContainer;
    private LinearLayout detailsContainer;
    private LinearLayout phoneActionsContainer;
    private LinearLayout dockActionsContainer;
    private Button detailsButton;
    private boolean detailsExpanded;
    private boolean phoneControlsOverride;
    private Button uploadButton;
    private Button cameraButton;
    private File pendingCameraFile;
    private Uri pendingCameraUri;
    private long lastActivityRenewElapsedMs;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable lifecycleTicker = new Runnable() {
        @Override
        public void run() {
            if (requestId == null) {
                return;
            }
            reload();
            refreshState();
            if (request != null && "waiting_human".equals(request.optString("status"))) {
                lifecycleHandler.postDelayed(this, 500L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = PickPicoTheme.load(this);
        applyWindowTheme();
        requestId = getIntent().getStringExtra(HumanHelpStore.EXTRA_REQUEST_ID);
        detailsExpanded = getIntent().getBooleanExtra(EXTRA_OPEN_DETAILS, false);
        if (TextUtils.isEmpty(requestId)) {
            finish();
            return;
        }
        reload();
        if (request == null) {
            Toast.makeText(this, "Human-help request not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            HumanHelpStore.renewHumanActivity(this, requestId, "opened", true);
            reload();
        } catch (Exception ignored) {
        }
        setContentView(buildContent());
        if (getIntent().getBooleanExtra(EXTRA_CONFIRM_BLE_VOICE, false)) {
            showBleVoiceConfirmation(
                    getIntent().getStringExtra(EXTRA_BLE_VOICE_PATH),
                    getIntent().getStringExtra(EXTRA_BLE_VOICE_TRANSCRIPT),
                    getIntent().getStringExtra(EXTRA_BLE_VOICE_STT_STATUS));
        }
    }

    private void applyWindowTheme() {
        boolean light = PickPicoTheme.isLightBackground(theme);
        int barColor = theme != null && !theme.gradient ? theme.colorA : PickPicoTheme.BASE_BG;
        getWindow().setStatusBarColor(barColor);
        getWindow().setNavigationBarColor(barColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_DETAILS, false)) {
            setDetailsExpanded(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (requestId != null && actionContainer != null) {
            reload();
            refreshState();
            lifecycleHandler.removeCallbacks(lifecycleTicker);
            lifecycleHandler.post(lifecycleTicker);
        }
    }

    @Override
    protected void onPause() {
        lifecycleHandler.removeCallbacks(lifecycleTicker);
        super.onPause();
    }

    private View buildContent() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(PickPicoTheme.BASE_BG);
        themeBackgroundView = new PickPicoTheme.BackgroundView(this, theme);
        stage.addView(themeBackgroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        stage.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        boolean approvalRequest = "approval".equals(request.optString("requestType", "help"));
        root.addView(buildHeader(approvalRequest));

        LinearLayout pauseCard = glassCard();
        pauseCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        pauseCard.setOrientation(LinearLayout.HORIZONTAL);
        pauseCard.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView pauseIcon = text("Ⅱ", 16, Typeface.BOLD);
        pauseIcon.setTextColor(primaryTextColor());
        pauseIcon.setGravity(android.view.Gravity.CENTER);
        pauseIcon.setBackground(PickPicoTheme.control(
                theme, dp(18), PickPicoTheme.accentA(theme), true));
        pauseCard.addView(pauseIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout pauseCopy = new LinearLayout(this);
        pauseCopy.setOrientation(LinearLayout.VERTICAL);
        TextView pauseTitle = text(approvalRequest ? "AI needs approval" : "AI paused", 15, Typeface.BOLD);
        pauseTitle.setTextColor(primaryTextColor());
        TextView pauseSubtitle = text("Your input is required.", 11, Typeface.NORMAL);
        pauseSubtitle.setTextColor(secondaryTextColor());
        pauseCopy.addView(pauseTitle);
        pauseCopy.addView(pauseSubtitle);
        LinearLayout.LayoutParams pauseCopyParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pauseCopyParams.setMarginStart(dp(9));
        pauseCard.addView(pauseCopy, pauseCopyParams);

        countdownStatus = text("", 13, Typeface.BOLD);
        countdownStatus.setTextColor(primaryTextColor());
        countdownStatus.setGravity(android.view.Gravity.END);
        pauseCard.addView(countdownStatus);
        root.addView(pauseCard, cardParams(10));

        LinearLayout requestCard = glassCard();
        requestCard.setOrientation(LinearLayout.VERTICAL);

        LinearLayout requestTitleRow = new LinearLayout(this);
        requestTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        requestTitleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView taskIcon = text("AI", 12, Typeface.BOLD);
        taskIcon.setTextColor(primaryTextColor());
        taskIcon.setGravity(android.view.Gravity.CENTER);
        taskIcon.setBackground(PickPicoTheme.control(
                theme, dp(10), PickPicoTheme.accentB(theme), true));
        requestTitleRow.addView(taskIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text(request.optString("title", "AI needs your help"), 23, Typeface.BOLD);
        title.setTextColor(primaryTextColor());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        requestTitleRow.addView(title, titleParams);
        requestCard.addView(requestTitleRow);

        TextView instruction = text(request.optString("instruction", ""), 17, Typeface.NORMAL);
        instruction.setTextColor(primaryTextColor());
        instruction.setLineSpacing(0f, 1.12f);
        instruction.setPadding(0, dp(15), 0, 0);
        requestCard.addView(instruction);

        String additionalNotes = request.optString("additionalNotes", "").trim();
        if (!additionalNotes.isEmpty()) {
            TextView notesLabel = text("Additional notes from AI", 13, Typeface.NORMAL);
            notesLabel.setTextColor(secondaryTextColor());
            notesLabel.setPadding(0, dp(16), 0, dp(7));
            requestCard.addView(notesLabel);
            TextView notes = text(additionalNotes, 15, Typeface.NORMAL);
            notes.setTextColor(primaryTextColor());
            notes.setPadding(dp(12), dp(11), dp(12), dp(11));
            notes.setBackground(PickPicoTheme.control(
                    theme, dp(10), PickPicoTheme.accentB(theme), false));
            requestCard.addView(notes);
        }
        root.addView(requestCard, cardParams(14));

        lifecycleStatus = text("", 15, Typeface.BOLD);
        lifecycleStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        lifecycleStatus.setVisibility(View.GONE);
        root.addView(lifecycleStatus);

        detailsContainer = new LinearLayout(this);
        detailsContainer.setOrientation(LinearLayout.VERTICAL);
        detailsContainer.setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
        TextView detailsHeading = text("DETAILS", 11, Typeface.BOLD);
        detailsHeading.setTextColor(PickPicoTheme.accentB(theme));
        detailsHeading.setLetterSpacing(.12f);
        detailsHeading.setPadding(dp(2), dp(12), 0, dp(8));
        detailsContainer.addView(detailsHeading);
        TextView detailsIntro = text("Add context, a note, or an image when the Agent needs more information.", 12, Typeface.NORMAL);
        detailsIntro.setTextColor(secondaryTextColor());
        detailsIntro.setPadding(dp(2), 0, dp(2), dp(10));
        detailsContainer.addView(detailsIntro);
        root.addView(detailsContainer);

        if (request.optBoolean("allowTextReply", true)) {
            LinearLayout replyCard = glassCard();
            replyCard.setOrientation(LinearLayout.VERTICAL);
            TextView replyLabel = cardTitle("Add a note (optional)");
            replyCard.addView(replyLabel);
            replyInput = new EditText(this);
            replyInput.setHint(approvalRequest
                    ? "Optional note for the Agent…"
                    : "Type a message to AI…");
            replyInput.setTextSize(16);
            replyInput.setTextColor(primaryTextColor());
            replyInput.setHintTextColor(PickPicoTheme.dim(theme));
            replyInput.setMinLines(2);
            replyInput.setMaxLines(6);
            replyInput.setPadding(dp(14), dp(12), dp(14), dp(12));
            replyInput.setBackground(PickPicoTheme.control(
                    theme, dp(12), PickPicoTheme.accentA(theme), false));
            replyInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            JSONObject existingResponse = request.optJSONObject("response");
            if (existingResponse != null) {
                replyInput.setText(existingResponse.optString("text", ""));
            }
            replyInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    renewActivity("typing");
                }
            });
            LinearLayout.LayoutParams replyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            replyParams.topMargin = dp(10);
            replyCard.addView(replyInput, replyParams);
            detailsContainer.addView(replyCard, cardParams(14));
        }

        if (request.optBoolean("allowImages", true) && request.optInt("maxImages", 3) > 0) {
            LinearLayout attachmentCard = glassCard();
            attachmentCard.setPadding(dp(12), dp(9), dp(12), dp(10));
            attachmentCard.setOrientation(LinearLayout.VERTICAL);
            TextView attachmentTitle = text("Add an image · optional", 13, Typeface.NORMAL);
            attachmentTitle.setTextColor(secondaryTextColor());
            attachmentCard.addView(attachmentTitle);
            attachmentStatus = text("No images attached", 11, Typeface.NORMAL);
            attachmentStatus.setTextColor(secondaryTextColor());
            attachmentStatus.setPadding(0, dp(3), 0, dp(7));
            attachmentCard.addView(attachmentStatus);

            LinearLayout imageButtons = new LinearLayout(this);
            imageButtons.setOrientation(LinearLayout.HORIZONTAL);

            uploadButton = new Button(this);
            uploadButton.setText("Upload image");
            styleUtilityButton(uploadButton);
            uploadButton.setOnClickListener(v -> pickImage());
            imageButtons.addView(uploadButton, new LinearLayout.LayoutParams(0, dp(42), 1f));

            cameraButton = new Button(this);
            cameraButton.setText("Take photo");
            styleUtilityButton(cameraButton);
            cameraButton.setOnClickListener(v -> requestCamera());
            LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
            cameraParams.setMarginStart(dp(8));
            imageButtons.addView(cameraButton, cameraParams);
            attachmentCard.addView(imageButtons);
            detailsContainer.addView(attachmentCard, cardParams(10));
        }

        actionContainer = new LinearLayout(this);
        actionContainer.setOrientation(LinearLayout.VERTICAL);
        actionContainer.setPadding(dp(18), dp(8), dp(18), dp(10));
        actionContainer.setBackground(PickPicoTheme.strongGlass(theme, dp(22)));
        shell.addView(actionContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rebuildActions();
        refreshState();
        return stage;
    }

    private void rebuildActions() {
        actionContainer.removeAllViews();
        String approveAction = HumanHelpStore.approveAction(request);
        String rejectAction = HumanHelpStore.rejectAction(request);

        phoneActionsContainer = actionRow();
        addCompactActionButton(phoneActionsContainer, "✓", approveAction, 0,
                () -> submit(approveAction));
        addCompactActionButton(phoneActionsContainer, "×", rejectAction, 1,
                () -> submit(rejectAction));
        addCompactActionButton(phoneActionsContainer, "🎙", "Voice", 2,
                this::startVoiceReply);
        detailsButton = addCompactActionButton(phoneActionsContainer, "≡", "Details", 3,
                () -> setDetailsExpanded(!detailsExpanded));
        actionContainer.addView(phoneActionsContainer, actionRowParams());

        dockActionsContainer = new LinearLayout(this);
        dockActionsContainer.setOrientation(LinearLayout.VERTICAL);
        dockActionsContainer.setPadding(dp(10), dp(7), dp(10), dp(7));
        TextView dockTitle = text("●  PICO DOCK CONNECTED", 12, Typeface.BOLD);
        dockTitle.setTextColor(PickPicoTheme.GREEN);
        dockTitle.setGravity(android.view.Gravity.CENTER);
        dockActionsContainer.addView(dockTitle);
        TextView dockMapping = text(
                approveAction + "   ·   " + rejectAction + "   ·   Voice   ·   Details",
                12,
                Typeface.NORMAL);
        dockMapping.setTextColor(secondaryTextColor());
        dockMapping.setGravity(android.view.Gravity.CENTER);
        dockMapping.setPadding(0, dp(5), 0, dp(7));
        dockActionsContainer.addView(dockMapping);
        Button usePhone = new Button(this);
        usePhone.setText("Use phone controls");
        styleUtilityButton(usePhone);
        usePhone.setOnClickListener(v -> {
            phoneControlsOverride = true;
            refreshActionMode();
        });
        dockActionsContainer.addView(usePhone, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        actionContainer.addView(dockActionsContainer);
        setDetailsExpanded(detailsExpanded);
        refreshActionMode();
    }

    private Button addCompactActionButton(
            LinearLayout row,
            String icon,
            String label,
            int index,
            Runnable action) {
        Button button = new Button(this);
        button.setText(icon + "\n" + label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primaryTextColor());
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(4), dp(3), dp(4), dp(3));
        button.setBackground(PickPicoTheme.control(theme, dp(14), actionColor(index), true));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1f);
        if (row.getChildCount() > 0) params.setMarginStart(dp(7));
        row.addView(button, params);
        return button;
    }

    private void setDetailsExpanded(boolean expanded) {
        detailsExpanded = expanded;
        if (detailsContainer != null) {
            detailsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (detailsButton != null) {
            detailsButton.setText((expanded ? "⌃" : "≡") + "\nDetails");
        }
        if (expanded) renewActivity("details");
    }

    private void refreshActionMode() {
        boolean dockConnected = BleButtonBridge.isConnected();
        boolean showDock = dockConnected && !phoneControlsOverride;
        if (phoneActionsContainer != null) {
            phoneActionsContainer.setVisibility(showDock ? View.GONE : View.VISIBLE);
        }
        if (dockActionsContainer != null) {
            dockActionsContainer.setVisibility(showDock ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams actionRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(78));
        params.bottomMargin = dp(9);
        return params;
    }

    private Button addActionButton(
            LinearLayout row,
            String icon,
            String primary,
            String secondary,
            int index,
            Runnable action) {
        Button button = new Button(this);
        button.setText(actionLabel(icon, primary, secondary));
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primaryTextColor());
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(8), dp(5), dp(8), dp(5));
        button.setBackground(PickPicoTheme.control(theme, dp(14), actionColor(index), true));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) {
            params.setMarginStart(dp(9));
        }
        row.addView(button, params);
        return button;
    }

    private CharSequence actionLabel(String icon, String primary, String secondary) {
        String value = icon + "  " + primary + "\n" + secondary;
        SpannableString label = new SpannableString(value);
        int secondaryStart = value.lastIndexOf('\n') + 1;
        label.setSpan(new RelativeSizeSpan(0.72f), secondaryStart, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(secondaryTextColor()), secondaryStart, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private void submit(String action) {
        submit(action, replyInput == null ? "" : replyInput.getText().toString());
    }

    private void submit(String action, String text) {
        try {
            HumanHelpStore.complete(this, requestId, action, text);
            Toast.makeText(this, "Sent to Agent", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showBleVoiceConfirmation(String attachmentPath, String transcript, String sttStatus) {
        String safeTranscript = transcript == null ? "" : transcript.trim();
        String message;
        if (!safeTranscript.isEmpty()) {
            message = "辨識到：\n\n「" + safeTranscript + "」\n\n還沒有傳給 Agent。";
        } else {
            String status = TextUtils.isEmpty(sttStatus) ? "no transcript" : sttStatus;
            message = "系統語音辨識沒有取得文字。\n\nSTT: " + status
                    + "\n\n目前仍未傳給 Agent。";
        }
        new AlertDialog.Builder(this)
                .setTitle("確定要傳送嗎？")
                .setMessage(message)
                .setPositiveButton("傳送", (dialog, which) -> {
                    try {
                        HumanHelpStore.complete(this, requestId, "語音回覆", safeTranscript);
                        Toast.makeText(this, "語音已傳送", Toast.LENGTH_SHORT).show();
                        finish();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("重錄", (dialog, which) -> discardBleVoice(attachmentPath, true))
                .setNegativeButton("取消", (dialog, which) -> discardBleVoice(attachmentPath, false))
                .setOnCancelListener(dialog -> discardBleVoice(attachmentPath, false))
                .show();
    }

    private void discardBleVoice(String attachmentPath, boolean retry) {
        try {
            if (!TextUtils.isEmpty(attachmentPath)) {
                HumanHelpStore.removeAttachment(this, requestId, attachmentPath);
            }
            HumanHelpStore.renewHumanActivity(this, requestId,
                    retry ? "ble_voice_retry" : "ble_voice_cancel", false);
            Toast.makeText(this,
                    retry ? "已丟棄，按住藍鍵重新說一次" : "已取消傳送",
                    Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pickImage() {
        renewActivity("image_picker");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void requestCamera() {
        renewActivity("camera");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        try {
            pendingCameraFile = HumanHelpStore.createCameraTempFile(this, requestId);
            pendingCameraUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".files",
                    pendingCameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, "No camera app available", Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (IOException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE_REPLY) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty() && !TextUtils.isEmpty(results.get(0))) {
                    showVoiceReplyConfirmation(results.get(0).trim());
                }
            }
            return;
        }
        if (resultCode != RESULT_OK) {
            return;
        }
        try {
            if (requestCode == REQUEST_PICK_IMAGE && data != null && data.getData() != null) {
                addImage(data.getData(), "upload");
            } else if (requestCode == REQUEST_CAMERA && pendingCameraFile != null) {
                addImage(Uri.fromFile(pendingCameraFile), "camera");
                pendingCameraFile.delete();
                pendingCameraFile = null;
                pendingCameraUri = null;
            }
        } catch (Exception error) {
            Toast.makeText(this, "Image failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addImage(Uri uri, String source) throws Exception {
        Bitmap bitmap = decodeScaled(uri);
        if (bitmap == null) {
            throw new IOException("Unable to decode image");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bytes)) {
                throw new IOException("Unable to compress image");
            }
            HumanHelpStore.addJpegAttachment(
                    this,
                    requestId,
                    new ByteArrayInputStream(bytes.toByteArray()),
                    source);
            HumanHelpStore.renewHumanActivity(this, requestId, source, false);
        } finally {
            bitmap.recycle();
        }
        reload();
        refreshState();
    }

    private Bitmap decodeScaled(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source;
            if ("file".equals(uri.getScheme())) {
                source = ImageDecoder.createSource(new File(uri.getPath()));
            } else {
                source = ImageDecoder.createSource(getContentResolver(), uri);
            }
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int largest = Math.max(width, height);
                if (largest > MAX_IMAGE_EDGE) {
                    float scale = MAX_IMAGE_EDGE / (float) largest;
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(input, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_IMAGE_EDGE) {
            sample *= 2;
        }
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            return android.graphics.BitmapFactory.decodeStream(input, null, options);
        }
    }

    private void reload() {
        try {
            request = HumanHelpStore.load(this, requestId);
        } catch (Exception error) {
            request = null;
        }
    }

    private void refreshState() {
        if (request == null) {
            return;
        }
        boolean approvalRequest = "approval".equals(request.optString("requestType", "help"));
        boolean waiting = "waiting_human".equals(request.optString("status"));
        String status = request.optString("status", "");
        if (countdownStatus != null) {
            if (waiting) {
                long remainingMs = Math.max(0L,
                        request.optLong("expiresAtEpochMs", 0L) - System.currentTimeMillis());
                long remainingSeconds = (remainingMs + 999L) / 1000L;
                long minutes = remainingSeconds / 60L;
                long seconds = remainingSeconds % 60L;
                countdownStatus.setText((approvalRequest ? "等待核准" : "等待回覆")
                        + " · " + String.format(java.util.Locale.US, "%d:%02d", minutes, seconds));
                countdownStatus.setTextColor(PickPicoTheme.accentB(theme));
                countdownStatus.setVisibility(View.VISIBLE);
            } else if ("timed_out".equals(status)) {
                countdownStatus.setText("等待已結束 · 已逾時");
                countdownStatus.setTextColor(PickPicoTheme.AMBER);
                countdownStatus.setVisibility(View.VISIBLE);
            } else if ("completed".equals(status)) {
                countdownStatus.setText("等待已結束 · 已完成");
                countdownStatus.setTextColor(PickPicoTheme.GREEN);
                countdownStatus.setVisibility(View.VISIBLE);
            } else {
                countdownStatus.setVisibility(View.GONE);
            }
        }
        if (lifecycleStatus != null) {
            if ("timed_out".equals(status)) {
                lifecycleStatus.setText(approvalRequest
                        ? "此核准請求已逾時 · 操作不會執行。"
                        : "此請求已逾時 · AI 已停止等待，將自行決定下一步。");
                lifecycleStatus.setTextColor(primaryTextColor());
                lifecycleStatus.setBackground(PickPicoTheme.control(
                        theme, dp(12), PickPicoTheme.AMBER, true));
                lifecycleStatus.setVisibility(View.VISIBLE);
            } else if ("completed".equals(status)) {
                lifecycleStatus.setText(approvalRequest
                        ? "已完成 · Agent 已收到你的核准決定。"
                        : "已完成 · AI 已收到你的回覆。");
                lifecycleStatus.setTextColor(primaryTextColor());
                lifecycleStatus.setBackground(PickPicoTheme.control(
                        theme, dp(12), PickPicoTheme.GREEN, true));
                lifecycleStatus.setVisibility(View.VISIBLE);
            } else if (!waiting) {
                lifecycleStatus.setText("此請求已結束，無法再提交回覆。");
                lifecycleStatus.setVisibility(View.VISIBLE);
            } else {
                lifecycleStatus.setVisibility(View.GONE);
            }
        }
        JSONArray attachments = request.optJSONArray("attachments");
        int count = 0;
        if (attachments != null) {
            for (int index = 0; index < attachments.length(); index++) {
                JSONObject attachment = attachments.optJSONObject(index);
                if (attachment != null && "image".equals(attachment.optString("type", ""))) {
                    count++;
                }
            }
        }
        int max = request.optInt("maxImages", 3);
        if (attachmentStatus != null) {
            attachmentStatus.setText(count == 0
                    ? "No images attached"
                    : count + " / " + max + " image(s) attached");
        }
        if (uploadButton != null) {
            uploadButton.setEnabled(waiting && count < max);
        }
        if (cameraButton != null) {
            cameraButton.setEnabled(waiting && count < max);
        }
        if (replyInput != null) {
            replyInput.setEnabled(waiting);
        }
        setActionButtonsEnabled(phoneActionsContainer, waiting);
        if (detailsButton != null) {
            detailsButton.setEnabled(true);
            detailsButton.setAlpha(1f);
        }
        refreshActionMode();
    }

    private void renewActivity(String activity) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastActivityRenewElapsedMs < 1500L) {
            return;
        }
        lastActivityRenewElapsedMs = now;
        try {
            if (HumanHelpStore.renewHumanActivity(this, requestId, activity, false)) {
                reload();
                refreshState();
            }
        } catch (Exception ignored) {
        }
    }

    private View buildHeader(boolean approvalRequest) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(4), 0, dp(15));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView back = text("‹", 38, Typeface.NORMAL);
        back.setTextColor(primaryTextColor());
        back.setGravity(android.view.Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));

        TextView heading = text(approvalRequest ? "APPROVAL" : "H U M A N   H E L P", 17, Typeface.BOLD);
        heading.setTextColor(primaryTextColor());
        heading.setGravity(android.view.Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View balance = new View(this);
        header.addView(balance, new LinearLayout.LayoutParams(dp(42), dp(44)));
        wrapper.addView(header);

        TextView subtitle = text("AI is paused · waiting for you", 13, Typeface.NORMAL);
        subtitle.setTextColor(secondaryTextColor());
        subtitle.setGravity(android.view.Gravity.CENTER);
        wrapper.addView(subtitle);
        return wrapper;
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(PickPicoTheme.card(theme, dp(20), false));
        return card;
    }

    private LinearLayout.LayoutParams cardParams(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(bottomMarginDp);
        return params;
    }

    private TextView cardTitle(String value) {
        TextView view = text(value, 16, Typeface.NORMAL);
        view.setTextColor(primaryTextColor());
        return view;
    }

    private void styleUtilityButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primaryTextColor());
        button.setBackground(PickPicoTheme.control(
                theme, dp(12), PickPicoTheme.accentA(theme), false));
    }

    private int actionColor(int index) {
        if (index == 0) {
            return PickPicoTheme.GREEN;
        }
        if (index == 1) {
            return PickPicoTheme.RED;
        }
        if (index == 2) {
            return PickPicoTheme.BLUE;
        }
        return PickPicoTheme.accentA(theme);
    }

    private void setActionButtonsEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        if (view instanceof Button) {
            view.setEnabled(enabled);
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setActionButtonsEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private void startVoiceReply() {
        renewActivity("voice_reply");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reply to the Agent")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "Voice input is not available on this phone", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(intent, REQUEST_VOICE_REPLY);
    }

    private void showVoiceReplyConfirmation(String transcript) {
        String safeTranscript = transcript == null ? "" : transcript.trim();
        if (safeTranscript.isEmpty()) {
            Toast.makeText(this, "沒有辨識到文字，請重新錄音", Toast.LENGTH_LONG).show();
            startVoiceReply();
            return;
        }
        renewActivity("voice_review");

        LinearLayout panel = glassCard();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        panel.setBackground(PickPicoTheme.strongGlass(theme, dp(24)));

        TextView kicker = text("VOICE REPLY", 10, Typeface.BOLD);
        kicker.setTextColor(PickPicoTheme.accentB(theme));
        kicker.setLetterSpacing(.12f);
        panel.addView(kicker);

        TextView title = text("辨識結果正確嗎？", 21, Typeface.BOLD);
        title.setTextColor(primaryTextColor());
        title.setPadding(0, dp(8), 0, dp(12));
        panel.addView(title);

        TextView transcriptView = text("「" + safeTranscript + "」", 17, Typeface.NORMAL);
        transcriptView.setTextColor(primaryTextColor());
        transcriptView.setPadding(dp(14), dp(13), dp(14), dp(13));
        transcriptView.setBackground(PickPicoTheme.control(
                theme, dp(14), PickPicoTheme.accentB(theme), false));
        panel.addView(transcriptView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = text("確認後才會傳給 Agent。", 12, Typeface.NORMAL);
        hint.setTextColor(secondaryTextColor());
        hint.setPadding(0, dp(10), 0, dp(14));
        panel.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button retry = voiceReviewButton("不正確，重錄", PickPicoTheme.BLUE);
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button send = voiceReviewButton("正確，傳送", PickPicoTheme.GREEN);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        sendParams.setMarginStart(dp(9));
        actions.addView(send, sendParams);
        panel.addView(actions);

        TextView cancel = text("取消", 13, Typeface.BOLD);
        cancel.setTextColor(secondaryTextColor());
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, dp(13), 0, 0);
        panel.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();
        retry.setOnClickListener(v -> {
            dialog.dismiss();
            startVoiceReply();
        });
        send.setOnClickListener(v -> {
            dialog.dismiss();
            submit("語音回覆", safeTranscript);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels - dp(36);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        });
        dialog.show();
    }

    private Button voiceReviewButton(String label, int accent) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primaryTextColor());
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(PickPicoTheme.control(theme, dp(14), accent, true));
        return button;
    }

    private int primaryTextColor() {
        return PickPicoTheme.text(theme);
    }

    private int secondaryTextColor() {
        return PickPicoTheme.muted(theme);
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
