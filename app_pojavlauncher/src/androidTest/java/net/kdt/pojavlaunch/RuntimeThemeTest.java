package net.kdt.pojavlaunch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.QuickSettingSideDialog;
import net.kdt.pojavlaunch.utils.RuntimeTheme;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class RuntimeThemeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test
    public void systemThemeChangeKeepsOpenViewsAndPendingEdits() throws IOException {
        String originalMode = shell("cmd uimode night").trim().replaceFirst("^Night mode: ", "");
        boolean originalEnglish = LauncherPreferences.PREF_FORCE_ENGLISH;
        boolean originalGestures = LauncherPreferences.PREF_DISABLE_GESTURES;
        ProbeActivity activity = null;
        try {
            shell("cmd uimode night no");
            LauncherPreferences.PREF_FORCE_ENGLISH = true;
            activity = (ProbeActivity) instrumentation.startActivitySync(
                    new Intent(instrumentation.getTargetContext(), ProbeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ProbeActivity probe = activity;
            waitForMode(probe, Configuration.UI_MODE_NIGHT_NO);
            instrumentation.runOnMainSync(() -> probe.settings.appear(true));
            instrumentation.waitForIdleSync();

            TextView label = probe.findViewById(R.id.editResolution_textView);
            Switch gestures = probe.findViewById(R.id.checkboxGesture);
            int lightText = label.getCurrentTextColor();
            int lightPanel = panelColor(probe);
            instrumentation.runOnMainSync(() -> {
                assertTrue(ColorUtils.calculateContrast(lightText, lightPanel) >= 4.5);
                gestures.setChecked(!originalGestures);
                assertEquals(!originalGestures, LauncherPreferences.PREF_DISABLE_GESTURES);
                probe.input.setText("Test value");
                probe.dialog = RuntimeTheme.show(new AlertDialog.Builder(probe)
                        .setTitle("Test title")
                        .setView(probe.input)
                        .setPositiveButton(android.R.string.ok, null));
            });

            shell("cmd uimode night yes");
            waitForMode(probe, Configuration.UI_MODE_NIGHT_YES);
            instrumentation.runOnMainSync(() -> {
                assertSame(label, probe.findViewById(R.id.editResolution_textView));
                assertNotEquals(lightText, label.getCurrentTextColor());
                assertNotEquals(lightPanel, panelColor(probe));
                assertTrue(ColorUtils.calculateContrast(label.getCurrentTextColor(), panelColor(probe)) >= 4.5);
                assertEquals(!originalGestures, gestures.isChecked());
                assertTrue(probe.dialog.isShowing());
                assertEquals("Test value", probe.input.getText().toString());
                assertEquals(label.getCurrentTextColor(), probe.input.getCurrentTextColor());
                assertEquals(Locale.ENGLISH, probe.getResources().getConfiguration().getLocales().get(0));
            });

            shell("cmd uimode night no");
            waitForMode(probe, Configuration.UI_MODE_NIGHT_NO);
            instrumentation.runOnMainSync(() -> {
                assertEquals(lightText, label.getCurrentTextColor());
                assertEquals(lightPanel, panelColor(probe));
                probe.dialog.dismiss();
                probe.settings.cancel();
                assertEquals(originalGestures, LauncherPreferences.PREF_DISABLE_GESTURES);
            });
        } finally {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
            }
            LauncherPreferences.PREF_FORCE_ENGLISH = originalEnglish;
            LauncherPreferences.PREF_DISABLE_GESTURES = originalGestures;
            shell("cmd uimode night " + originalMode);
        }
    }

    private void waitForMode(ProbeActivity activity, int mode) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (activity.appliedMode != mode && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            SystemClock.sleep(50);
        }
        assertEquals(mode, activity.appliedMode);
    }

    private static int panelColor(ProbeActivity activity) {
        return ((GradientDrawable) activity.panelHost.getChildAt(0).getBackground()).getColor().getDefaultColor();
    }

    private String shell(String command) throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(command))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static final class ProbeActivity extends BaseActivity {
        private volatile int appliedMode;
        private FrameLayout panelHost;
        private QuickSettingSideDialog settings;
        private AlertDialog dialog;
        private EditText input;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            panelHost = new FrameLayout(this);
            setContentView(panelHost);
            input = new EditText(this);
            settings = new QuickSettingSideDialog(this, panelHost) {
                @Override
                public void onResolutionChanged() {}

                @Override
                public void onGyroStateChanged() {}
            };
            appliedMode = RuntimeTheme.nightMode(getResources().getConfiguration());
        }

        @Override
        protected void onRuntimeThemeChanged(RuntimeTheme theme) {
            settings.applyRuntimeTheme(theme);
            appliedMode = RuntimeTheme.nightMode(getResources().getConfiguration());
        }
    }
}
