package com.mcpocket.poc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Native Android sensing and interaction capabilities for the Mobile Agent Node. */
final class AndroidDeviceCapabilities {
    // Android notification-channel importance is immutable after first creation. The original
    // channel shipped at DEFAULT, so v2 gives existing installs a genuinely HIGH channel.
    private static final String AGENT_CHANNEL_ID = "mcpocket_agent_messages_v3";
    private static final int CAMERA_TIMEOUT_SECONDS = 8;
    private static final int TTS_INIT_TIMEOUT_SECONDS = 5;
    private static final int AUDIO_SAMPLE_RATE = 16000;
    private static final int AUDIO_BYTES_PER_SAMPLE = 2;

    private final Service service;
    private final File workspaceRoot;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger notificationIds = new AtomicInteger(9200);
    private final CountDownLatch ttsReady = new CountDownLatch(1);
    private volatile TextToSpeech textToSpeech;
    private volatile int ttsInitStatus = TextToSpeech.ERROR;

    AndroidDeviceCapabilities(Service service, File workspaceRoot) {
        this.service = service;
        this.workspaceRoot = workspaceRoot;
        createAgentNotificationChannel();
        initializeTts();
    }

    JSONObject status() throws JSONException {
        JSONObject result = new JSONObject()
                .put("cameraPermission", hasPermission(Manifest.permission.CAMERA))
                .put("microphonePermission", hasPermission(Manifest.permission.RECORD_AUDIO))
                .put("notificationPermission", notificationPermissionGranted())
                .put("cameraForegroundType", foregroundTypeActive(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA))
                .put("microphoneForegroundType", foregroundTypeActive(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE))
                .put("audio", inspectMediaAudio(false));
        if (ttsReady.getCount() == 0L) {
            result.put("ttsReady", ttsInitStatus == TextToSpeech.SUCCESS);
        } else {
            result.put("ttsReady", JSONObject.NULL);
        }
        return result;
    }

    JSONObject captureCamera(JSONObject arguments, long callCount) throws JSONException {
        JSONObject unavailable = requireMediaCapability(
                Manifest.permission.CAMERA,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                "camera");
        if (unavailable != null) {
            return unavailable.put("toolCallCount", callCount);
        }

        String lens = arguments.optString("lens", "back");
        int maxWidth = arguments.optInt("maxWidth", 1280);
        int maxHeight = arguments.optInt("maxHeight", 1280);
        int quality = arguments.optInt("quality", 85);
        boolean returnContent = arguments.optBoolean("returnContent", true);

        CameraManager manager = (CameraManager) service.getSystemService(Service.CAMERA_SERVICE);
        if (manager == null) {
            return failure("camera_unavailable", "Android CameraManager is unavailable", callCount);
        }

        HandlerThread cameraThread = new HandlerThread("mcpocket-camera");
        cameraThread.start();
        Handler cameraHandler = new Handler(cameraThread.getLooper());
        // Device/session callbacks must outlive the per-capture image thread:
        // openCamera may deliver its result after our eight-second deadline.
        Handler stateHandler = new Handler(Looper.getMainLooper());
        CaptureResources resources = new CaptureResources();
        ImageReader reader = null;
        try {
            CameraSelection selection = selectCamera(manager, lens, maxWidth, maxHeight);
            reader = ImageReader.newInstance(
                    selection.size.getWidth(),
                    selection.size.getHeight(),
                    ImageFormat.JPEG,
                    2);
            resources.add(reader);

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<byte[]> imageBytes = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ImageReader captureReader = reader;

            reader.setOnImageAvailableListener(imageReader -> {
                try (Image image = imageReader.acquireLatestImage()) {
                    if (image == null) {
                        return;
                    }
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    imageBytes.compareAndSet(null, bytes);
                    finished.countDown();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    finished.countDown();
                }
            }, cameraHandler);

            manager.openCamera(selection.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice openedCamera) {
                    synchronized (resources) {
                        if (!resources.add(openedCamera)) return;
                        try {
                            openedCamera.createCaptureSession(
                                    Collections.singletonList(captureReader.getSurface()),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession captureSession) {
                                            synchronized (resources) {
                                                if (!resources.add(captureSession)) return;
                                                try {
                                                    CaptureRequest.Builder request = openedCamera.createCaptureRequest(
                                                            CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                    request.addTarget(captureReader.getSurface());
                                                    request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                                    request.set(CaptureRequest.JPEG_QUALITY, (byte) quality);
                                                    if (selection.sensorOrientation != null) {
                                                        request.set(CaptureRequest.JPEG_ORIENTATION, selection.sensorOrientation);
                                                    }
                                                    captureSession.capture(
                                                            request.build(),
                                                            new CameraCaptureSession.CaptureCallback() {},
                                                            cameraHandler);
                                                } catch (Throwable error) {
                                                    failure.compareAndSet(null, error);
                                                    finished.countDown();
                                                }
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession captureSession) {
                                            resources.add(captureSession);
                                            resources.close();
                                            failure.compareAndSet(
                                                    null,
                                                    new IllegalStateException("Camera capture session configuration failed"));
                                            finished.countDown();
                                        }
                                    },
                                    stateHandler);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                            finished.countDown();
                        }
                    }
                }

                @Override
                public void onDisconnected(CameraDevice disconnectedCamera) {
                    resources.add(disconnectedCamera);
                    resources.close();
                    failure.compareAndSet(null, new IllegalStateException("Camera disconnected"));
                    finished.countDown();
                }

                @Override
                public void onError(CameraDevice errorCamera, int errorCode) {
                    resources.add(errorCamera);
                    resources.close();
                    failure.compareAndSet(
                            null,
                            new IllegalStateException("Camera error code " + errorCode));
                    finished.countDown();
                }
            }, stateHandler);

            boolean completed = finished.await(CAMERA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                return failure("camera_timeout", "Timed out waiting for a camera frame", callCount);
            }
            if (failure.get() != null) {
                Throwable error = failure.get();
                return failure(
                        "camera_capture_failed",
                        error.getClass().getSimpleName() + ": " + safeMessage(error),
                        callCount);
            }

            byte[] bytes = imageBytes.get();
            if (bytes == null || bytes.length == 0) {
                return failure("camera_empty_frame", "Camera returned no JPEG data", callCount);
            }

            String relativePath = mediaPath("camera", "jpg");
            File output = resolveMediaFile(relativePath);
            writeBytes(output, bytes);
            JSONObject result = new JSONObject()
                    .put("captured", true)
                    .put("lens", selection.lens)
                    .put("cameraId", selection.cameraId)
                    .put("width", selection.size.getWidth())
                    .put("height", selection.size.getHeight())
                    .put("mimeType", "image/jpeg")
                    .put("path", relativePath)
                    .put("sizeBytes", bytes.length)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
            if (returnContent) {
                result.put("_mcpContent", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", "Captured " + lens + " camera image to " + relativePath))
                        .put(new JSONObject()
                                .put("type", "image")
                                .put("mimeType", "image/jpeg")
                                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))));
            }
            return result;
        } catch (SecurityException error) {
            return failure("camera_permission_denied", safeMessage(error), callCount);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure("camera_interrupted", "Camera capture was interrupted", callCount);
        } catch (Throwable error) {
            return failure(
                    "camera_capture_failed",
                    error.getClass().getSimpleName() + ": " + safeMessage(error),
                    callCount);
        } finally {
            resources.close();
            cameraThread.quitSafely();
        }
    }

    JSONObject notifyUser(JSONObject arguments, long callCount) throws JSONException {
        if (!notificationPermissionGranted()) {
            return new JSONObject()
                    .put("notified", false)
                    .put("requiresSetup", true)
                    .put("setupAction", "grant_notification_permission")
                    .put("toolCallCount", callCount);
        }
        String title = arguments.optString("title", "PickPico Agent");
        String body = arguments.optString("body", "");
        int notificationId = notificationIds.incrementAndGet();
        String inboxId = AgentInboxStore.add(service, "phone.notify", title, body);

        Intent openIntent = new Intent(service, AgentInboxActivity.class)
                .putExtra(AgentInboxActivity.EXTRA_ENTRY_ID, inboxId);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                service,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(service, AGENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE);
        boolean hyperUnlockArmed = AgentAttention.applyUrgentBehavior(service, builder, notificationId);
        Notification notification = builder.build();
        NotificationManager manager = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (manager == null) {
            return failure("notification_unavailable", "NotificationManager is unavailable", callCount);
        }
        manager.notify(notificationId, notification);
        AgentAttention.alert(service);
        return new JSONObject()
                .put("notified", true)
                .put("notificationId", notificationId)
                .put("inboxId", inboxId)
                .put("title", title)
                .put("body", body)
                .put("screenWakeRequested", true)
                .put("lockscreenVisibility", "public")
                .put("hyperUnlockArmed", hyperUnlockArmed)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    JSONObject speak(JSONObject arguments, long callCount) throws JSONException {
        String text = arguments.optString("text", "");
        String language = arguments.optString("language", "");
        float rate = (float) arguments.optDouble("rate", 1.0);
        float pitch = (float) arguments.optDouble("pitch", 1.0);
        String queue = arguments.optString("queue", "flush");
        try {
            if (!ttsReady.await(TTS_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return failure("tts_init_timeout", "Android TextToSpeech initialization timed out", callCount);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure("tts_interrupted", "TextToSpeech request was interrupted", callCount);
        }

        TextToSpeech tts = textToSpeech;
        if (tts == null || ttsInitStatus != TextToSpeech.SUCCESS) {
            return failure("tts_unavailable", "Android TextToSpeech is unavailable", callCount);
        }
        if (!language.isEmpty()) {
            Locale locale = Locale.forLanguageTag(language);
            int languageResult = tts.setLanguage(locale);
            if (languageResult == TextToSpeech.LANG_MISSING_DATA
                    || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                return failure("tts_language_unavailable", "Unsupported TTS language: " + language, callCount);
            }
        }
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        JSONObject audioState = inspectMediaAudio(true);
        String utteranceId = "mcpocket-" + UUID.randomUUID();
        Bundle speechParameters = new Bundle();
        speechParameters.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        int result = tts.speak(
                text,
                "add".equals(queue) ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH,
                speechParameters,
                utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            return failure("tts_speak_failed", "Android rejected the TTS request", callCount);
        }
        return new JSONObject()
                .put("speaking", true)
                .put("utteranceId", utteranceId)
                .put("text", text)
                .put("language", language.isEmpty() ? JSONObject.NULL : language)
                .put("rate", rate)
                .put("pitch", pitch)
                .put("queue", queue)
                .put("audio", audioState)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    private JSONObject inspectMediaAudio(boolean autoAdjust) throws JSONException {
        AudioManager audio = (AudioManager) service.getSystemService(Service.AUDIO_SERVICE);
        if (audio == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("stream", "music")
                    .put("autoBoostRecommended", false)
                    .put("volumeAdjusted", false)
                    .put("decision", "audio_unavailable");
        }

        int currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int ringerMode = audio.getRingerMode();
        int interruptionFilter = currentInterruptionFilter();
        boolean quietMode = ringerMode == AudioManager.RINGER_MODE_SILENT
                || interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
                || interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS;
        AudioVolumePolicy.Decision decision = AudioVolumePolicy.decide(
                currentVolume,
                maxVolume,
                quietMode);

        boolean adjusted = false;
        int appliedVolume = currentVolume;
        String decisionReason = decision.reason;
        if (autoAdjust && decision.shouldAdjust) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, decision.targetVolume, 0);
                appliedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                adjusted = appliedVolume > currentVolume;
                decisionReason = adjusted ? "raised_low_media_volume" : "volume_adjustment_not_applied";
            } catch (SecurityException | IllegalArgumentException error) {
                decisionReason = "volume_adjustment_failed";
            }
        }

        return new JSONObject()
                .put("available", true)
                .put("stream", "music")
                .put("volume", appliedVolume)
                .put("maxVolume", maxVolume)
                .put("volumePercent", volumePercent(appliedVolume, maxVolume))
                .put("previousVolume", currentVolume)
                .put("recommendedVolume", decision.targetVolume)
                .put("autoBoostRecommended", decision.shouldAdjust)
                .put("volumeAdjusted", adjusted)
                .put("ringerMode", ringerModeName(ringerMode))
                .put("interruptionFilter", interruptionFilterName(interruptionFilter))
                .put("quietModeRespected", quietMode)
                .put("decision", decisionReason);
    }

    JSONObject audioStatus(long callCount) throws JSONException {
        AudioManager audio = (AudioManager) service.getSystemService(Service.AUDIO_SERVICE);
        if (audio == null) {
            return failure("audio_unavailable", "Android AudioManager is unavailable", callCount);
        }
        return buildAudioStatus(audio)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    JSONObject audioSet(JSONObject arguments, long callCount) throws JSONException {
        AudioManager audio = (AudioManager) service.getSystemService(Service.AUDIO_SERVICE);
        if (audio == null) {
            return failure("audio_unavailable", "Android AudioManager is unavailable", callCount);
        }
        if (audio.isVolumeFixed()) {
            return failure("audio_volume_fixed", "Android reports fixed device volume", callCount);
        }

        String streamName = arguments.optString("stream", "");
        int requestedPercent = arguments.optInt("percent", -1);
        boolean showUi = arguments.optBoolean("showUi", false);
        int stream = audioStream(streamName);
        int maxVolume = audio.getStreamMaxVolume(stream);
        int before = audio.getStreamVolume(stream);
        int notificationBefore = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int ringBefore = audio.getStreamVolume(AudioManager.STREAM_RING);
        int target = Math.round((maxVolume * requestedPercent) / 100f);
        if (requestedPercent > 0 && target == 0 && maxVolume > 0) {
            target = 1;
        }

        try {
            audio.setStreamVolume(
                    stream,
                    target,
                    showUi ? AudioManager.FLAG_SHOW_UI : 0);
        } catch (SecurityException error) {
            return failure(
                    "audio_set_denied",
                    "Android denied the volume change: " + safeMessage(error),
                    callCount);
        } catch (IllegalArgumentException error) {
            return failure(
                    "audio_set_invalid",
                    "Android rejected the volume change: " + safeMessage(error),
                    callCount);
        }

        int after = audio.getStreamVolume(stream);
        int notificationAfter = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        int ringAfter = audio.getStreamVolume(AudioManager.STREAM_RING);
        JSONObject result = new JSONObject()
                .put("set", after == target)
                .put("changed", before != after)
                .put("stream", streamName)
                .put("requestedPercent", requestedPercent)
                .put("before", streamState(audio, streamName, stream, before))
                .put("after", streamState(audio, streamName, stream, after))
                .put("ringerMode", ringerModeName(audio.getRingerMode()))
                .put("interruptionFilter", interruptionFilterName(currentInterruptionFilter()))
                .put("linkedNotificationChanged", notificationBefore != notificationAfter && !"notification".equals(streamName))
                .put("linkedRingChanged", ringBefore != ringAfter && !"ring".equals(streamName))
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
        if (notificationBefore != notificationAfter || ringBefore != ringAfter) {
            result.put("notificationAfter", streamState(
                    audio,
                    "notification",
                    AudioManager.STREAM_NOTIFICATION,
                    notificationAfter));
            result.put("ringAfter", streamState(
                    audio,
                    "ring",
                    AudioManager.STREAM_RING,
                    ringAfter));
        }
        return result;
    }

    private JSONObject buildAudioStatus(AudioManager audio) throws JSONException {
        return new JSONObject()
                .put("available", true)
                .put("fixedVolume", audio.isVolumeFixed())
                .put("ringerMode", ringerModeName(audio.getRingerMode()))
                .put("interruptionFilter", interruptionFilterName(currentInterruptionFilter()))
                .put("media", streamState(audio, "media", AudioManager.STREAM_MUSIC))
                .put("notification", streamState(audio, "notification", AudioManager.STREAM_NOTIFICATION))
                .put("ring", streamState(audio, "ring", AudioManager.STREAM_RING))
                .put("alarm", streamState(audio, "alarm", AudioManager.STREAM_ALARM));
    }

    private static JSONObject streamState(AudioManager audio, String name, int stream) throws JSONException {
        return streamState(audio, name, stream, audio.getStreamVolume(stream));
    }

    private static JSONObject streamState(
            AudioManager audio,
            String name,
            int stream,
            int currentVolume) throws JSONException {
        int maxVolume = audio.getStreamMaxVolume(stream);
        return new JSONObject()
                .put("stream", name)
                .put("volume", currentVolume)
                .put("maxVolume", maxVolume)
                .put("volumePercent", volumePercent(currentVolume, maxVolume))
                .put("muted", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audio.isStreamMute(stream));
    }

    private static int audioStream(String streamName) {
        switch (streamName) {
            case "media":
                return AudioManager.STREAM_MUSIC;
            case "notification":
                return AudioManager.STREAM_NOTIFICATION;
            case "ring":
                return AudioManager.STREAM_RING;
            case "alarm":
                return AudioManager.STREAM_ALARM;
            default:
                throw new IllegalArgumentException("Unsupported audio stream: " + streamName);
        }
    }

    private int currentInterruptionFilter() {
        NotificationManager manager = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (manager == null) {
            return NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
        try {
            return manager.getCurrentInterruptionFilter();
        } catch (RuntimeException ignored) {
            return NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    private static int volumePercent(int volume, int maxVolume) {
        if (maxVolume <= 0) {
            return 0;
        }
        return Math.round((volume * 100f) / maxVolume);
    }

    private static String ringerModeName(int ringerMode) {
        switch (ringerMode) {
            case AudioManager.RINGER_MODE_SILENT:
                return "silent";
            case AudioManager.RINGER_MODE_VIBRATE:
                return "vibrate";
            case AudioManager.RINGER_MODE_NORMAL:
                return "normal";
            default:
                return "unknown";
        }
    }

    private static String interruptionFilterName(int interruptionFilter) {
        switch (interruptionFilter) {
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                return "all";
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                return "priority";
            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return "none";
            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                return "alarms";
            default:
                return "unknown";
        }
    }

    JSONObject recordMicrophone(JSONObject arguments, long callCount) throws JSONException {
        JSONObject unavailable = requireMediaCapability(
                Manifest.permission.RECORD_AUDIO,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                "microphone");
        if (unavailable != null) {
            return unavailable.put("toolCallCount", callCount);
        }

        int durationMs = arguments.optInt("durationMs", 3000);
        boolean returnContent = arguments.optBoolean("returnContent", true);
        int minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            return failure("microphone_unavailable", "Unable to determine an AudioRecord buffer size", callCount);
        }

        AudioRecord recorder = null;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            int bufferSize = Math.max(minBuffer, 4096);
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                return failure("microphone_init_failed", "AudioRecord did not initialize", callCount);
            }

            int targetBytes = (int) ((long) AUDIO_SAMPLE_RATE
                    * AUDIO_BYTES_PER_SAMPLE
                    * durationMs / 1000L);
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(targetBytes);
            byte[] buffer = new byte[bufferSize];
            recorder.startRecording();
            while (pcm.size() < targetBytes) {
                int remaining = targetBytes - pcm.size();
                int read = recorder.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read > 0) {
                    pcm.write(buffer, 0, read);
                } else if (read < 0) {
                    return failure("microphone_read_failed", "AudioRecord read error " + read, callCount);
                }
            }
            recorder.stop();

            byte[] wav = wavBytes(pcm.toByteArray(), AUDIO_SAMPLE_RATE, 1, 16);
            String relativePath = mediaPath("microphone", "wav");
            File output = resolveMediaFile(relativePath);
            writeBytes(output, wav);
            long actualDurationMs = SystemClock.elapsedRealtime() - startedAt;
            JSONObject result = new JSONObject()
                    .put("recorded", true)
                    .put("durationMs", actualDurationMs)
                    .put("requestedDurationMs", durationMs)
                    .put("sampleRate", AUDIO_SAMPLE_RATE)
                    .put("channels", 1)
                    .put("bitsPerSample", 16)
                    .put("mimeType", "audio/wav")
                    .put("path", relativePath)
                    .put("sizeBytes", wav.length)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
            if (returnContent) {
                result.put("_mcpContent", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", "Recorded microphone audio to " + relativePath))
                        .put(new JSONObject()
                                .put("type", "audio")
                                .put("mimeType", "audio/wav")
                                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));
            }
            return result;
        } catch (SecurityException error) {
            return failure("microphone_permission_denied", safeMessage(error), callCount);
        } catch (Throwable error) {
            return failure(
                    "microphone_record_failed",
                    error.getClass().getSimpleName() + ": " + safeMessage(error),
                    callCount);
        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Throwable ignored) {
                }
                recorder.release();
            }
        }
    }

    void shutdown() {
        TextToSpeech tts = textToSpeech;
        textToSpeech = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private void initializeTts() {
        mainHandler.post(() -> {
            try {
                textToSpeech = new TextToSpeech(service.getApplicationContext(), status -> {
                    ttsInitStatus = status;
                    ttsReady.countDown();
                });
            } catch (Throwable error) {
                ttsInitStatus = TextToSpeech.ERROR;
                ttsReady.countDown();
            }
        });
    }

    private void createAgentNotificationChannel() {
        NotificationManager manager = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                AGENT_CHANNEL_ID,
                "Agent messages",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Urgent Agent messages shown on the lock screen and allowed to wake the display");
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);
    }

    private JSONObject requireMediaCapability(String permission, int foregroundType, String capability)
            throws JSONException {
        JSONObject readiness = AndroidCapabilityRegistry.mediaCapabilityState(
                service, new JSONObject(), permission, foregroundType, capability);
        return readiness.optBoolean("available", false) ? null : readiness;
    }

    private boolean hasPermission(String permission) {
        return service.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || hasPermission(Manifest.permission.POST_NOTIFICATIONS);
    }

    private boolean foregroundTypeActive(int type) {
        if (!requiresForegroundTypeOnSdk(Build.VERSION.SDK_INT, type)) {
            return true;
        }
        return foregroundTypeActiveFromQ(type);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private boolean foregroundTypeActiveFromQ(int type) {
        return AndroidCapabilityRegistry.foregroundTypeReady(
                Build.VERSION.SDK_INT, service.getForegroundServiceType(), type);
    }

    static boolean requiresForegroundTypeOnSdk(int sdkInt, int type) {
        if (sdkInt < Build.VERSION_CODES.Q) return false;
        if (sdkInt < Build.VERSION_CODES.R
                && (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                || type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) {
            return false;
        }
        return true;
    }

    private CameraSelection selectCamera(CameraManager manager, String requestedLens, int maxWidth, int maxHeight)
            throws Exception {
        String fallbackId = null;
        String fallbackLens = "unknown";
        CameraCharacteristics fallbackCharacteristics = null;
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            String lens = lensName(facing);
            if (fallbackId == null) {
                fallbackId = cameraId;
                fallbackLens = lens;
                fallbackCharacteristics = characteristics;
            }
            if ("any".equals(requestedLens) || requestedLens.equals(lens)) {
                return selection(cameraId, lens, characteristics, maxWidth, maxHeight);
            }
        }
        if (fallbackId == null || fallbackCharacteristics == null) {
            throw new IllegalStateException("No camera is available on this device");
        }
        if (!"any".equals(requestedLens)) {
            throw new IllegalStateException("Requested camera lens is unavailable: " + requestedLens);
        }
        return selection(fallbackId, fallbackLens, fallbackCharacteristics, maxWidth, maxHeight);
    }

    private CameraSelection selection(
            String cameraId,
            String lens,
            CameraCharacteristics characteristics,
            int maxWidth,
            int maxHeight) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            throw new IllegalStateException("Camera has no JPEG stream configuration");
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            throw new IllegalStateException("Camera exposes no JPEG output size");
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() <= maxWidth && size.getHeight() <= maxHeight) {
                if (best == null || area(size) > area(best)) {
                    best = size;
                }
            }
        }
        if (best == null) {
            best = sizes[0];
            for (Size size : sizes) {
                if (area(size) < area(best)) {
                    best = size;
                }
            }
        }
        return new CameraSelection(
                cameraId,
                lens,
                best,
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static String lensName(Integer lensFacing) {
        if (lensFacing == null) {
            return "unknown";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            return "back";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "front";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "external";
        }
        return "unknown";
    }

    private String mediaPath(String category, String extension) {
        return "media/" + category + "/" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
                + "." + extension;
    }

    private File resolveMediaFile(String relativePath) throws IOException {
        File canonicalRoot = workspaceRoot.getCanonicalFile();
        File output = new File(canonicalRoot, relativePath).getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!output.getPath().startsWith(prefix)) {
            throw new IOException("Media path escaped the workspace root");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create media directory");
        }
        return output;
    }

    private static void writeBytes(File output, byte[] bytes) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(bytes);
            stream.flush();
        }
    }

    private static byte[] wavBytes(byte[] pcm, int sampleRate, int channels, int bitsPerSample)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + pcm.length);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        output.write(new byte[] {'R', 'I', 'F', 'F'});
        writeLe32(output, 36 + pcm.length);
        output.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeLe32(output, 16);
        writeLe16(output, 1);
        writeLe16(output, channels);
        writeLe32(output, sampleRate);
        writeLe32(output, byteRate);
        writeLe16(output, blockAlign);
        writeLe16(output, bitsPerSample);
        output.write(new byte[] {'d', 'a', 't', 'a'});
        writeLe32(output, pcm.length);
        output.write(pcm);
        return output.toByteArray();
    }

    private static void writeLe16(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static JSONObject failure(String code, String message, long callCount) throws JSONException {
        return new JSONObject()
                .put("ok", false)
                .put("errorCode", code)
                .put("message", message == null ? "" : message)
                .put("toolCallCount", callCount);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class CameraSelection {
        final String cameraId;
        final String lens;
        final Size size;
        final Integer sensorOrientation;

        CameraSelection(String cameraId, String lens, Size size, Integer sensorOrientation) {
            this.cameraId = cameraId;
            this.lens = lens;
            this.size = size;
            this.sensorOrientation = sensorOrientation;
        }
    }
}
