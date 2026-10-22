package com.mcpocket.poc;

import org.json.JSONObject;

/**
 * Single source of truth for the three capability display/unlock tiers:
 *
 * 1. {@link Tier#INTERACTIVE_UNLOCKED}: Needs the display lit AND the keyguard
 *    unlocked. If locked, alerts the owner and pends for unlock. If unlocked
 *    with the screen dark (e.g. during Extend Unlock), wakes the display
 *    automatically without bothering the owner and holds it while executing.
 * 2. {@link Tier#UNLOCKED_ONLY}: Needs the keyguard unlocked, but does NOT need
 *    the display lit. If already unlocked, executes silently even while the
 *    screen is off. If locked, alerts the owner and pends for unlock.
 * 3. {@link Tier#BACKGROUND}: Needs neither display nor unlock. Executes
 *    silently behind a dark, locked screen.
 */
final class ScreenPolicy {
    enum Tier {
        /** Category 1: 需要點亮 ＋ 需要解鎖 */
        INTERACTIVE_UNLOCKED,
        /** Category 2: 不需要點亮 ＋ 但需要解鎖 */
        UNLOCKED_ONLY,
        /** Category 3: 不需要點亮 ＋ 不需要解鎖 */
        BACKGROUND
    }

    private ScreenPolicy() {
    }

    static Tier tier(String commandId, JSONObject arguments) {
        if (commandId == null) return Tier.BACKGROUND;
        switch (commandId) {
            case "ui.inspect":
            case "ui.action":
            case "ui.type":
            case "ui.scroll":
            case "screen.capture":
            case "app.launch":
            case "url.open":
            case "phone.home":
                return Tier.INTERACTIVE_UNLOCKED;
            case "picoadb.set_enabled":
                // Android only lets Wi-Fi debugging come up on an unlocked device.
                // Turning it off is always allowed in the background.
                return arguments != null && arguments.optBoolean("enabled", false)
                        ? Tier.UNLOCKED_ONLY
                        : Tier.BACKGROUND;
            case "phone.extend_unlock": {
                String action = arguments == null ? "extend" : arguments.optString("action", "extend");
                return "extend".equals(action) ? Tier.UNLOCKED_ONLY : Tier.BACKGROUND;
            }
            default:
                return Tier.BACKGROUND;
        }
    }

    /**
     * True for Category 1 and Category 2 capabilities that cannot proceed while
     * the device keyguard is locked.
     */
    static boolean requiresUnlock(String commandId, JSONObject arguments) {
        return tier(commandId, arguments) != Tier.BACKGROUND;
    }

    /**
     * True when the capability requires the physical display to be powered on
     * (Category 1 capabilities plus the explicit {@code phone.wake} tool).
     */
    static boolean requiresInteractiveDisplay(String commandId, JSONObject arguments) {
        return tier(commandId, arguments) == Tier.INTERACTIVE_UNLOCKED
                || "phone.wake".equals(commandId);
    }

    /**
     * Owns the display wake lock while the single operation is actively running.
     * Category 2 and Category 3 capabilities never hold the display on.
     */
    static boolean holdsScreen(String commandId) {
        return tier(commandId, null) == Tier.INTERACTIVE_UNLOCKED
                || "phone.wake".equals(commandId);
    }
}
