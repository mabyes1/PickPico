package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Button;
import android.widget.EditText;
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
    private static final int REQUEST_PICK_IMAGE = 201;
    private static final int REQUEST_CAMERA = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;
    private static final int REQUEST_VOICE_REPLY = 204;
    private static final int MAX_IMAGE_EDGE = 1600;
    private static final int SCREEN_BG = Color.rgb(67, 80, 89);
    private static final int CARD_BG = Color.rgb(86, 101, 111);
    private static final int CARD_BG_ALT = Color.rgb(102, 119, 130);
    private static final int CARD_STROKE = Color.rgb(190, 207, 218);
    private static final int TEXT_PRIMARY = Color.rgb(248, 250, 252);
    private static final int TEXT_SECONDARY = Color.rgb(200, 210, 217);
    private static final int ACCENT_BLUE = Color.rgb(174, 230, 255);

    private String requestId;
    private JSONObject request;
    private EditText replyInput;
    private TextView countdownStatus;
    private TextView lifecycleStatus;
    private TextView attachmentStatus;
    private LinearLayout actionContainer;
    private Button customActionButton;
    private String customAction = "";
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
        getWindow().setStatusBarColor(SCREEN_BG);
        getWindow().setNavigationBarColor(SCREEN_BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        requestId = getIntent().getStringExtra(HumanHelpStore.EXTRA_REQUEST_ID);
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(screenBackground());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(30));
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
        pauseIcon.setTextColor(TEXT_PRIMARY);
        pauseIcon.setGravity(android.view.Gravity.CENTER);
        pauseIcon.setBackground(roundedBackground(Color.rgb(88, 110, 121), 0, 24));
        pauseCard.addView(pauseIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout pauseCopy = new LinearLayout(this);
        pauseCopy.setOrientation(LinearLayout.VERTICAL);
        TextView pauseTitle = text(approvalRequest ? "AI needs approval" : "AI paused", 15, Typeface.BOLD);
        pauseTitle.setTextColor(TEXT_PRIMARY);
        TextView pauseSubtitle = text("Your input is required.", 11, Typeface.NORMAL);
        pauseSubtitle.setTextColor(TEXT_SECONDARY);
        pauseCopy.addView(pauseTitle);
        pauseCopy.addView(pauseSubtitle);
        LinearLayout.LayoutParams pauseCopyParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pauseCopyParams.setMarginStart(dp(9));
        pauseCard.addView(pauseCopy, pauseCopyParams);

        countdownStatus = text("", 13, Typeface.BOLD);
        countdownStatus.setTextColor(TEXT_PRIMARY);
        countdownStatus.setGravity(android.view.Gravity.END);
        pauseCard.addView(countdownStatus);
        root.addView(pauseCard, cardParams(10));

        LinearLayout requestCard = glassCard();
        requestCard.setOrientation(LinearLayout.VERTICAL);

        LinearLayout requestTitleRow = new LinearLayout(this);
        requestTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        requestTitleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView taskIcon = text("AI", 12, Typeface.BOLD);
        taskIcon.setTextColor(Color.WHITE);
        taskIcon.setGravity(android.view.Gravity.CENTER);
        taskIcon.setBackground(roundedBackground(Color.rgb(92, 116, 128), CARD_STROKE, 10));
        requestTitleRow.addView(taskIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text(request.optString("title", "AI needs your help"), 23, Typeface.BOLD);
        title.setTextColor(TEXT_PRIMARY);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        requestTitleRow.addView(title, titleParams);
        requestCard.addView(requestTitleRow);

        TextView instruction = text(request.optString("instruction", ""), 17, Typeface.NORMAL);
        instruction.setTextColor(TEXT_PRIMARY);
        instruction.setLineSpacing(0f, 1.12f);
        instruction.setPadding(0, dp(15), 0, 0);
        requestCard.addView(instruction);

        String additionalNotes = request.optString("additionalNotes", "").trim();
        if (!additionalNotes.isEmpty()) {
            TextView notesLabel = text("Additional notes from AI", 13, Typeface.NORMAL);
            notesLabel.setTextColor(TEXT_SECONDARY);
            notesLabel.setPadding(0, dp(16), 0, dp(7));
            requestCard.addView(notesLabel);
            TextView notes = text(additionalNotes, 15, Typeface.NORMAL);
            notes.setTextColor(TEXT_PRIMARY);
            notes.setPadding(dp(12), dp(11), dp(12), dp(11));
            notes.setBackground(roundedBackground(Color.argb(62, 255, 255, 255), CARD_STROKE, 10));
            requestCard.addView(notes);
        }
        root.addView(requestCard, cardParams(14));

        lifecycleStatus = text("", 15, Typeface.BOLD);
        lifecycleStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        lifecycleStatus.setVisibility(View.GONE);
        root.addView(lifecycleStatus);

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
            replyInput.setTextColor(TEXT_PRIMARY);
            replyInput.setHintTextColor(Color.rgb(170, 184, 194));
            replyInput.setMinLines(2);
            replyInput.setMaxLines(6);
            replyInput.setPadding(dp(14), dp(12), dp(14), dp(12));
            replyInput.setBackground(roundedBackground(Color.argb(38, 255, 255, 255), Color.rgb(180, 202, 215), 12));
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
            root.addView(replyCard, cardParams(14));
        }

        if (request.optBoolean("allowImages", true) && request.optInt("maxImages", 3) > 0) {
            LinearLayout attachmentCard = glassCard();
            attachmentCard.setPadding(dp(12), dp(9), dp(12), dp(10));
            attachmentCard.setOrientation(LinearLayout.VERTICAL);
            TextView attachmentTitle = text("Add an image · optional", 13, Typeface.NORMAL);
            attachmentTitle.setTextColor(TEXT_SECONDARY);
            attachmentCard.addView(attachmentTitle);
            attachmentStatus = text("No images attached", 11, Typeface.NORMAL);
            attachmentStatus.setTextColor(TEXT_SECONDARY);
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
            root.addView(attachmentCard, cardParams(10));
        }

        actionContainer = new LinearLayout(this);
        actionContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionContainer);
        rebuildActions();
        refreshState();
        return scroll;
    }

    private void rebuildActions() {
        actionContainer.removeAllViews();
        String approveAction = HumanHelpStore.approveAction(request);
        String rejectAction = HumanHelpStore.rejectAction(request);
        customAction = HumanHelpStore.customAction(request);

        LinearLayout primaryRow = actionRow();
        addActionButton(primaryRow, "✓", "Approve", "確認繼續", 0,
                () -> submit(approveAction));
        addActionButton(primaryRow, "×", "Reject", "拒絕", 1,
                () -> submit(rejectAction));
        actionContainer.addView(primaryRow, actionRowParams());

        LinearLayout secondaryRow = actionRow();
        addActionButton(secondaryRow, "🎙", "Voice", "語音回覆", 2,
                this::startVoiceReply);
        customActionButton = addActionButton(
                secondaryRow,
                "✦",
                customAction.isEmpty() ? "Custom" : customAction,
                "CUSTOM",
                3,
                () -> submit(customAction));
        customActionButton.setEnabled(!customAction.isEmpty());
        customActionButton.setAlpha(customAction.isEmpty() ? 0.38f : 1f);
        actionContainer.addView(secondaryRow, actionRowParams());
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
        button.setTextColor(TEXT_PRIMARY);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(8), dp(5), dp(8), dp(5));
        button.setBackgroundTintList(ColorStateList.valueOf(actionColor(index)));
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
        label.setSpan(new ForegroundColorSpan(TEXT_SECONDARY), secondaryStart, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                    submit("語音回覆", results.get(0).trim());
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
                countdownStatus.setTextColor(Color.rgb(45, 85, 145));
                countdownStatus.setVisibility(View.VISIBLE);
            } else if ("timed_out".equals(status)) {
                countdownStatus.setText("等待已結束 · 已逾時");
                countdownStatus.setTextColor(Color.rgb(145, 70, 35));
                countdownStatus.setVisibility(View.VISIBLE);
            } else if ("completed".equals(status)) {
                countdownStatus.setText("等待已結束 · 已完成");
                countdownStatus.setTextColor(Color.rgb(35, 105, 55));
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
                lifecycleStatus.setTextColor(Color.rgb(145, 70, 35));
                lifecycleStatus.setBackgroundColor(Color.rgb(255, 238, 226));
                lifecycleStatus.setVisibility(View.VISIBLE);
            } else if ("completed".equals(status)) {
                lifecycleStatus.setText(approvalRequest
                        ? "已完成 · Agent 已收到你的核准決定。"
                        : "已完成 · AI 已收到你的回覆。");
                lifecycleStatus.setTextColor(Color.rgb(35, 105, 55));
                lifecycleStatus.setBackgroundColor(Color.rgb(230, 246, 234));
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
        setActionButtonsEnabled(actionContainer, waiting);
        if (customActionButton != null) {
            boolean customEnabled = waiting && !customAction.isEmpty();
            customActionButton.setEnabled(customEnabled);
            customActionButton.setAlpha(customEnabled ? 1f : 0.38f);
        }
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
        back.setTextColor(TEXT_PRIMARY);
        back.setGravity(android.view.Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));

        TextView heading = text(approvalRequest ? "APPROVAL" : "H U M A N   H E L P", 17, Typeface.BOLD);
        heading.setTextColor(TEXT_PRIMARY);
        heading.setGravity(android.view.Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View balance = new View(this);
        header.addView(balance, new LinearLayout.LayoutParams(dp(42), dp(44)));
        wrapper.addView(header);

        TextView subtitle = text("AI is paused · waiting for you", 13, Typeface.NORMAL);
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setGravity(android.view.Gravity.CENTER);
        wrapper.addView(subtitle);
        return wrapper;
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(glassBackground());
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
        view.setTextColor(TEXT_PRIMARY);
        return view;
    }

    private void styleUtilityButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(TEXT_PRIMARY);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(79, 96, 106)));
    }

    private int actionColor(int index) {
        if (index == 0) {
            return Color.rgb(66, 128, 108);
        }
        if (index == 1) {
            return Color.rgb(128, 70, 76);
        }
        return Color.rgb(74, 90, 101);
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

    private GradientDrawable screenBackground() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(94, 109, 119), Color.rgb(67, 80, 89)});
    }

    private GradientDrawable glassBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(66, 255, 255, 255), Color.argb(28, 255, 255, 255)});
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), Color.argb(150, 220, 235, 244));
        return background;
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
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
