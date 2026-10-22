package com.mcpocket.poc;

/**
 * Coordinates two distinct display/security leases for Agent work:
 *
 * 1. Display Wake Hold ({@link #holdsScreen}): Held only while a Category 1
 *    screen-driving command ({@link ScreenPolicy#holdsScreen}) is actively
 *    executing, bounded by {@link #WATCHDOG_MS}. Released immediately when the
 *    command finishes so the display can dim and turn off naturally.
 * 2. Extend Unlock Lease ({@link #extendsUnlock}): Held for {@link #IDLE_MS}
 *    (3 minutes) while an Agent task is active, or for an explicit duration
 *    requested via {@code phone.extend_unlock}. Keeps the phone unlocked even
 *    if the screen turns off (including via the power button), so the next
 *    Category 1 command can wake the screen and proceed without bothering the
 *    owner.
 */
final class AgentScreenLease {
    static final long WATCHDOG_MS = 360_000L;
    static final long IDLE_MS = 180_000L;

    private int activeOperations;
    private long watchdogDeadline;
    private int activeTasks;
    private long taskDeadline;
    private long manualUnlockDeadline;

    synchronized void tasksChanged(int count, long now) {
        activeTasks = Math.max(0, count);
        taskDeadline = activeTasks > 0 ? now + IDLE_MS : 0L;
    }

    /** Renews the 3-minute task extend-unlock window on active task progress. */
    synchronized void touchTask(long now) {
        if (activeTasks > 0) {
            taskDeadline = Math.max(taskDeadline, now + IDLE_MS);
        }
    }

    /** Explicitly extends unlock for a bounded duration (default 3 minutes). */
    synchronized long extendUnlock(long durationMs, long now) {
        long bounded = Math.max(
                ExtendUnlockControl.MIN_EXTEND_MS,
                Math.min(ExtendUnlockControl.MAX_EXTEND_MS, durationMs));
        manualUnlockDeadline = Math.max(manualUnlockDeadline, now + bounded);
        return Math.max(0L, manualUnlockDeadline - now);
    }

    synchronized void releaseManualUnlock() {
        manualUnlockDeadline = 0L;
    }

    synchronized boolean started(String command, long now) {
        if (activeTasks > 0) {
            taskDeadline = Math.max(taskDeadline, now + IDLE_MS);
        }
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
        if (manualUnlockDeadline > 0L && now >= manualUnlockDeadline) {
            manualUnlockDeadline = 0L;
            expired = true;
        }
        return expired;
    }

    synchronized long remainingScreenHold(long now) {
        long deadline = activeOperations > 0 ? watchdogDeadline : 0L;
        return Math.max(0L, deadline - now);
    }

    synchronized long remainingUnlockExtension(long now) {
        long task = activeTasks > 0 ? taskDeadline : 0L;
        long deadline = Math.max(task, manualUnlockDeadline);
        return Math.max(0L, deadline - now);
    }

    synchronized long remaining(long now) {
        return Math.max(remainingScreenHold(now), remainingUnlockExtension(now));
    }

    synchronized boolean holdsScreen(long now) {
        return remainingScreenHold(now) > 0L;
    }

    synchronized boolean extendsUnlock(long now) {
        return remainingUnlockExtension(now) > 0L;
    }

    synchronized boolean hasTaskLease(long now) {
        return activeTasks > 0 && taskDeadline > now;
    }

    synchronized boolean hasManualUnlockLease(long now) {
        return manualUnlockDeadline > now;
    }

    synchronized void reset() {
        activeOperations = 0;
        watchdogDeadline = 0L;
        activeTasks = 0;
        taskDeadline = 0L;
        manualUnlockDeadline = 0L;
    }

    /** Allowlist: only Category 1 screen-driving capabilities own the display. */
    static boolean isActivity(String command) {
        return ScreenPolicy.holdsScreen(command);
    }
}
