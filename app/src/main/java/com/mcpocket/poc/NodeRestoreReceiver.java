package com.mcpocket.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.time.Instant;

/** Restores the base MCP node after an in-place app update when the user left it running. */
public final class NodeRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(McpNodeService.PREFS, Context.MODE_PRIVATE);
        boolean desiredRunning = prefs.getBoolean(McpNodeService.KEY_DESIRED_RUNNING, false);
        String token = prefs.getString(McpNodeService.KEY_TOKEN, "");

        JSONObject updateState = SelfUpdateState.read(context);
        SelfUpdateState.put(updateState, "nodeRestoreAt", Instant.now().toString());

        if (!desiredRunning) {
            SelfUpdateState.put(updateState, "nodeRestoreRequested", false);
            SelfUpdateState.put(updateState, "nodeRestoreReason", "Node was not running before package replacement");
            SelfUpdateState.write(context, updateState);
            return;
        }
        if (TextUtils.isEmpty(token)) {
            SelfUpdateState.put(updateState, "nodeRestoreRequested", false);
            SelfUpdateState.put(updateState, "nodeRestoreReason", "Persisted MCP bearer token is unavailable");
            SelfUpdateState.write(context, updateState);
            return;
        }

        Intent service = new Intent(context, McpNodeService.class)
                .setAction(McpNodeService.ACTION_START)
                .putExtra(McpNodeService.EXTRA_TOKEN, token)
                // Package-replaced runs in the background. Restore the network/runtime node first;
                // media foreground types are safely refreshed when the PickPico UI is visible again.
                .putExtra(McpNodeService.EXTRA_ENABLE_MEDIA_FGS, false);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            SelfUpdateState.put(updateState, "nodeRestoreRequested", true);
            SelfUpdateState.put(updateState, "nodeRestoreMode", "base_runtime");
        } catch (Throwable error) {
            SelfUpdateState.put(updateState, "nodeRestoreRequested", false);
            SelfUpdateState.put(
                    updateState,
                    "nodeRestoreError",
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        SelfUpdateState.write(context, updateState);
    }
}
