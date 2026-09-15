package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class PickPicoThemeTest {
    private final Map<String, Object> stored = new HashMap<>();
    private Context context;
    private SharedPreferences.Editor editor;
    private MockedStatic<Color> colors;

    @Before public void setUp() {
        colors = mockStatic(Color.class);
        colors.when(() -> Color.rgb(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                0xff000000 | ((int) call.getArgument(0) << 16)
                        | ((int) call.getArgument(1) << 8) | (int) call.getArgument(2));
        colors.when(() -> Color.parseColor(anyString())).thenAnswer(call ->
                (int) (0xff000000L | Long.parseLong(((String) call.getArgument(0)).substring(1), 16)));
        colors.when(() -> Color.red(anyInt())).thenAnswer(call -> ((int) call.getArgument(0) >> 16) & 255);
        colors.when(() -> Color.green(anyInt())).thenAnswer(call -> ((int) call.getArgument(0) >> 8) & 255);
        colors.when(() -> Color.blue(anyInt())).thenAnswer(call -> (int) call.getArgument(0) & 255);
        context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(eq("pickpico_appearance"), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), anyString())).thenAnswer(call -> stored.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(prefs.getInt(anyString(), anyInt())).thenAnswer(call -> stored.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> stored.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return editor; });
        when(editor.putInt(anyString(), anyInt())).thenAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return editor; });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return editor; });
    }

    @After public void tearDown() { colors.close(); }

    @Test public void existingUsersKeepOriginalOrbAndTheirTheme() {
        stored.put("color_a", "#112233");
        stored.put("color_b", "#445566");
        PickPicoTheme.State theme = PickPicoTheme.load(context);
        assertEquals(PickPicoTheme.ORB_ORIGINAL, theme.orbStyle);
        assertEquals(0xff112233, theme.colorA);
        assertEquals(0xff445566, theme.colorB);
        verify(editor, never()).clear();
    }

    @Test public void flameSurvivesReloadAndUnrelatedAppearanceEdits() {
        PickPicoTheme.save(context, true, "#aabbcc", "#112233", 8, 30, 80, PickPicoTheme.ORB_FLAME);
        assertEquals(PickPicoTheme.ORB_FLAME, PickPicoTheme.load(context).orbStyle);
        PickPicoTheme.save(context, false, "#ddeeff", "#224466", 12, 35, 90);
        PickPicoTheme.State changed = PickPicoTheme.load(context);
        assertEquals(PickPicoTheme.ORB_FLAME, changed.orbStyle);
        assertFalse(changed.gradient);
        assertEquals(0xffddeeff, changed.colorA);
        PickPicoTheme.save(context, true, "#aabbcc", "#112233", 9);
        assertEquals(PickPicoTheme.ORB_FLAME, PickPicoTheme.load(context).orbStyle);
    }

    @Test public void switchingBackKeepsOtherPreferences() {
        stored.put("unrelated_setting", "keep me");
        PickPicoTheme.save(context, true, "#aabbcc", "#112233", 8, 30, 80, PickPicoTheme.ORB_FLAME);
        PickPicoTheme.save(context, true, "#aabbcc", "#112233", 8, 30, 80, PickPicoTheme.ORB_ORIGINAL);
        PickPicoTheme.State restored = PickPicoTheme.load(context);
        assertEquals(PickPicoTheme.ORB_ORIGINAL, restored.orbStyle);
        assertEquals(0xffaabbcc, restored.colorA);
        assertEquals(80, restored.backgroundIntensity);
        assertEquals("keep me", stored.get("unrelated_setting"));
        verify(editor, never()).clear();
    }

    @Test public void unknownSavedStyleFallsBackToOriginal() {
        stored.put("orb_style", "future-or-removed-style");
        assertEquals(PickPicoTheme.ORB_ORIGINAL, PickPicoTheme.load(context).orbStyle);
    }
}
