package com.mcpocket.poc;

/** Monotonic activity lease, independent of command completion and task retention. */
final class AgentScreenLease {
    static final long IDLE_MS = 180_000L;
    private int unfinishedTasks;
    private long deadline;

    synchronized void tasksChanged(int count, long now) {
        unfinishedTasks = count;
        if (count > 0) activity(now); else deadline = 0;
    }

    synchronized void activity(long now) {
        if (unfinishedTasks > 0) deadline = now + IDLE_MS;
    }

    synchronized long remaining(long now) {
        return unfinishedTasks > 0 ? Math.max(0, deadline - now) : 0;
    }

    static boolean isActivity(String command) {
        return !command.equals("node.info") && !command.equals("guide.get")
                && !command.startsWith("capability.") && !command.equals("policy.status")
                && !command.equals("app.update_check") && !command.equals("app.update_status");
    }
}
