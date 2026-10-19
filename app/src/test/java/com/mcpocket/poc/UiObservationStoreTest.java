package com.mcpocket.poc;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
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
    @Test public void passiveOrbActivityDoesNotInvalidateTheObservedApp() throws Exception {
        JSONObject before = screen("Launcher", "ready", false);
        JSONObject after = screen("Launcher", "working", false);
        assertEquals(UiObservationStore.guardSnapshot(before).toString(), UiObservationStore.guardSnapshot(after).toString());
        assertEquals(2, before.getJSONArray("nodes").length());
    }
    @Test public void actualAppChangesStillInvalidateObservation() throws Exception {
        assertNotEquals(UiObservationStore.guardSnapshot(screen("Launcher", "ready", false)).toString(),
                UiObservationStore.guardSnapshot(screen("Permission dialog", "working", false)).toString());
    }
    @Test public void activePicoWindowRemainsGuarded() throws Exception {
        assertNotEquals(UiObservationStore.guardSnapshot(screen("Launcher", "Settings", true)).toString(),
                UiObservationStore.guardSnapshot(screen("Launcher", "Delete pairing", true)).toString());
    }
    private JSONObject screen(String appText, String picoText, boolean picoActive) throws Exception {
        return new JSONObject().put("packageName", "launcher").put("windows", new JSONArray()
                .put(new JSONObject().put("id", 1).put("type", 1).put("packageName", "launcher").put("active", true))
                .put(new JSONObject().put("id", 2).put("type", 3).put("packageName", "com.mcpocket.poc").put("active", picoActive)))
                .put("nodes", new JSONArray().put(new JSONObject().put("path", "w1/0").put("text", appText))
                        .put(new JSONObject().put("path", "w2/0").put("text", picoText)));
    }
}
