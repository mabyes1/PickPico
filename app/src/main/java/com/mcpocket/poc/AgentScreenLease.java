package com.mcpocket.poc;

/**
 * Display hold for commands and the gaps inside an active Agent task.
 *
 * Every operational Agent command acquires a hold at dispatch start and must
 * release it from the command runtime's finally block. Task activity has a
 * separate idle deadline so a disconnected Agent cannot hold the display forever.
 */
final class AgentScreenLease {
    static final long WATCHDOG_MS = 360_000L;
    static final long IDLE_MS = 180_000L;

    private int activeOperations;
    private long watchdogDeadline;
    private int activeTasks;
    private long taskDeadline;

    synchronized void tasksChanged(int count, long now) {
        activeTasks = Math.max(0, count);
        taskDeadline = activeTasks > 0 ? now + IDLE_MS : 0L;
    }

    synchronized boolean started(String command, long now) {
        if (!isActivity(command)) return false;
        activeOperations++;
        watchdogDeadline = now + WATCHDOG_MS;
        if (activeTasks > 0) taskDeadline = now + IDLE_MS;
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
        boolean expired = false;
        if (activeOperations > 0 && watchdogDeadline > 0L && now >= watchdogDeadline) {
            activeOperations = 0;
            watchdogDeadline = 0L;
            expired = true;
        }
        if (taskDeadline > 0L && now >= taskDeadline) {
            taskDeadline = 0L;
            expired = true;
        }
        return expired;
    }

    synchronized long remaining(long now) {
        long deadline = Math.max(activeOperations > 0 ? watchdogDeadline : 0L,
                activeTasks > 0 ? taskDeadline : 0L);
        return Math.max(0L, deadline - now);
    }

    synchronized boolean holdsScreen(long now) { return remaining(now) > 0L; }

    synchronized void reset() {
        activeOperations = 0;
        watchdogDeadline = 0L;
        activeTasks = 0;
        taskDeadline = 0L;
    }

    static boolean isActivity(String command) {
        return !command.equals("node.info") && !command.equals("guide.get")
                && !command.startsWith("capability.") && !command.equals("policy.status")
                && !command.equals("picoadb.status")
                && !command.equals("app.update_check") && !command.equals("app.update_status");
    }
}
