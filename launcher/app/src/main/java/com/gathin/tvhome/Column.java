package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds Home's sections at their full height, however tall, so every row is laid out and can take focus;
 * Home slides the column itself (translationY) instead of scrolling it.
 */
public class Column extends FrameLayout {

    public Column(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(0, 0, c.getMeasuredWidth(), c.getMeasuredHeight());
        }
    }
}
