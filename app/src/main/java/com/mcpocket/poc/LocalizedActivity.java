package com.mcpocket.poc;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

/** Backport app language contexts for Android 8–12 without changing system language. */
public abstract class LocalizedActivity extends Activity {
    private String languageAtAttach;

    @Override protected void attachBaseContext(Context base) {
        languageAtAttach = AppLanguage.selected(base);
        super.attachBaseContext(AppLanguage.wrap(base));
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < 33 && !AppLanguage.selected(this).equals(languageAtAttach)) {
            recreate();
        }
    }
}
