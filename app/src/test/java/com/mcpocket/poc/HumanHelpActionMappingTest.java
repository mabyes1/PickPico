package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class HumanHelpActionMappingTest {
    @Test
    public void explicitCustomActionWins() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray().put("確認繼續").put("拒絕"))
                .put("customAction", "開啟登入頁");

        assertEquals("確認繼續", HumanHelpStore.approveAction(request));
        assertEquals("拒絕", HumanHelpStore.rejectAction(request));
        assertEquals("開啟登入頁", HumanHelpStore.customAction(request));
    }

    @Test
    public void thirdUnmappedActionBecomesCustom() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray()
                        .put("完成")
                        .put("做不到")
                        .put("需要更多資訊"));

        assertEquals("需要更多資訊", HumanHelpStore.customAction(request));
    }

    @Test
    public void twoStandardActionsLeaveCustomDisabled() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray().put("Approve").put("Reject"));

        assertEquals("", HumanHelpStore.customAction(request));
    }
}
