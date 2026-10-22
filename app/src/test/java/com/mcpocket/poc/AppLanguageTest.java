package com.mcpocket.poc;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import android.widget.TextView;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
public class AppLanguageTest {
    private Context locale(String tag) {
        Context app = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(tag));
        return app.createConfigurationContext(configuration);
    }

    @Test public void languagesCoexistAndIconsUseStableIdentity() {
        Context en = locale("en"), zh = locale("zh-Hant-TW");
        assertEquals("SETTINGS", en.getString(R.string.ui_settings));
        assertEquals("設定", zh.getString(R.string.ui_settings));
        assertEquals("settings", PulseNavigation.kind(en, en.getString(R.string.ui_settings)));
        assertEquals("settings", PulseNavigation.kind(zh, zh.getString(R.string.ui_settings)));
        assertEquals("Node\nstopped", en.getString(R.string.ui_node_nstopped));
        assertEquals("服務\n已停止", zh.getString(R.string.ui_node_nstopped));
        assertEquals("PAIRING · ", en.getString(R.string.ui_pairing_1f2ce3));
        assertEquals("未知狀態", UiStatus.label(zh, "未知狀態"));
    }

    @Test public void languageChangesCopyWithoutChangingTaskStatesOrUserText() throws Exception {
        HomePulse.reset();
        Context zh = locale("zh-Hant");
        HomePulse.Snapshot idle = HomePulse.snapshot(zh, true, false, "disabled", null, System.currentTimeMillis());
        assertEquals("idle", idle.mode);
        assertEquals("目前沒有進行中的任務", idle.title);
        assertEquals("No active agent tasks", HomePulse.snapshot(true, false, "disabled", null, System.currentTimeMillis()).title);
        JSONObject pending = new JSONObject().put("title", "Keep my original task title")
                .put("requestType", "approval").put("requestId", "sample");
        HomePulse.Snapshot waiting = HomePulse.snapshot(zh, true, false, "disabled", pending, System.currentTimeMillis());
        assertEquals("waiting", waiting.mode);
        assertEquals("Keep my original task title", waiting.actionDetail);
        assertEquals("需要核准", waiting.actionTitle);
        assertEquals("正在查看目前畫面", HomePulse.commandTitle(zh, "ui.inspect"));
    }

    @Test public void preAndroid13SelectionPersistsAndFollowSystemRestoresEnglish() {
        try (var controller = Robolectric.buildActivity(Activity.class).create()) {
            Activity activity = controller.get();
            try {
                AppLanguage.select(activity, "zh-Hant");
                assertEquals("zh-Hant", AppLanguage.selected(activity));
                assertEquals("設定", AppLanguage.wrap(activity).getString(R.string.ui_settings));
                AppLanguage.select(activity, "en");
                assertEquals("SETTINGS", AppLanguage.wrap(activity).getString(R.string.ui_settings));
                AppLanguage.select(activity, "");
                assertEquals(0, AppLanguage.choice(activity));
                assertEquals("SETTINGS", AppLanguage.wrap(activity).getString(R.string.ui_settings));
            } finally {
                activity.getSharedPreferences("app_language", 0).edit().clear().commit();
            }
        }
    }

    @Test @Config(sdk = 33) public void android13UsesSystemAppLocaleStorage() {
        try (var controller = Robolectric.buildActivity(Activity.class).create()) {
            Activity activity = controller.get();
            AppLanguage.select(activity, "zh-Hant");
            assertEquals("zh-Hant", activity.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());
            AppLanguage.select(activity, "en");
            assertEquals(2, AppLanguage.choice(activity));
            AppLanguage.select(activity, "");
            assertEquals(0, AppLanguage.choice(activity));
        }
    }

    @Test public void chineseDashboardSeparatesSavedPairingFromLiveTransport() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("app_language", 0).edit().putString("tag", "zh-Hant").commit();
        PicoAdbManager previous = ReflectionHelpers.getStaticField(PicoAdbManager.class, "instance");
        PicoAdbManager manager = mock(PicoAdbManager.class);
        when(manager.status()).thenReturn(new JSONObject().put("state", "paired").put("paired", true)
                .put("connection", new JSONObject().put("state", "disconnected")));
        ReflectionHelpers.setStaticField(PicoAdbManager.class, "instance", manager);
        try (var controller = Robolectric.buildActivity(DashboardActivity.class).create()) {
            DashboardActivity activity = controller.get();
            ReflectionHelpers.callInstanceMethod(activity, "showPage", ReflectionHelpers.ClassParameter.from(int.class, 6));
            TextView pairing = ReflectionHelpers.getField(activity, "picoAdbPairingState");
            TextView connection = ReflectionHelpers.getField(activity, "picoAdbState");
            assertEquals("配對狀態・已儲存", pairing.getText().toString());
            assertEquals("目前連線・尚未連線", connection.getText().toString());
        } finally {
            ReflectionHelpers.setStaticField(PicoAdbManager.class, "instance", previous);
            app.getSharedPreferences("app_language", 0).edit().clear().commit();
        }
    }
}
