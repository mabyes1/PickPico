package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class HumanHelpActionMappingTest {
    @Test
    public void localizedApproveAndRejectAreMapped() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray().put("確認繼續").put("拒絕"));

        assertEquals("確認繼續", HumanHelpStore.approveAction(request));
        assertEquals("拒絕", HumanHelpStore.rejectAction(request));
    }

    @Test
    public void extraAgentActionsDoNotChangePrimaryControls() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray()
                        .put("完成")
                        .put("做不到")
                        .put("需要更多資訊"));

        assertEquals("完成", HumanHelpStore.approveAction(request));
        assertEquals("做不到", HumanHelpStore.rejectAction(request));
    }

    @Test
    public void standardEnglishActionsAreMapped() throws Exception {
        JSONObject request = new JSONObject()
                .put("actions", new JSONArray().put("Approve").put("Reject"));

        assertEquals("Approve", HumanHelpStore.approveAction(request));
        assertEquals("Reject", HumanHelpStore.rejectAction(request));
    }
}
