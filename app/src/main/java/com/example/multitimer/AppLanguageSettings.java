package com.example.multitimer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

final class AppLanguageSettings {
    private static final String PREFERENCES_NAME = "multitimer_language";
    private static final String KEY_SELECTED = "language_selected";
    private static final String KEY_TAG = "language_tag";

    private static final String[] LANGUAGE_TAGS = {
            "de", "en", "fr", "es", "pt-PT"
    };

    private AppLanguageSettings() {
    }

    static boolean hasSelectedLanguage(Context context) {
        return preferences(context).getBoolean(KEY_SELECTED, false);
    }

    static String[] getLanguageNames() {
        String[] names = new String[LANGUAGE_TAGS.length];
        for (int index = 0; index < LANGUAGE_TAGS.length; index++) {
            Locale locale = Locale.forLanguageTag(LANGUAGE_TAGS[index]);
            names[index] = locale.getDisplayLanguage(locale);
        }
        return names;
    }

    static int getSelectedIndex(Context context) {
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        if (!appLocales.isEmpty()) {
            int appLocaleIndex = findIndex(appLocales.get(0).toLanguageTag());
            if (appLocaleIndex >= 0) {
                return appLocaleIndex;
            }
        }

        String savedTag = preferences(context).getString(KEY_TAG, null);
        if (savedTag != null) {
            int savedIndex = findIndex(savedTag);
            if (savedIndex >= 0) {
                return savedIndex;
            }
        }

        int systemIndex = findIndex(Locale.getDefault().toLanguageTag());
        if (systemIndex >= 0) {
            return systemIndex;
        }
        return findIndex("de");
    }

    static void selectLanguage(Context context, int index) {
        if (index < 0 || index >= LANGUAGE_TAGS.length) {
            return;
        }
        String tag = LANGUAGE_TAGS[index];
        preferences(context).edit()
                .putBoolean(KEY_SELECTED, true)
                .putString(KEY_TAG, tag)
                .apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    static String getLanguageTag(int index) {
        return index >= 0 && index < LANGUAGE_TAGS.length ? LANGUAGE_TAGS[index] : LANGUAGE_TAGS[0];
    }

    private static int findIndex(String languageTag) {
        String language = Locale.forLanguageTag(languageTag).getLanguage();
        for (int index = 0; index < LANGUAGE_TAGS.length; index++) {
            if (Locale.forLanguageTag(LANGUAGE_TAGS[index]).getLanguage().equals(language)) {
                return index;
            }
        }
        return -1;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}