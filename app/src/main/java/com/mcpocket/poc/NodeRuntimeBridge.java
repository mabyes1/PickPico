package com.mcpocket.poc;

final class NodeRuntimeBridge {
    static {
        System.loadLibrary("node");
        System.loadLibrary("mcpocket_node_bridge");
    }

    private NodeRuntimeBridge() {
    }

    static native int startNode(String cwd, String[] arguments);
}
