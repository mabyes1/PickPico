package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OperationGuidesTest {
    @Test public void taskIntentWorksWithoutWhitespaceOrCapabilityMatches() throws Exception {
        assertTrue(has(OperationGuides.search("幫我打開外送APP操作一下", new JSONArray()), "app.operate"));
        assertTrue(has(OperationGuides.search("搜索商品并填写地址", new JSONArray()), "ui.find_and_fill"));
        assertTrue(has(OperationGuides.search("permission blocked", new JSONArray()), "ui.recover"));
        assertTrue(has(OperationGuides.search("open an app", new JSONArray()), "app.operate"));
    }

    @Test public void exactToolLookupOffersGuidesButUnrelatedWorkDoesNot() throws Exception {
        JSONArray matches = new JSONArray().put(new JSONObject().put("id", "ui.type"));
        assertEquals(0, OperationGuides.search("ui.type", matches).length());
        assertEquals(0, OperationGuides.search("append file", new JSONArray()
                .put(new JSONObject().put("id", "workspace.write"))).length());
        assertEquals(0, OperationGuides.search("拍照", new JSONArray()
                .put(new JSONObject().put("id", "camera.capture"))).length());
        assertEquals(0, OperationGuides.search("human.help", new JSONArray()
                .put(new JSONObject().put("id", "human.help"))).length());
    }

    @Test public void summariesCanBeFollowedAndDoNotLoadFullGuides() throws Exception {
        JSONArray summaries = OperationGuides.search("", new JSONArray());
        assertEquals(3, summaries.length());
        for (int i = 0; i < summaries.length(); i++) {
            JSONObject summary = summaries.getJSONObject(i);
            assertFalse(summary.has("rules"));
            assertFalse(summary.has("decisions"));
            JSONObject call = summary.getJSONObject("readWith").getJSONObject("arguments");
            assertEquals("guide.get", call.getString("commandId"));
            JSONObject guide = OperationGuides.get(call.getJSONObject("arguments").getString("guideId"));
            assertEquals(summary.getString("id"), guide.getString("id"));
            assertTrue(guide.getJSONArray("capabilityIds").length() > 0);
            JSONArray decisions = guide.getJSONArray("decisions");
            for (int j = 0; j < decisions.length(); j++) {
                JSONObject decision = decisions.getJSONObject(j);
                assertFalse(decision.getString("when").isEmpty());
                assertFalse(decision.getString("action").isEmpty());
                assertFalse(decision.getString("verify").isEmpty());
            }
        }
    }

    @Test public void callersCannotMutateFutureGuideResponses() throws Exception {
        OperationGuides.get("app.operate").getJSONArray("rules").put("injected");
        assertFalse(OperationGuides.get("app.operate").toString().contains("injected"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownGuideFailsClearly() throws Exception {
        OperationGuides.get("made.up");
    }

    private boolean has(JSONArray guides, String id) throws Exception {
        for (int i = 0; i < guides.length(); i++) {
            if (id.equals(guides.getJSONObject(i).getString("id"))) return true;
        }
        return false;
    }
}
