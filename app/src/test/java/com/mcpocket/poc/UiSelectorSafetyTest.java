package com.mcpocket.poc;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.junit.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public final class UiSelectorSafetyTest {
    private McpAccessibilityService service;
    private AccessibilityNodeInfo root, first, second;
    @Before public void setup() {
        service=mock(McpAccessibilityService.class); root=mock(AccessibilityNodeInfo.class);
        first=mock(AccessibilityNodeInfo.class); second=mock(AccessibilityNodeInfo.class);
        when(service.getRootInActiveWindow()).thenReturn(root);
        when(root.getChildCount()).thenReturn(2); when(root.getChild(0)).thenReturn(first); when(root.getChild(1)).thenReturn(second);
        when(first.getText()).thenReturn("Send"); when(second.getText()).thenReturn("Send");
    }
    private Object find(JSONObject selector) throws Exception {
        Method method=McpAccessibilityService.class.getDeclaredMethod("findNode",McpAccessibilityService.class,JSONObject.class);
        method.setAccessible(true);
        try { return method.invoke(null,service,selector); }
        catch(InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    @Test public void invalidPathNeverFallsBackToTextOrRoot() throws Exception {
        for(String path:new String[]{"0/99","0//1","garbage","/","0/"}) assertNull(path,find(new JSONObject().put("path",path).put("text","Send")));
        assertSame(first,find(new JSONObject().put("path","0/0").put("text","Send")));
    }
    @Test public void repeatedLabelsNeedAnExplicitUniqueTarget() throws Exception {
        assertThrows(CommandRuntime.CommandInputException.class,()->find(new JSONObject().put("text","Send")));
        assertSame(second,find(new JSONObject().put("text","Send").put("instance",1)));
        assertThrows(CommandRuntime.CommandInputException.class,()->find(new JSONObject().put("instance",0)));
        verify(first,never()).performAction(org.mockito.ArgumentMatchers.anyInt());
        verify(second,never()).performAction(org.mockito.ArgumentMatchers.anyInt());
    }
}
