package com.mcpocket.poc;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Looper;
import static org.robolectric.Shadows.shadowOf;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class AppearanceColorDialogTest {
    private DashboardActivity appearance() throws Exception {
        // Build only the appearance UI: no node service, runtime or permissions.
        DashboardActivity activity = Robolectric.buildActivity(DashboardActivity.class).get();
        PickPicoTheme.State initial = PickPicoTheme.save(activity, true, "#112233", "#445566", 8, 30, 80, PickPicoTheme.ORB_FLAME);
        field("theme").set(activity, initial);
        activity.setContentView((View) invoke(activity, "buildShell"));
        invoke(activity, "buildAppearancePage");
        return activity;
    }

    @Test public void bothColorRowsOpenPaletteAndApplyToLiveThemeAndSavedSettings() throws Exception {
        DashboardActivity activity = appearance();
        for (String control : new String[]{"appearanceColorA", "appearanceColorB"}) {
            TextView target = (TextView) field(control).get(activity);
            assertTrue(target.performClick());
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            EditText hex = find(dialog.getWindow().getDecorView(), EditText.class);
            hex.setText(control.endsWith("A") ? "#29C7B4" : "#E57326");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
        }
        PickPicoTheme.State saved = PickPicoTheme.load(activity);
        PickPicoTheme.State live = (PickPicoTheme.State) field("theme").get(activity);
        assertEquals(Color.rgb(41, 199, 180), saved.colorA);
        assertEquals(Color.rgb(229, 115, 38), saved.colorB);
        assertEquals(saved.colorA, live.colorA);
        assertEquals(saved.colorB, live.colorB);
        assertEquals(PickPicoTheme.ORB_FLAME, saved.orbStyle);
        invoke(activity, "clearPageReferences");
        invoke(activity, "buildAppearancePage");
        assertEquals("#29c7b4", ((TextView) field("appearanceColorA").get(activity)).getText().toString());
    }

    @Test public void cancelDoesNotChangeTheThemeAndInvalidHexKeepsDialogOpen() throws Exception {
        DashboardActivity activity = appearance();
        ((TextView) field("appearanceColorA").get(activity)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText hex = find(dialog.getWindow().getDecorView(), EditText.class);
        hex.setText("#12");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(dialog.isShowing());
        hex.setText("#FFFFFF");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Color.rgb(17, 34, 51), PickPicoTheme.load(activity).colorA);
    }

    private static Field field(String name) throws Exception {
        Field field = DashboardActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object invoke(DashboardActivity activity, String name) throws Exception {
        Method method = DashboardActivity.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(activity);
    }
    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T match = find(group.getChildAt(i), type); if (match != null) return match;
            }
        }
        return null;
    }
}
