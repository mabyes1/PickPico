package com.mcpocket.poc;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.Arrays;

/** Canonical launcher quick actions. Re-published on process start to replace stale dynamic entries. */
final class LauncherShortcuts {
    static final String ACTION_ACTIVITY = "com.mcpocket.poc.shortcut.ACTIVITY";
    static final String ACTION_CAPABILITIES = "com.mcpocket.poc.shortcut.CAPABILITIES";
    static final String ACTION_SETTINGS = "com.mcpocket.poc.shortcut.SETTINGS";

    private LauncherShortcuts() {
    }

    static void publish(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;

        ShortcutInfo activity = shortcut(
                context,
                "activity",
                "Activity",
                "Open Agent Activity",
                ACTION_ACTIVITY,
                0);
        ShortcutInfo capabilities = shortcut(
                context,
                "capabilities",
                "Capabilities",
                "Open Capabilities",
                ACTION_CAPABILITIES,
                1);
        ShortcutInfo settings = shortcut(
                context,
                "settings",
                "Settings",
                "Open PickPico Settings",
                ACTION_SETTINGS,
                2);

        // setDynamicShortcuts replaces the complete dynamic set, so shortcuts left behind by
        // older builds cannot keep targeting a renamed/non-exported Activity indefinitely.
        try {
            manager.setDynamicShortcuts(Arrays.asList(activity, capabilities, settings));
        } catch (RuntimeException error) {
            // Launcher shortcuts are convenience UI, never a reason to prevent the Agent node
            // process from starting (for example if a vendor launcher is temporarily unavailable).
            Log.w("PickPico", "Unable to refresh launcher shortcuts", error);
        }
    }

    private static ShortcutInfo shortcut(
            Context context,
            String id,
            String shortLabel,
            String longLabel,
            String action,
            int rank) {
        Intent intent = new Intent(context, DashboardActivity.class)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setRank(rank)
                .setIntent(intent)
                .build();
    }
}
