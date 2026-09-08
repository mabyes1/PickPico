package com.mcpocket.poc;

/** One semantic state for every Pico orb surface. No Android lifecycle or window code lives here. */
final class PicoOrbState {
    static final String HIDDEN = "hidden";
    static final String HUMAN_HELP = "human_help";
    static final String BLOCKED = "blocked";
    static final String RUNNING = "running";
    static final String CONNECTING = "connecting";
    static final String COMPLETED = "completed";
    static final String CONNECTION_ATTENTION = "connection_attention";
    static final String READY = "ready";

    static final long COMPLETED_VISIBLE_MS = 5_000L;

    static final int RED = 0xffb6534f;
    static final int ORANGE = 0xffc9784a;
    static final int YELLOW = 0xffc9a84c;
    static final int CYAN = 0xff5fae9b;
    static final int GREEN = 0xff6fa06d;

    private PicoOrbState() {
    }

    static String resolve(
            boolean nodeRunning,
            boolean humanHelp,
            boolean blocked,
            boolean running,
            boolean connecting,
            boolean completedRecently,
            boolean connectionAttention) {
        if (!nodeRunning) return HIDDEN;
        if (humanHelp) return HUMAN_HELP;
        if (blocked) return BLOCKED;
        if (running) return RUNNING;
        if (connecting) return CONNECTING;
        if (completedRecently) return COMPLETED;
        if (connectionAttention) return CONNECTION_ATTENTION;
        return READY;
    }

    static int primary(String mode, int themeColor) {
        switch (mode) {
            case HUMAN_HELP: return CYAN;
            case BLOCKED: return RED;
            case RUNNING: return YELLOW;
            case CONNECTING: return ORANGE;
            case COMPLETED: return GREEN;
            case CONNECTION_ATTENTION: return ORANGE;
            default: return themeColor;
        }
    }

    static int secondary(String mode, int themeColor) {
        switch (mode) {
            case HUMAN_HELP: return 0xff8dc8b9;
            case BLOCKED: return 0xffd68a82;
            case RUNNING: return 0xffdac77a;
            case CONNECTING: return 0xffd99b73;
            case COMPLETED: return 0xff94ba8d;
            case CONNECTION_ATTENTION: return 0xffd99b73;
            default: return themeColor;
        }
    }

    static String label(String mode) {
        switch (mode) {
            case HUMAN_HELP: return "Human Help";
            case BLOCKED: return "Task needs attention";
            case RUNNING: return "PickPico is working";
            case CONNECTING:
            case CONNECTION_ATTENTION: return "Connecting PickPico";
            case COMPLETED: return "Task completed";
            default: return "PickPico is ready";
        }
    }
}
