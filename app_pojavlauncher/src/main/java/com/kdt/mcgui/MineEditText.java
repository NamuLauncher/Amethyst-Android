package com.kdt.mcgui;

import android.content.*;
import android.util.*;

import net.kdt.pojavlaunch.R;

public class MineEditText extends androidx.appcompat.widget.AppCompatEditText {
	public MineEditText(Context ctx) {
		super(ctx);
		init();
	}

	public MineEditText(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
	}

	public void init() {
		setBackgroundTintList(getContext().getColorStateList(R.color.runtime_control));
		setPadding(5, 5, 5, 5);
	}
}
