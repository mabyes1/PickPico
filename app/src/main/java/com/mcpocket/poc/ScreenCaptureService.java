package com.mcpocket.poc;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Human-authorized MediaProjection session used by Hyper Mode screen.capture.
 *
 * Android owns the consent dialog. PickPico can start the flow and observe the
 * resulting session, but it cannot grant MediaProjection access to itself.
 */
public final class ScreenCaptureService extends Service {
    static final String ACTION_START = "com.mcpocket.poc.screen.START";
    static final String ACTION_STOP = "com.mcpocket.poc.screen.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "pickpico_screen_capture";
    private static final int NOTIFICATION_ID = 8770;
    private static volatile ScreenCaptureService activeInstance;

    private HandlerThread imageThread;
    private Handler imageHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int width;
    private int height;
    private int densityDpi;
    private boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PickPico screen capture",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while a user-authorized Hyper Mode screen-capture session is active");
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            stopCapture();
            stopping = false;
            startAsForeground();
            startProjection(resultCode, resultData);
        } catch (Throwable error) {
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean isActive() {
        ScreenCaptureService service = activeInstance;
        return service != null
                && service.projection != null
                && service.virtualDisplay != null
                && service.imageReader != null;
    }

    static JSONObject capture(JSONObject arguments, long callCount) throws JSONException {
        ScreenCaptureService service = activeInstance;
        if (service == null || !isActive()) {
            return new JSONObject()
                    .put("available", false)
                    .put("requiresSetup", true)
                    .put("setupAction", "media_projection_consent")
                    .put("userInteractionRequired", true)
                    .put("message", "Open PickPico and start the Screen Capture session. Android requires human confirmation.")
                    .put("toolCallCount", callCount);
        }
        return service.captureInternal(arguments, callCount);
    }

    private synchronized JSONObject captureInternal(JSONObject arguments, long callCount) throws JSONException {
        int quality = arguments.optInt("quality", 82);
        boolean returnContent = arguments.optBoolean("returnContent", true);
        Image image = null;
        try {
            long deadline = SystemClock.elapsedRealtime() + 1800L;
            while (image == null && SystemClock.elapsedRealtime() < deadline) {
                image = imageReader.acquireLatestImage();
                if (image == null) {
                    SystemClock.sleep(40L);
                }
            }
            if (image == null) {
                return new JSONObject()
                        .put("captured", false)
                        .put("error", "screen_frame_unavailable")
                        .put("message", "No MediaProjection frame was available yet")
                        .put("toolCallCount", callCount);
            }

            Bitmap bitmap = bitmapFromImage(image);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) {
                bitmap.recycle();
                throw new IllegalStateException("Unable to encode screen frame as JPEG");
            }
            bitmap.recycle();

            byte[] jpeg = bytes.toByteArray();
            String relativePath = "captures/screen-" + System.currentTimeMillis() + ".jpg";
            File root = new File(getFilesDir(), "workspaces");
            File output = new File(root, relativePath);
            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Unable to create screen capture directory");
            }
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(jpeg);
                stream.getFD().sync();
            }

            JSONObject result = new JSONObject()
                    .put("captured", true)
                    .put("width", width)
                    .put("height", height)
                    .put("mimeType", "image/jpeg")
                    .put("path", relativePath)
                    .put("sizeBytes", jpeg.length)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
            if (returnContent) {
                result.put("_mcpContent", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", "Captured current Android screen to " + relativePath))
                        .put(new JSONObject()
                                .put("type", "image")
                                .put("mimeType", "image/jpeg")
                                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))));
            }
            return result;
        } catch (Throwable error) {
            return new JSONObject()
                    .put("captured", false)
                    .put("error", "screen_capture_failed")
                    .put("message", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()))
                    .put("toolCallCount", callCount);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void startProjection(int resultCode, Intent resultData) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        width = Math.max(1, metrics.widthPixels);
        height = Math.max(1, metrics.heightPixels);
        densityDpi = Math.max(1, metrics.densityDpi);

        imageThread = new HandlerThread("pickpico-screen-capture");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("MediaProjectionManager unavailable");
        }
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            throw new IllegalStateException("Android did not create a MediaProjection session");
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!stopping) {
                    stopCapture();
                    stopSelf();
                }
            }
        }, imageHandler);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        virtualDisplay = projection.createVirtualDisplay(
                "PickPicoScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                imageHandler);
        if (virtualDisplay == null) {
            throw new IllegalStateException("Unable to create MediaProjection virtual display");
        }
        activeInstance = this;
    }

    private synchronized void stopCapture() {
        stopping = true;
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
            imageThread = null;
            imageHandler = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private Bitmap bitmapFromImage(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            throw new IllegalStateException("MediaProjection frame has no image planes");
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = Math.max(0, rowStride - pixelStride * width);
        int paddedWidth = width + rowPadding / Math.max(1, pixelStride);

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == width) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private void startAsForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("PickPico Hyper screen capture")
                .setContentText("Android screen sharing is active")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
