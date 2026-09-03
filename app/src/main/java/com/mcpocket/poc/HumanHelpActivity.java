package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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

public final class HumanHelpActivity extends Activity {
    private static final int REQUEST_PICK_IMAGE = 201;
    private static final int REQUEST_CAMERA = 202;
    private static final int REQUEST_CAMERA_PERMISSION = 203;
    private static final int MAX_IMAGE_EDGE = 1600;

    private String requestId;
    private JSONObject request;
    private EditText replyInput;
    private TextView countdownStatus;
    private TextView lifecycleStatus;
    private TextView attachmentStatus;
    private LinearLayout actionContainer;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("HUMAN HELP", 12, Typeface.BOLD);
        eyebrow.setTextColor(Color.rgb(90, 90, 90));
        root.addView(eyebrow);

        countdownStatus = text("", 15, Typeface.BOLD);
        countdownStatus.setTextColor(Color.rgb(45, 85, 145));
        countdownStatus.setPadding(0, dp(8), 0, dp(2));
        root.addView(countdownStatus);

        TextView title = text(request.optString("title", "AI needs your help"), 27, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(12));
        root.addView(title);

        TextView instruction = text(request.optString("instruction", ""), 18, Typeface.NORMAL);
        instruction.setTextColor(Color.rgb(35, 35, 35));
        instruction.setPadding(dp(14), dp(14), dp(14), dp(14));
        instruction.setBackgroundColor(Color.rgb(241, 243, 246));
        root.addView(instruction);

        lifecycleStatus = text("", 15, Typeface.BOLD);
        lifecycleStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
        lifecycleStatus.setVisibility(View.GONE);
        root.addView(lifecycleStatus);

        if (request.optBoolean("allowTextReply", true)) {
            TextView replyLabel = label("REPLY");
            replyLabel.setPadding(0, dp(22), 0, dp(6));
            root.addView(replyLabel);
            replyInput = new EditText(this);
            replyInput.setHint("Type anything the Agent should know…");
            replyInput.setMinLines(3);
            replyInput.setMaxLines(8);
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
            root.addView(replyInput, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (request.optBoolean("allowImages", true) && request.optInt("maxImages", 3) > 0) {
            root.addView(label("IMAGES"));
            attachmentStatus = text("No images attached", 14, Typeface.NORMAL);
            attachmentStatus.setTextColor(Color.DKGRAY);
            attachmentStatus.setPadding(0, 0, 0, dp(8));
            root.addView(attachmentStatus);

            LinearLayout imageButtons = new LinearLayout(this);
            imageButtons.setOrientation(LinearLayout.HORIZONTAL);

            uploadButton = new Button(this);
            uploadButton.setText("UPLOAD IMAGE");
            uploadButton.setOnClickListener(v -> pickImage());
            imageButtons.addView(uploadButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

            cameraButton = new Button(this);
            cameraButton.setText("TAKE PHOTO");
            cameraButton.setOnClickListener(v -> requestCamera());
            LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
            cameraParams.setMarginStart(dp(8));
            imageButtons.addView(cameraButton, cameraParams);
            root.addView(imageButtons);
        }

        root.addView(label("RESPONSE"));
        actionContainer = new LinearLayout(this);
        actionContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionContainer);
        rebuildActions();
        refreshState();
        return scroll;
    }

    private void rebuildActions() {
        actionContainer.removeAllViews();
        JSONArray actions = request.optJSONArray("actions");
        if (actions == null) {
            return;
        }
        for (int index = 0; index < actions.length(); index++) {
            String action = actions.optString(index, "");
            if (action.isEmpty()) {
                continue;
            }
            Button button = new Button(this);
            button.setText(action);
            button.setOnClickListener(v -> submit(action));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(54));
            params.setMargins(0, 0, 0, dp(8));
            actionContainer.addView(button, params);
        }
    }

    private void submit(String action) {
        try {
            String text = replyInput == null ? "" : replyInput.getText().toString();
            HumanHelpStore.complete(this, requestId, action, text);
            Toast.makeText(this, "Sent to Agent", Toast.LENGTH_SHORT).show();
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
        boolean waiting = "waiting_human".equals(request.optString("status"));
        String status = request.optString("status", "");
        if (countdownStatus != null) {
            if (waiting) {
                long remainingMs = Math.max(0L,
                        request.optLong("expiresAtEpochMs", 0L) - System.currentTimeMillis());
                long remainingSeconds = (remainingMs + 999L) / 1000L;
                countdownStatus.setText("AI 等待中 · 剩餘 " + remainingSeconds + " 秒（操作會重置）");
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
                lifecycleStatus.setText("此請求已逾時 · AI 已停止等待，將自行決定下一步。");
                lifecycleStatus.setTextColor(Color.rgb(145, 70, 35));
                lifecycleStatus.setBackgroundColor(Color.rgb(255, 238, 226));
                lifecycleStatus.setVisibility(View.VISIBLE);
            } else if ("completed".equals(status)) {
                lifecycleStatus.setText("已完成 · AI 已收到你的回覆。");
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
        int count = attachments == null ? 0 : attachments.length();
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
        for (int index = 0; actionContainer != null && index < actionContainer.getChildCount(); index++) {
            actionContainer.getChildAt(index).setEnabled(waiting);
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

    private TextView label(String value) {
        TextView view = text(value, 12, Typeface.BOLD);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(20), 0, dp(6));
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
}
