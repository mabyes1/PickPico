package com.mcpocket.poc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public final class VisualUiSafetyTest {
    @Test public void screenshotIsSingleUseAndNewCaptureSupersedesIt() {
        VisualObservationStore store = new VisualObservationStore();
        String first = store.record("browser:1080:2340:portrait", 1080, 2340, 1000);
        store.consume(first, "browser:1080:2340:portrait", 1001, 1079, 2339);
        assertThrows(CommandRuntime.CommandInputException.class,
                () -> store.consume(first, "browser:1080:2340:portrait", 1002, 10, 10));
        String old = store.record("same", 1080, 2340, 1000);
        store.record("same", 1080, 2340, 1001);
        assertThrows(CommandRuntime.CommandInputException.class, () -> store.consume(old, "same", 1002));
    }

    @Test public void changedContextExpiredFrameAndInvalidCoordinatesNeverAct() {
        for (String changed : new String[]{"different-app", "rotation-changed", "editor-changed"}) {
            VisualObservationStore store = new VisualObservationStore();
            String id = store.record("original", 1080, 2340, 1000);
            assertThrows(CommandRuntime.CommandInputException.class, () -> store.consume(id, changed, 1001, 20, 30));
        }
        for (long now : new long[]{999, 61001}) {
            VisualObservationStore store = new VisualObservationStore();
            String id = store.record("same", 1080, 2340, 1000);
            assertThrows(CommandRuntime.CommandInputException.class, () -> store.consume(id, "same", now));
        }
        for (double[] xy : new double[][]{{-1, 0}, {1080, 0}, {0, 2340}, {Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}}) {
            VisualObservationStore store = new VisualObservationStore();
            String id = store.record("same", 1080, 2340, 1000);
            assertThrows(CommandRuntime.CommandInputException.class, () -> store.consume(id, "same", 1001, xy));
        }
    }

    @Test public void semanticActionAndEditorChangeCanInvalidateScreenshot() {
        VisualObservationStore store = new VisualObservationStore();
        String id = store.record("same", 1080, 2340, 1000);
        store.invalidate();
        assertThrows(CommandRuntime.CommandInputException.class, () -> store.consume(id, "same", 1001));
    }

    @Test public void mixedTargetsAndMissingVisualObservationFailBeforeApproval() throws Exception {
        McpToolActions actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        CommandRuntime runtime = new CommandRuntime(actions);
        String[][] invalid = {
            {"ui.action", "{action:'click',point:{x:1,y:1},selector:{text:'Save'},observationId:'screen-test'}"},
            {"ui.action", "{action:'click',point:{x:1,y:1},observationId:'ui-test'}"},
            {"ui.action", "{action:'focus',point:{x:1,y:1},observationId:'screen-test'}"},
            {"ui.action", "{action:'home',point:{x:1,y:1},observationId:'screen-test'}"},
            {"ui.action", "{action:'click',point:{x:-1,y:1},observationId:'screen-test'}"},
            {"ui.scroll", "{swipe:{start:{x:1,y:1},end:{x:2,y:2}},direction:'down',observationId:'screen-test'}"},
            {"ui.scroll", "{swipe:{start:{x:1,y:1},end:{x:1,y:1}},observationId:'screen-test'}"},
            {"ui.scroll", "{swipe:{start:{x:1,y:1},end:{x:2,y:2},durationMs:3001},observationId:'screen-test'}"},
            {"ui.type", "{focused:true,text:'abc',observationId:'screen-test'}"},
            {"ui.type", "{focused:true,selector:{text:'Name'},textMode:'replace',text:'abc',observationId:'screen-test'}"},
            {"ui.type", "{focused:true,append:true,textMode:'insert',text:'abc',observationId:'screen-test'}"},
            {"ui.type", "{focused:true,textMode:'insert',text:'abc'}"},
            {"ui.type", "{text:'abc'}"}
        };
        for (String[] test : invalid) assertThrows(test[1], CommandRuntime.CommandInputException.class,
                () -> runtime.execute(test[0], new JSONObject(test[1]), 1));
        verify(actions, never()).requestApproval(anyString(), anyString(), anyString(), any(), anyLong());
        verify(actions, never()).uiAction(any(), anyLong());
        verify(actions, never()).uiScroll(any(), anyLong());
        verify(actions, never()).uiType(any(), anyLong());
    }

    @Test public void oldSelectorsAndNewVisualTargetsBothReachTheirHandlers() throws Exception {
        McpToolActions actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        CommandRuntime runtime = new CommandRuntime(actions);
        JSONObject point = new JSONObject("{action:'long_click',point:{x:45.5,y:120},observationId:'screen-test'}");
        JSONObject selector = new JSONObject("{action:'click',selector:{text:'Save'}}");
        JSONObject input = new JSONObject("{focused:true,textMode:'replace',text:'測試🦊\\n第二行',observationId:'screen-test'}");
        JSONObject swipe = new JSONObject("{swipe:{start:{x:100,y:900},end:{x:100,y:300}},observationId:'screen-test'}");
        runtime.execute("ui.action", point, 1);
        runtime.execute("ui.action", selector, 2);
        runtime.execute("ui.type", input, 3);
        runtime.execute("ui.scroll", swipe, 4);
        verify(actions).uiAction(point, 1);
        verify(actions).uiAction(selector, 2);
        verify(actions).uiType(input, 3);
        verify(actions).uiScroll(swipe, 4);
    }

    @Test public void replaceRequiresSelectionOfWholeField() {
        android.view.inputmethod.SurroundingText selected = mock(android.view.inputmethod.SurroundingText.class);
        when(selected.getText()).thenReturn("測試🦊");
        when(selected.getOffset()).thenReturn(0);
        when(selected.getSelectionStart()).thenReturn(0);
        when(selected.getSelectionEnd()).thenReturn(4);
        assertTrue(VisualUiController.wholeFieldSelected(selected));
        when(selected.getSelectionEnd()).thenReturn(3);
        assertFalse(VisualUiController.wholeFieldSelected(selected));
        when(selected.getSelectionEnd()).thenReturn(4);
        when(selected.getOffset()).thenReturn(5);
        assertFalse(VisualUiController.wholeFieldSelected(selected));
        assertFalse(VisualUiController.wholeFieldSelected(null));
    }
}
