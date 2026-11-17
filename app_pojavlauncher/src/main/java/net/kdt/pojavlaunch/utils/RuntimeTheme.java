package net.kdt.pojavlaunch.utils;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.WindowCompat;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.R;

public final class RuntimeTheme {
    private final Context context;
    private final ColorStateList text;
    private final ColorStateList hint;
    private final ColorStateList control;
    private final ColorStateList progress;
    private final ColorStateList switchThumb;
    private final ColorStateList switchTrack;
    private final ColorStateList toggleBackground;
    private final ColorStateList ripple;

    public RuntimeTheme(Context context) {
        this.context = context;
        text = context.getColorStateList(R.color.runtime_text);
        hint = context.getColorStateList(R.color.runtime_hint);
        control = context.getColorStateList(R.color.runtime_control);
        progress = context.getColorStateList(R.color.runtime_progress);
        switchThumb = context.getColorStateList(R.color.runtime_switch_thumb);
        toggleBackground = context.getColorStateList(R.color.runtime_toggle_background);
        switchTrack = context.getColorStateList(R.color.runtime_switch_track);
        ripple = ColorStateList.valueOf(color(R.color.runtime_ripple));
    }

    public static int nightMode(Configuration configuration) {
        return configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
    }

    public int color(@ColorRes int resource) {
        return context.getColor(resource);
    }

    public Drawable drawable(@DrawableRes int resource) {
        return context.getDrawable(resource);
    }

    public void applySystemBars(Window window) {
        boolean light = context.getResources().getBoolean(R.bool.runtime_light_bars);
        window.setStatusBarColor(color(R.color.background_status_bar));
        window.setNavigationBarColor(color(R.color.background_app));
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(light);
    }

    public void applyControls(View view) {
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            label.setTextColor(text);
            label.setHintTextColor(hint);
        }

        if (view instanceof Switch) {
            Switch toggle = (Switch) view;
            toggle.setThumbTintList(switchThumb);
            toggle.setTrackTintList(switchTrack);
        } else if (view instanceof ToggleButton) {
            view.setBackgroundTintList(toggleBackground);
        } else if (view instanceof CompoundButton) {
            ((CompoundButton) view).setButtonTintList(control);
        } else if (view instanceof CheckedTextView) {
            ((CheckedTextView) view).setCheckMarkTintList(control);
        } else if (view instanceof SeekBar) {
            SeekBar slider = (SeekBar) view;
            slider.setThumbTintList(progress);
            slider.setProgressTintList(progress);
            slider.setProgressBackgroundTintList(control);
        } else if (view instanceof ProgressBar) {
            ((ProgressBar) view).setIndeterminateTintList(progress);
        } else if (view instanceof EditText) {
            view.setBackgroundTintList(control);
        } else if (view instanceof Spinner) {
            applySpinner((Spinner) view);
        }

        Drawable background = view.getBackground();
        if (background instanceof RippleDrawable) {
            ((RippleDrawable) background.mutate()).setColor(ripple);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyControls(group.getChildAt(i));
            }
        }
    }

    private void applySpinner(Spinner spinner) {
        Drawable background = spinner.getPopupBackground();
        if (background instanceof GradientDrawable) {
            // 열린 드롭다운이 참조하는 배경도 같은 drawable을 통해 갱신한다.
            ((GradientDrawable) background.mutate()).setColor(color(R.color.runtime_surface));
        }
    }

    public void applyMenu(ListView menu) {
        menu.setBackgroundColor(color(R.color.runtime_surface));
        menu.setSelector(drawable(R.drawable.runtime_selection));
        applyControls(menu);
    }

    public void applyDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(drawable(R.drawable.runtime_dialog_background));
        applyControls(window.getDecorView());
    }

    public static AlertDialog show(AlertDialog.Builder builder) {
        return show(builder.create());
    }

    public static <T extends Dialog> T show(T dialog) {
        dialog.show();
        Context owner = dialog.getContext();
        while (owner instanceof ContextWrapper) {
            if (owner instanceof BaseActivity) {
                ((BaseActivity) owner).trackRuntimeDialog(dialog);
                break;
            }
            owner = ((ContextWrapper) owner).getBaseContext();
        }
        return dialog;
    }
}
