package com.mcpocket.poc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.pm.ServiceInfo;

import org.json.JSONObject;
import org.junit.Test;

public final class SelfUpdateManagerTest {
    @Test
    public void detectsOnlyInterruptedInFlightStates() throws Exception {
        assertTrue(SelfUpdateManager.isInFlightState(new JSONObject().put("status", "downloading")));
        assertTrue(SelfUpdateManager.isInFlightState(new JSONObject().put("status", "staging")));
        assertFalse(SelfUpdateManager.isInFlightState(new JSONObject().put("status", "failed")));
        assertFalse(SelfUpdateManager.isInFlightState(new JSONObject().put("status", "pending_user_action")));
        assertFalse(SelfUpdateManager.isInFlightState(null));
    }

    @Test
    public void updateChannelUsesConfiguredRelayOrExplicitManifest() throws Exception {
        assertEquals(
                "https://relay.example.test/v1/update/latest",
                SelfUpdateManager.updateManifestForRelay("https://relay.example.test/"));
        assertEquals(
                "https://updates.example.test/latest.json",
                SelfUpdateManager.resolveManifestUrl(
                        null,
                        new JSONObject().put("manifestUrl", "https://updates.example.test/latest.json")));
    }

    @Test(expected = CommandRuntime.CommandInputException.class)
    public void missingUpdateSourceDoesNotUseProjectInfrastructure() {
        SelfUpdateManager.updateManifestForRelay("");
    }

    @Test
    public void marksAnInstalledCandidateOnlyOnce() throws Exception {
        JSONObject pending = new JSONObject().put("status", "pending_user_action");
        JSONObject installed = new JSONObject().put("status", "installed");

        assertTrue(SelfUpdateManager.shouldMarkInstalled(pending, 64L, 64L));
        assertTrue(SelfUpdateManager.shouldMarkInstalled(pending, 65L, 64L));
        assertFalse(SelfUpdateManager.shouldMarkInstalled(installed, 64L, 64L));
        assertFalse(SelfUpdateManager.shouldMarkInstalled(pending, 63L, 64L));
        assertFalse(SelfUpdateManager.shouldMarkInstalled(pending, 64L, -1L));
    }

    @Test
    public void android10DoesNotRequireCameraOrMicrophoneForegroundTypes() {
        assertFalse(AndroidDeviceCapabilities.requiresForegroundTypeOnSdk(
                29, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
        assertFalse(AndroidDeviceCapabilities.requiresForegroundTypeOnSdk(
                29, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE));
        assertTrue(AndroidDeviceCapabilities.requiresForegroundTypeOnSdk(
                29, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION));
        assertTrue(AndroidDeviceCapabilities.requiresForegroundTypeOnSdk(
                30, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
        assertTrue(AndroidDeviceCapabilities.requiresForegroundTypeOnSdk(
                30, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE));
    }

    @Test
    public void capabilityRegistryRequiresTheActualMediaForegroundType() {
        assertTrue(AndroidCapabilityRegistry.foregroundTypeReady(
                29,
                0,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
        assertFalse(AndroidCapabilityRegistry.foregroundTypeReady(
                30,
                0,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
        assertTrue(AndroidCapabilityRegistry.foregroundTypeReady(
                30,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
        assertFalse(AndroidCapabilityRegistry.foregroundTypeReady(
                30,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA));
    }
}
