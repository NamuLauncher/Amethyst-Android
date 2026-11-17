package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.utils.RuntimeTheme;

public class DrawerPullButton extends View {
    public DrawerPullButton(Context context) {super(context); init();}
    public DrawerPullButton(Context context, @Nullable AttributeSet attrs) {super(context, attrs); init();}

    private final Paint mBackgroundPaint = new Paint();
    private VectorDrawableCompat mDrawable;

    private void init(){
        mDrawable = VectorDrawableCompat.create(getContext().getResources(), R.drawable.ic_sharp_settings_24, null);
        applyRuntimeTheme(new RuntimeTheme(getContext()));
    }

    public void applyRuntimeTheme(RuntimeTheme theme) {
        mBackgroundPaint.setColor(theme.color(R.color.runtime_surface));
        mDrawable.setTint(theme.color(R.color.primary_text));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawArc(getPaddingLeft(),-getHeight() + getPaddingBottom(),getWidth() - getPaddingRight(), getHeight() - getPaddingBottom(), 0, 180, true, mBackgroundPaint);

        mDrawable.setBounds(getPaddingLeft()/2, getPaddingTop()/2, getHeight() - getPaddingRight()/2, getHeight() - getPaddingBottom()/2);
        canvas.save();
        canvas.translate((getWidth()-getHeight())/2f, -getPaddingBottom()/2f);
        mDrawable.draw(canvas);
        canvas.restore();
    }
}
