package com.mcpocket.poc;

/**
 * Operation-scoped display hold.
 *
 * Every operational Agent command acquires a hold at dispatch start and must
 * release it from the command runtime's finally block. A bounded watchdog only
 * exists as crash/hang protection; ordinary task lifetime never owns the screen.
 */
final class AgentScreenLease {
    static final long WATCHDOG_MS = 360_000L;
    // Compatibility name used by older diagnostics/tests while callers migrate.
    static final long IDLE_MS = WATCHDOG_MS;

    private int activeOperations;
    private long watchdogDeadline;

    synchronized boolean started(String command, long now) {
        if (!isActivity(command)) return false;
        activeOperations++;
        watchdogDeadline = now + WATCHDOG_MS;
        return activeOperations == 1;
    }

    synchronized boolean finished(String command) {
        if (!isActivity(command)) return false;
        if (activeOperations > 0) activeOperations--;
        if (activeOperations == 0) watchdogDeadline = 0L;
        return activeOperations == 0;
    }

    synchronized int activeOperations() {
        return activeOperations;
    }

    synchronized boolean expireIfNeeded(long now) {
        if (activeOperations <= 0 || watchdogDeadline <= 0L || now < watchdogDeadline) {
            return false;
        }
        activeOperations = 0;
        watchdogDeadline = 0L;
        return true;
    }

    synchronized long remaining(long now) {
        if (activeOperations <= 0 || watchdogDeadline <= 0L) return 0L;
        return Math.max(0L, watchdogDeadline - now);
    }

    synchronized void reset() {
        activeOperations = 0;
        watchdogDeadline = 0L;
    }

    static boolean isActivity(String command) {
        return !command.equals("node.info") && !command.equals("guide.get")
                && !command.startsWith("capability.") && !command.equals("policy.status")
                && !command.equals("picoadb.status")
                && !command.equals("app.update_check") && !command.equals("app.update_status");
    }
}
