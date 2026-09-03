package com.mcpocket.poc;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Transparent trampoline into Android's file / media picker. */
public final class PickerActivity extends Activity {
    private static final int REQUEST_PICK = 4901;
    private String requestId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getStringExtra(PickerRequestStore.EXTRA_REQUEST_ID);
        if (requestId == null || requestId.isEmpty()) {
            finish();
            return;
        }
        if (savedInstanceState != null) {
            return;
        }
        try {
            JSONObject request = PickerRequestStore.load(this, requestId);
            if (request == null) {
                finish();
                return;
            }
            PickerRequestStore.markPicking(this, requestId);
            Intent picker = "media".equals(request.optString("kind", "file"))
                    ? mediaPicker(request.optJSONObject("arguments"))
                    : filePicker(request.optJSONObject("arguments"));
            startActivityForResult(picker, REQUEST_PICK);
        } catch (JSONException | RuntimeException error) {
            cancel("Unable to open picker: " + error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) {
            return;
        }
        if (resultCode != RESULT_OK) {
            cancel("User cancelled the Android picker");
            return;
        }
        try {
            PickerRequestStore.complete(this, requestId, data);
        } catch (JSONException | IOException error) {
            cancel("Unable to import selection: " + error.getMessage());
            return;
        }
        finish();
    }

    private Intent filePicker(JSONObject arguments) {
        JSONObject safe = arguments == null ? new JSONObject() : arguments;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, safe.optBoolean("allowMultiple", false));
        JSONArray mimeTypes = safe.optJSONArray("mimeTypes");
        if (mimeTypes == null || mimeTypes.length() == 0) {
            intent.setType("*/*");
        } else if (mimeTypes.length() == 1) {
            intent.setType(mimeTypes.optString(0, "*/*"));
        } else {
            String[] values = new String[mimeTypes.length()];
            for (int index = 0; index < mimeTypes.length(); index++) {
                values[index] = mimeTypes.optString(index, "*/*");
            }
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, values);
        }
        return intent;
    }

    private Intent mediaPicker(JSONObject arguments) {
        JSONObject safe = arguments == null ? new JSONObject() : arguments;
        boolean allowMultiple = safe.optBoolean("allowMultiple", false);
        String mediaType = safe.optString("mediaType", "image_or_video");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if ("image".equals(mediaType)) {
                intent.setType("image/*");
            } else if ("video".equals(mediaType)) {
                intent.setType("video/*");
            }
            if (allowMultiple) {
                intent.putExtra(
                        MediaStore.EXTRA_PICK_IMAGES_MAX,
                        Math.max(2, Math.min(MediaStore.getPickImagesMaxLimit(), safe.optInt("maxItems", 5))));
            }
            return intent;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        if ("image".equals(mediaType)) {
            intent.setType("image/*");
        } else if ("video".equals(mediaType)) {
            intent.setType("video/*");
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }
        return intent;
    }

    private void cancel(String reason) {
        try {
            if (requestId != null) {
                PickerRequestStore.cancel(this, requestId, reason);
            }
        } catch (JSONException ignored) {
        }
        finish();
    }
}
