package net.kdt.pojavlaunch.utils;


import static net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_ENGLISH;

import android.content.*;
import android.content.res.*;
import android.os.LocaleList;

import androidx.preference.*;
import java.util.*;

public class LocaleUtils extends ContextWrapper {

    public LocaleUtils(Context base) {
        super(base);
    }

    public static ContextWrapper setLocale(Context context) {
        ensureLocalePreference(context);

        if(PREF_FORCE_ENGLISH){
            Resources resources = context.getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());
            LocaleList english = new LocaleList(Locale.ENGLISH);
            configuration.setLocales(english);
            LocaleList.setDefault(english);
            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        }
        return new LocaleUtils(context);
    }

    public static Context forActivity(Context context) {
        ensureLocalePreference(context);
        if (!PREF_FORCE_ENGLISH) {
            return context;
        }

        // 언어만 덮어써서 시스템의 테마와 화면 구성이 계속 전달되도록 한다.
        Configuration locale = new Configuration();
        locale.fontScale = 0;
        locale.setLocales(new LocaleList(Locale.ENGLISH));
        return context.createConfigurationContext(locale);
    }

    private static void ensureLocalePreference(Context context) {
        if (DEFAULT_PREF == null) {
            DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context);
            // Too early to initialize all prefs here, as this is called by PojavApplication
            // before storage checks are done and before the storage paths are initialized.
            // So only initialize PREF_FORCE_ENGLISH for the check below.
            PREF_FORCE_ENGLISH = DEFAULT_PREF.getBoolean("force_english", false);
        }
    }
}
