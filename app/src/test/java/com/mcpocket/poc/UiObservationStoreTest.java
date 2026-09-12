package com.mcpocket.poc;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public final class UiObservationStoreTest {
    @Test public void observationIsSingleUseAndRejectsChangedExpiredOrUnknownScreens() {
        UiObservationStore store=new UiObservationStore();
        String id=store.record("screen",100); store.consume(id,"screen",101);
        assertThrows(CommandRuntime.CommandInputException.class,()->store.consume(id,"screen",102));
        String stale=store.record("screen",100);
        assertThrows(CommandRuntime.CommandInputException.class,()->store.consume(stale,"changed",101));
        String expired=store.record("screen",100);
        assertThrows(CommandRuntime.CommandInputException.class,()->store.consume(expired,"screen",120101));
    }
    @Test public void compactNodesPreserveTargetsAndMeaningfulFalseState() throws Exception {
        JSONObject node=new JSONObject().put("path","0/1").put("text","Send").put("clickable",true).put("enabled",false).put("visible",true).put("className","android.widget.Button");
        JSONObject compact=UiObservationStore.compact(node);
        assertEquals("0/1",compact.getString("path")); assertFalse(compact.getBoolean("enabled"));
        assertEquals("click",compact.getJSONArray("actions").getString(0)); assertFalse(compact.has("className"));
        assertTrue(compact.toString().length()<node.toString().length());
    }
}
