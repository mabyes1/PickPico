package com.mcpocket.poc;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Small vocabulary shared by top-level discovery and deterministic fallback search. */
final class CapabilityIndex {
    static final Set<String> DIRECT = new LinkedHashSet<>(Arrays.asList(
            "phone.status", "phone.wake", "phone.home", "camera.capture", "phone.speak",
            "human.help", "human.help.status", "notification.list", "notification.reply",
            "ui.inspect", "ui.action", "ui.type", "ui.scroll", "screen.capture",
            "app.list", "app.launch", "url.open", "location.get"));
    static String tool(String id) {
        if (DIRECT.contains(id)) return id.replace('.', '_');
        switch (id) {
            case "node.info": return "server_info";
            case "capability.list": return "capability_list";
            case "capability.status": return "capability_status";
            case "policy.status": return "policy_status";
            default: return "command_run";
        }
    }
    static String label(String id) {
        switch (id) {
            case "guide.get": return "Read an operation guide";
            case "node.info": return "Device and connection information";
            case "phone.status": return "Battery, network and storage";
            case "capability.list": return "All capability names and availability";
            case "capability.status": return "Exact input schema and availability";
            case "policy.status": return "Local approval policy";
            case "phone.ring": return "Start or stop a find-phone ring";
            case "phone.lock": return "Lock the screen";
            case "phone.wake": return "Wake display; does not unlock or navigate";
            case "phone.home": return "Wake, request system unlock, then go Home";
            case "camera.capture": return "Take a camera photo, not a screenshot";
            case "phone.notify": return "Post a phone notification";
            case "phone.speak": return "Speak text aloud";
            case "audio.status": return "Read media, ring, alarm and notification volume";
            case "audio.set": return "Set one audio stream volume percentage";
            case "microphone.record": return "Record a short microphone clip";
            case "human.help": return "Ask the nearby human for one step";
            case "human.help.status": return "Read a human-help response by requestId";
            case "notification.list": return "List active notifications";
            case "notification.get": return "Read one notification by key";
            case "notification.dismiss": return "Clear one notification by key";
            case "notification.actions": return "Read notification buttons and reply options";
            case "notification.invoke_action": return "Invoke a discovered notification button";
            case "notification.reply": return "Send a reply via a notification";
            case "ui.inspect": return "Read the current UI and observationId";
            case "ui.action": return "Click a unique UI target or back/home/recents";
            case "ui.type": return "Replace or append text in a UI field";
            case "ui.scroll": return "Scroll a UI container";
            case "screen.capture": return "Take a screen image";
            case "app.list": return "Find installed apps and package names";
            case "app.launch": return "Open an installed app by package name";
            case "url.open": return "Open a URL or supported app link";
            case "location.get": return "Read current location and accuracy";
            case "clipboard.get": return "Read clipboard text";
            case "clipboard.set": return "Replace clipboard text";
            case "contacts.search": return "Find contacts by name";
            case "contacts.get": return "Read contact details by discovered id";
            case "calendar.list": return "Read calendar events in a time range";
            case "calendar.get": return "Read an event by eventId";
            case "calendar.create": return "Create a calendar event";
            case "calendar.update": return "Edit an event by eventId";
            case "calendar.delete": return "Delete an event by eventId";
            case "file.pick": return "Ask the human to select files";
            case "media.pick": return "Ask the human to select photos or videos";
            case "share.send": return "Open share sheet; human selects destination";
            case "workspace.info": return "Phone workspace and installed runtimes";
            case "workspace.list": return "List phone workspace files";
            case "workspace.read": return "Read a chunk of a workspace text file";
            case "workspace.write": return "Write or append workspace text";
            case "node.start": return "Start a workspace Node.js program";
            case "node.status": return "Read Node.js program state";
            case "node.stop": return "Stop the Node.js program";
            case "app.update": return "Install PickPico from verified APK URL and hash";
            case "app.update_check": return "Check for a PickPico update";
            case "app.update_latest": return "Install latest PickPico update";
            case "app.update_status": return "Read PickPico installation progress";
            case "process.run": return "Run a predefined diagnostic";
            case "process.exec": return "Run a phone shell command";
            case "process.output": return "Read process output using offsets";
            case "process.stop": return "Stop a process session";
            default: return id;
        }
    }

    /** Prefer explicit task intent; no substring matching for English words. */
    static Set<String> preferred(String query) {
        String q = query.toLowerCase(java.util.Locale.ROOT);
        boolean read = has(q, "read view list show inspect 查看 查詢 读取 讀取 看看");
        boolean remove = has(q, "delete remove dismiss clear 刪除 删除 清除 清掉 清理");
        boolean create = has(q, "create add new 新增 建立 添加");
        boolean edit = has(q, "update edit write set change replace lower decrease raise increase 調整 调整 調低 调低 調高 修改 設定 设置 寫入 写入 貼上");
        boolean open = has(q, "open launch 開啟 打開 打开 開");
        if (has(q, "battery 電量 电量 剩多少電 剩多少电")) return ids("phone.status");
        if (has(q, "volume audio 音量 音效")) return ids(edit ? "audio.set" : "audio.status");
        if (has(q, "calendar agenda schedule event events 行事曆 日曆 日历 行程")) {
            if (remove) return ids("calendar.delete");
            if (create) return ids("calendar.create");
            if (edit && !read) return ids("calendar.update");
            return ids("calendar.list", "calendar.get");
        }
        if (has(q, "notification notifications 通知")) {
            if (has(q, "reply respond 回覆 回复")) return ids("notification.reply");
            if (remove) return ids("notification.dismiss");
            if (has(q, "button buttons action actions 按鈕 按钮")) return ids("notification.actions", "notification.invoke_action");
            return ids("notification.list", "notification.get");
        }
        if (has(q, "clipboard 剪貼簿 剪贴板")) return ids(edit ? "clipboard.set" : "clipboard.get");
        if (has(q, "contact contacts 聯絡人 联系人 通訊錄 通讯录"))
            return ids(has(q, "details 詳細 详细") ? "contacts.get" : "contacts.search");
        if (open && has(q, "camera app apps application 相機 相机 應用 应用")) return ids("app.list", "app.launch");
        if (has(q, "screenshot 截圖 截图 螢幕截圖 屏幕截图")) return ids("screen.capture");
        if (has(q, "camera photo photograph 拍照 相機 相机")) return ids("camera.capture");
        if (has(q, "location gps 定位 位置 在哪裡 在哪里")) return ids("location.get");
        if (has(q, "speak aloud tts 朗讀 朗读 唸出 念出")) return ids("phone.speak");
        return java.util.Collections.emptySet();
    }
    private static Set<String> ids(String... ids) { return new LinkedHashSet<>(Arrays.asList(ids)); }
    private static boolean has(String q, String words) {
        for (String word : words.split(" ")) {
            if (word.matches("[a-z]+")) {
                if ((" " + q.replaceAll("[^a-z]+", " ") + " ").contains(" " + word + " ")) return true;
            } else if (q.contains(word)) return true;
        }
        return false;
    }
}
