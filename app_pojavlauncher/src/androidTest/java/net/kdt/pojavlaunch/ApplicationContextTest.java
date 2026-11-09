package net.kdt.pojavlaunch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ApplicationContextTest {
    private final Context context = InstrumentationRegistry
            .getInstrumentation()
            .getTargetContext();

    @Test
    public void manifestReceiverUsesTheRealApplicationBaseContext() throws Exception {
        assertTrue(context.getApplicationContext() instanceof RuntimeTestApplication);
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(Activity.RESULT_CANCELED);
        AtomicReference<String> packageName = new AtomicReference<>();
        BroadcastReceiver completion = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                result.set(getResultCode());
                packageName.set(getResultData());
                received.countDown();
            }
        };

        context.sendOrderedBroadcast(
                new Intent(context, ApplicationContextProbeReceiver.class),
                null,
                completion,
                null,
                Activity.RESULT_CANCELED,
                null,
                null
        );

        assertTrue("manifest receiver가 5초 안에 실행되지 않았습니다", received.await(5, TimeUnit.SECONDS));
        assertEquals(Activity.RESULT_OK, result.get());
        assertEquals(context.getPackageName(), packageName.get());
    }

    @Test
    public void attachingApplicationPreservesBaseAndTheRequestedLanguage() {
        SharedPreferences previousPreferences = LauncherPreferences.DEFAULT_PREF;
        boolean previousForceEnglish = LauncherPreferences.PREF_FORCE_ENGLISH;
        Locale previousLocale = Locale.getDefault();
        LocaleList previousLocales = LocaleList.getDefault();
        try {
            // 파일의 사용자 설정을 바꾸지 않고 attach 시점의 이미 읽은 설정만 바꾼다.
            LauncherPreferences.DEFAULT_PREF = context.getSharedPreferences("test-application-context", Context.MODE_PRIVATE);
            verifyAttachment(false, Locale.KOREAN);
            verifyAttachment(true, Locale.ENGLISH);
        } finally {
            LauncherPreferences.DEFAULT_PREF = previousPreferences;
            LauncherPreferences.PREF_FORCE_ENGLISH = previousForceEnglish;
            LocaleList.setDefault(previousLocales);
            Locale.setDefault(previousLocale);
        }
    }

    private void verifyAttachment(boolean forceEnglish, Locale expectedLocale) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.KOREAN));
        Context base = context.createConfigurationContext(configuration);
        LauncherPreferences.PREF_FORCE_ENGLISH = forceEnglish;
        PojavApplication application = new PojavApplication();

        application.attachBaseContext(base);

        assertSame(base, application.getBaseContext());
        assertEquals(expectedLocale, application.getResources().getConfiguration().getLocales().get(0));
    }
}
