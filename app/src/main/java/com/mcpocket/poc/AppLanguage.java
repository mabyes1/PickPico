package com.mcpocket.poc;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** App-only language selection. Android 13+ also exposes it in system App languages. */
final class AppLanguage {
    private static final String PREFS = "app_language";
    private static final String KEY = "tag";

    static String selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? "" : locales.get(0).toLanguageTag();
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    static Context wrap(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;
        String tag = selected(context);
        if (tag.isEmpty()) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags(tag));
        return context.createConfigurationContext(config);
    }

    static void select(Activity activity, String tag) {
        if (!tag.isEmpty() && !tag.equals("en") && !tag.equals("zh-Hant")) {
            throw new IllegalArgumentException("Unsupported app language");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class)
                    .setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply();
            activity.recreate();
        }
    }

    static int choice(Context context) {
        String tag = selected(context);
        return tag.isEmpty() ? 0 : Locale.forLanguageTag(tag).getLanguage().equals("zh") ? 1 : 2;
    }
}
