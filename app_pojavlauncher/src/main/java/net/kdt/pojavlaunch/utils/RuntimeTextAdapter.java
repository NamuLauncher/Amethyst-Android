package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;

import net.kdt.pojavlaunch.R;

public final class RuntimeTextAdapter<T> extends ArrayAdapter<T> {
    public RuntimeTextAdapter(Context context, @LayoutRes int layout) {
        super(context, layout);
    }

    public RuntimeTextAdapter(Context context, @LayoutRes int layout, T[] items) {
        super(context, layout, items);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return applyCurrentColors(super.getView(position, convertView, parent), parent.getContext());
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return applyCurrentColors(super.getDropDownView(position, convertView, parent), parent.getContext());
    }

    private View applyCurrentColors(View row, Context context) {
        ((TextView) row).setTextColor(context.getColorStateList(R.color.runtime_text));
        if (row instanceof CheckedTextView) {
            ((CheckedTextView) row).setCheckMarkTintList(
                    context.getColorStateList(R.color.runtime_control));
        }
        return row;
    }
}
