package com.mcpocket.poc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/**
 * Auto-connects PickPico to the tiny four-button BLE remote used by the phone stand.
 *
 * Packet v1 is deliberately tiny: [protocolVersion, event, sequence]. The ESP32 is a
 * dumb input device; all human-help state, microphone capture, and Agent delivery stay
 * on Android.
 */
final class BleButtonBridge {
    static final UUID SERVICE_UUID = UUID.fromString("5f7c0001-8ec5-4d31-9ba1-1b99d0c6a001");
    static final UUID EVENT_CHARACTERISTIC_UUID = UUID.fromString("5f7c0002-8ec5-4d31-9ba1-1b99d0c6a001");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int PROTOCOL_VERSION = 1;
    private static final int EVENT_APPROVE = 1;
    private static final int EVENT_REJECT = 2;
    private static final int EVENT_DETAIL = 3;
    private static final int EVENT_VOICE_DOWN = 4;
    private static final int EVENT_VOICE_UP = 5;

    private static final long RETRY_MS = 4_000L;
    private static final long SCAN_WINDOW_MS = 12_000L;
    private static final long MAX_VOICE_MS = 45_000L;

    private final McpNodeService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean active;
    private boolean scanning;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private VoiceRecorder voiceRecorder;
    private String voiceRequestId;
    private SpeechRecognizer voiceSpeechRecognizer;
    private String voiceTranscript = "";
    private String voiceSpeechStatus = "idle";
    private boolean voiceRecognitionFinished = true;
    private String pendingVoiceReviewRequestId;
    private String pendingVoiceReviewPath;
    private boolean pendingVoiceReviewTimedOut;

    BleButtonBridge(McpNodeService service) {
        this.service = service;
    }

    void start() {
        if (active) {
            return;
        }
        active = true;
        mainHandler.post(this::scanOrRetry);
    }

    void stop() {
        active = false;
        mainHandler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        cancelVoice();
    }

    private void scanOrRetry() {
        if (!active || gatt != null) {
            return;
        }
        if (!hasBluetoothPermissions()) {
            recordRecent("Button pad waiting for Bluetooth permission");
            scheduleRetry();
            return;
        }

        BluetoothManager manager = service.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            recordRecent("Button pad waiting for Bluetooth");
            scheduleRetry();
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scheduleRetry();
            return;
        }
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanning = true;
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            mainHandler.postDelayed(() -> {
                if (scanning) {
                    stopScan();
                    scheduleRetry();
                }
            }, SCAN_WINDOW_MS);
        } catch (SecurityException error) {
            scanning = false;
            recordRecent("Button pad scan blocked: " + safeMessage(error));
            scheduleRetry();
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result == null ? null : result.getDevice();
            if (!active || device == null || gatt != null) {
                return;
            }
            stopScan();
            try {
                gatt = device.connectGatt(service, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                recordRecent("Button pad connecting");
            } catch (SecurityException error) {
                gatt = null;
                recordRecent("Button pad connect blocked: " + safeMessage(error));
                scheduleRetry();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            recordRecent("Button pad scan failed: " + errorCode);
            scheduleRetry();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            if (callbackGatt != gatt) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    callbackGatt.discoverServices();
                } catch (SecurityException error) {
                    disconnectAndRetry("Button pad discovery blocked: " + safeMessage(error));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectAndRetry("Button pad disconnected");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            if (callbackGatt != gatt || status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndRetry("Button pad service discovery failed: " + status);
                return;
            }
            BluetoothGattService remoteService = callbackGatt.getService(SERVICE_UUID);
            BluetoothGattCharacteristic events = remoteService == null
                    ? null
                    : remoteService.getCharacteristic(EVENT_CHARACTERISTIC_UUID);
            if (events == null) {
                disconnectAndRetry("Button pad protocol mismatch");
                return;
            }
            try {
                if (!callbackGatt.setCharacteristicNotification(events, true)) {
                    disconnectAndRetry("Button pad notifications unavailable");
                    return;
                }
                BluetoothGattDescriptor descriptor = events.getDescriptor(CLIENT_CONFIG_UUID);
                if (descriptor == null) {
                    disconnectAndRetry("Button pad CCC descriptor missing");
                    return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!callbackGatt.writeDescriptor(descriptor)) {
                    disconnectAndRetry("Button pad notification setup failed");
                    return;
                }
                recordRecent("Button pad connected");
            } catch (SecurityException error) {
                disconnectAndRetry("Button pad setup blocked: " + safeMessage(error));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic != null && EVENT_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                handlePacket(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt callbackGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            if (characteristic != null && EVENT_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                handlePacket(value);
            }
        }
    };

    private void handlePacket(byte[] packet) {
        if (!active || packet == null || packet.length == 0) {
            return;
        }
        int event;
        if (packet.length >= 2 && (packet[0] & 0xff) == PROTOCOL_VERSION) {
            event = packet[1] & 0xff;
        } else if (packet.length == 1) {
            event = packet[0] & 0xff;
        } else {
            return;
        }

        switch (event) {
            case EVENT_APPROVE:
                handlePreset(true);
                break;
            case EVENT_REJECT:
                handlePreset(false);
                break;
            case EVENT_DETAIL:
                handleDetail();
                break;
            case EVENT_VOICE_DOWN:
                handleVoiceDown();
                break;
            case EVENT_VOICE_UP:
                handleVoiceUp();
                break;
            default:
                break;
        }
    }

    private void handlePreset(boolean approve) {
        try {
            JSONObject request = HumanHelpStore.latestWaiting(service);
            if (request == null) {
                recordRecent("Button pad: no pending human-help request");
                return;
            }
            cancelVoice();
            String requestId = request.optString("requestId", "");
            String action = presetAction(request, approve);
            HumanHelpStore.complete(service, requestId, action, "");
            recordRecent("Button pad: " + (approve ? "approved" : "rejected") + " " + requestId);
        } catch (Exception error) {
            recordRecent("Button pad action failed: " + safeMessage(error));
        }
    }

    private void handleDetail() {
        try {
            JSONObject request = HumanHelpStore.latestWaiting(service);
            if (request == null) {
                recordRecent("Button pad: no pending detail");
                return;
            }
            String requestId = request.optString("requestId", "");
            HumanHelpStore.renewHumanActivity(service, requestId, "ble_detail", false);
            Intent intent = new Intent(service, HumanHelpActivity.class)
                    .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, requestId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            service.startActivity(intent);
            recordRecent("Button pad: opened human-help detail");
        } catch (Exception error) {
            recordRecent("Button pad detail failed: " + safeMessage(error));
        }
    }

    private synchronized void handleVoiceDown() {
        if (voiceRequestId != null) {
            return;
        }
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordRecent("Button pad voice needs microphone permission");
            return;
        }
        try {
            JSONObject request = HumanHelpStore.latestWaiting(service);
            if (request == null) {
                recordRecent("Button pad: no pending request for voice");
                return;
            }
            voiceRequestId = request.optString("requestId", "");
            HumanHelpStore.renewHumanActivity(service, voiceRequestId, "ble_voice", false);
            startVoiceRecognition();
            recordRecent("Button pad: voice STT listening");
            mainHandler.postDelayed(this::finishVoiceFromTimeout, MAX_VOICE_MS);
        } catch (Exception error) {
            cancelVoice();
            recordRecent("Button pad voice failed: " + safeMessage(error));
        }
    }

    private synchronized void handleVoiceUp() {
        finishVoice(false);
    }

    private void finishVoiceFromTimeout() {
        synchronized (this) {
            finishVoice(true);
        }
    }

    private void finishVoice(boolean timedOut) {
        String requestId = voiceRequestId;
        voiceRequestId = null;
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        try {
            HumanHelpStore.renewHumanActivity(service, requestId, "ble_voice_review", false);
            pendingVoiceReviewRequestId = requestId;
            pendingVoiceReviewPath = "";
            pendingVoiceReviewTimedOut = timedOut;
            requestVoiceRecognitionFinish();
        } catch (Exception error) {
            recordRecent("Button pad voice review failed: " + safeMessage(error));
        }
    }

    private void startVoiceRecognition() {
        voiceTranscript = "";
        voiceSpeechStatus = "starting";
        voiceRecognitionFinished = false;
        mainHandler.post(() -> {
            destroyVoiceSpeechRecognizer();
            if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                voiceSpeechStatus = "unavailable";
                voiceRecognitionFinished = true;
                maybeLaunchPendingVoiceReview();
                return;
            }
            try {
                SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(service);
                voiceSpeechRecognizer = recognizer;
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {
                        voiceSpeechStatus = "ready";
                    }

                    @Override public void onBeginningOfSpeech() {
                        voiceSpeechStatus = "listening";
                    }

                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }

                    @Override public void onEndOfSpeech() {
                        voiceSpeechStatus = "processing";
                    }

                    @Override public void onError(int error) {
                        voiceSpeechStatus = "error:" + error;
                        voiceRecognitionFinished = true;
                        destroyVoiceSpeechRecognizer();
                        maybeLaunchPendingVoiceReview();
                    }

                    @Override public void onResults(Bundle results) {
                        String result = bestSpeechResult(results);
                        if (!result.isEmpty()) {
                            voiceTranscript = result;
                        }
                        voiceSpeechStatus = voiceTranscript.isEmpty() ? "no_result" : "ok";
                        voiceRecognitionFinished = true;
                        destroyVoiceSpeechRecognizer();
                        maybeLaunchPendingVoiceReview();
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        String result = bestSpeechResult(partialResults);
                        if (!result.isEmpty()) {
                            voiceTranscript = result;
                            voiceSpeechStatus = "partial";
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) { }
                });
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
                recognizer.startListening(intent);
            } catch (RuntimeException error) {
                voiceSpeechStatus = "start_failed:" + error.getClass().getSimpleName();
                voiceRecognitionFinished = true;
                destroyVoiceSpeechRecognizer();
                maybeLaunchPendingVoiceReview();
            }
        });
    }

    private void requestVoiceRecognitionFinish() {
        mainHandler.post(() -> {
            SpeechRecognizer recognizer = voiceSpeechRecognizer;
            if (recognizer != null && !voiceRecognitionFinished) {
                try {
                    recognizer.stopListening();
                } catch (RuntimeException error) {
                    voiceSpeechStatus = "stop_failed:" + error.getClass().getSimpleName();
                    voiceRecognitionFinished = true;
                    destroyVoiceSpeechRecognizer();
                }
            }
            if (voiceRecognitionFinished) {
                maybeLaunchPendingVoiceReview();
            } else {
                mainHandler.postDelayed(this::forceVoiceRecognitionFinish, 1_800L);
            }
        });
    }

    private void forceVoiceRecognitionFinish() {
        if (pendingVoiceReviewRequestId == null || voiceRecognitionFinished) {
            maybeLaunchPendingVoiceReview();
            return;
        }
        voiceSpeechStatus = voiceTranscript.isEmpty() ? "timeout" : "partial_timeout";
        voiceRecognitionFinished = true;
        SpeechRecognizer recognizer = voiceSpeechRecognizer;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        destroyVoiceSpeechRecognizer();
        maybeLaunchPendingVoiceReview();
    }

    private void maybeLaunchPendingVoiceReview() {
        if (!voiceRecognitionFinished
                || pendingVoiceReviewRequestId == null
                || pendingVoiceReviewRequestId.isEmpty()) {
            return;
        }
        String requestId = pendingVoiceReviewRequestId;
        String path = pendingVoiceReviewPath == null ? "" : pendingVoiceReviewPath;
        boolean timedOut = pendingVoiceReviewTimedOut;
        pendingVoiceReviewRequestId = null;
        pendingVoiceReviewPath = null;
        pendingVoiceReviewTimedOut = false;
        Intent intent = new Intent(service, HumanHelpActivity.class)
                .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, requestId)
                .putExtra(HumanHelpActivity.EXTRA_CONFIRM_BLE_VOICE, true)
                .putExtra(HumanHelpActivity.EXTRA_BLE_VOICE_PATH, path)
                .putExtra(HumanHelpActivity.EXTRA_BLE_VOICE_TRANSCRIPT, voiceTranscript)
                .putExtra(HumanHelpActivity.EXTRA_BLE_VOICE_STT_STATUS, voiceSpeechStatus)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        service.startActivity(intent);
        recordRecent("Button pad: voice review STT=" + voiceSpeechStatus
                + (voiceTranscript.isEmpty() ? "" : " text=" + voiceTranscript)
                + (timedOut ? " (45s max)" : ""));
    }

    private static String bestSpeechResult(Bundle results) {
        if (results == null) {
            return "";
        }
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty() || matches.get(0) == null) {
            return "";
        }
        return matches.get(0).trim();
    }

    private void destroyVoiceSpeechRecognizer() {
        SpeechRecognizer recognizer = voiceSpeechRecognizer;
        voiceSpeechRecognizer = null;
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private synchronized void cancelVoice() {
        if (voiceRecorder != null) {
            voiceRecorder.cancel();
        }
        voiceRecorder = null;
        voiceRequestId = null;
        pendingVoiceReviewRequestId = null;
        pendingVoiceReviewPath = null;
        pendingVoiceReviewTimedOut = false;
        mainHandler.post(() -> {
            SpeechRecognizer recognizer = voiceSpeechRecognizer;
            if (recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (RuntimeException ignored) {
                }
            }
            destroyVoiceSpeechRecognizer();
            voiceRecognitionFinished = true;
        });
    }

    private String presetAction(JSONObject request, boolean approve) {
        JSONArray actions = request.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            return approve ? "允許" : "拒絕";
        }
        String[] approveWords = {"允許", "核准", "同意", "完成", "allow", "approve", "yes", "ok"};
        String[] rejectWords = {"拒絕", "否決", "做不到", "deny", "reject", "no", "cancel"};
        String[] words = approve ? approveWords : rejectWords;
        for (int index = 0; index < actions.length(); index++) {
            String action = actions.optString(index, "");
            String normalized = action.toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (normalized.contains(word.toLowerCase(Locale.ROOT))) {
                    return action;
                }
            }
        }
        int fallback = approve ? 0 : Math.min(1, actions.length() - 1);
        return actions.optString(fallback, approve ? "允許" : "拒絕");
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return service.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && service.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return service.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || service.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void stopScan() {
        if (!scanning || scanner == null) {
            scanning = false;
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
        scanning = false;
    }

    private void disconnectAndRetry(String message) {
        recordRecent(message);
        closeGatt();
        scheduleRetry();
    }

    private void closeGatt() {
        BluetoothGatt current = gatt;
        gatt = null;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (SecurityException ignored) {
        }
        current.close();
    }

    private void scheduleRetry() {
        if (!active) {
            return;
        }
        mainHandler.removeCallbacks(this::scanOrRetry);
        mainHandler.postDelayed(this::scanOrRetry, RETRY_MS);
    }

    private void recordRecent(String message) {
        service.getSharedPreferences(McpNodeService.PREFS, McpNodeService.MODE_PRIVATE).edit()
                .putString(McpNodeService.KEY_RECENT, message + "\n" + Instant.now())
                .apply();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isEmpty()
                ? (error == null ? "unknown" : error.getClass().getSimpleName())
                : message;
    }

    private static final class VoiceRecorder {
        private static final int SAMPLE_RATE = 16_000;
        private static final int BIT_RATE = 32_000;

        private final File outputFile;
        private MediaRecorder recorder;

        VoiceRecorder(File cacheDir) throws IOException {
            File dir = new File(cacheDir, "ble-voice");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("Unable to create voice cache directory");
            }
            outputFile = File.createTempFile("voice-", ".m4a", dir);
        }

        void start() throws IOException {
            try {
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioChannels(1);
                recorder.setAudioSamplingRate(SAMPLE_RATE);
                recorder.setAudioEncodingBitRate(BIT_RATE);
                recorder.setOutputFile(outputFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
            } catch (RuntimeException error) {
                cancel();
                throw new IOException("Microphone start failed", error);
            }
        }

        byte[] stopAndRead() throws IOException {
            stopRecorder();
            try (FileInputStream input = new FileInputStream(outputFile)) {
                byte[] bytes = input.readAllBytes();
                outputFile.delete();
                return bytes;
            }
        }

        void cancel() {
            try {
                stopRecorder();
            } catch (IOException ignored) {
            }
            outputFile.delete();
        }

        private void stopRecorder() throws IOException {
            MediaRecorder current = recorder;
            recorder = null;
            if (current != null) {
                try {
                    current.stop();
                } catch (RuntimeException error) {
                    outputFile.delete();
                    throw new IOException("Voice recording was too short or invalid", error);
                } finally {
                    current.reset();
                    current.release();
                }
            }
        }
    }
}
