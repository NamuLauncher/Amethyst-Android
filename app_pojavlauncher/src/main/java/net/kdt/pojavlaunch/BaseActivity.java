package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_IGNORE_NOTCH;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.utils.LocaleUtils;
import net.kdt.pojavlaunch.utils.RuntimeTheme;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public abstract class BaseActivity extends AppCompatActivity {
    private final Set<Dialog> runtimeDialogs =
            Collections.newSetFromMap(new WeakHashMap<>());
    private int nightMode;

    public final void trackRuntimeDialog(Dialog dialog) {
        runtimeDialogs.add(dialog);
        new RuntimeTheme(this).applyDialog(dialog);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        int newNightMode = RuntimeTheme.nightMode(getResources().getConfiguration());
        if (nightMode == newNightMode) {
            return;
        }

        nightMode = newNightMode;
        RuntimeTheme theme = new RuntimeTheme(this);
        theme.applySystemBars(getWindow());
        runtimeDialogs.removeIf(dialog -> !dialog.isShowing());
        for (Dialog dialog : runtimeDialogs) {
            theme.applyDialog(dialog);
        }
        onRuntimeThemeChanged(theme);
    }

    protected void onRuntimeThemeChanged(RuntimeTheme theme) {}

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.forActivity(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nightMode = RuntimeTheme.nightMode(getResources().getConfiguration());
        Tools.setFullscreen(this, setFullscreen());
        new RuntimeTheme(this).applySystemBars(getWindow());
        Tools.updateWindowSize(this);
    }

    /** @return Whether the activity should be set as a fullscreen one */
    public boolean setFullscreen(){
        return true;
    }


    @Override
    public void startActivity(Intent i) {
        super.startActivity(i);
        //new Throwable("StartActivity").printStackTrace();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tools.checkStorageInteractive(this);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Tools.setFullscreen(this, setFullscreen());
        Tools.ignoreNotch(shouldIgnoreNotch(),this);
    }

    /** @return Whether or not the notch should be ignored */
    protected boolean shouldIgnoreNotch(){
        return PREF_IGNORE_NOTCH;
    }
}
