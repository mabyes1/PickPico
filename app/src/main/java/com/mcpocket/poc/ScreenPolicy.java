package com.mcpocket.poc;

import org.json.JSONObject;

/**
 * Single source of truth for which capabilities need the display.
 *
 * Anything not listed here is a background capability: it must not wake the
 * screen, hold it on, or wait for the owner. Reading status, notifications,
 * location, files, running processes and so on all work behind the keyguard.
 */
final class ScreenPolicy {
    private ScreenPolicy() {
    }

    /**
     * Needs an interactive, unlocked display. When the phone is locked the call
     * alerts the owner and pends until they unlock (or the wait times out).
     */
    static boolean requiresUnlock(String commandId, JSONObject arguments) {
        switch (commandId) {
            case "ui.inspect":
            case "ui.action":
            case "ui.type":
            case "ui.scroll":
            case "screen.capture":
            case "app.launch":
            case "url.open":
                return true;
            case "picoadb.set_enabled":
                // Android only lets Wi-Fi debugging come up on an unlocked device.
                // Turning it off is always allowed in the background.
                return arguments != null && arguments.optBoolean("enabled", false);
            default:
                return false;
        }
    }

    /**
     * Owns the display while running and keeps it on between calls of an open
     * task. Only screen-driving work qualifies; background work never does.
     */
    static boolean holdsScreen(String commandId) {
        switch (commandId) {
            case "ui.inspect":
            case "ui.action":
            case "ui.type":
            case "ui.scroll":
            case "screen.capture":
            case "app.launch":
            case "url.open":
            case "phone.wake":
            case "phone.home":
                return true;
            default:
                return false;
        }
    }
}
