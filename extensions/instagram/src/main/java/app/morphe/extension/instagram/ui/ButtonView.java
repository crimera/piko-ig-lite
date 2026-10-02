/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Reusable button component supporting TEXT, TONAL, and FILLED styles.
 */
public class ButtonView extends TextView {

    public enum ButtonStyle {
        TEXT,
        TONAL,
        FILLED
    }

    private ButtonStyle style = ButtonStyle.TEXT;

    public ButtonView(Context context) {
        super(context);
        init();
    }

    public ButtonView(Context context, ButtonStyle style, CharSequence text) {
        super(context);
        this.style = style;
        setText(text);
        init();
    }

    public void setButtonStyle(ButtonStyle style) {
        this.style = style;
        updateStyle();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1.0f : 0.4f);
    }

    private void init() {
        setGravity(Gravity.CENTER);
        setFocusable(true);
        setClickable(true);

        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        int height = SheetTheme.dpToPx(getContext(), 40f);
        int paddingHoriz = SheetTheme.dpToPx(getContext(), 20f);
        setPadding(paddingHoriz, 0, paddingHoriz, 0);
        setMinHeight(height);

        updateStyle();
    }

    private void updateStyle() {
        Context context = getContext();
        int cornerRadius = SheetTheme.dpToPx(context, 20f);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(cornerRadius);

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(cornerRadius);
        mask.setColor(Color.BLACK);

        int textColor;
        int bgColor;
        int rippleColor = SheetTheme.rippleColor(context);

        switch (style) {
            case FILLED:
                bgColor = SheetTheme.primaryAccent(context);
                textColor = SheetTheme.onPrimaryAccent(context);
                shape.setColor(bgColor);
                setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, mask));
                break;

            case TONAL:
                bgColor = SheetTheme.primaryContainer(context);
                textColor = SheetTheme.onPrimaryContainer(context);
                shape.setColor(bgColor);
                setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask));
                break;

            case TEXT:
            default:
                bgColor = Color.TRANSPARENT;
                textColor = SheetTheme.primaryAccent(context);
                shape.setColor(bgColor);

                int textRipple = Color.argb(40, Color.red(textColor), Color.green(textColor), Color.blue(textColor));
                setBackground(new RippleDrawable(ColorStateList.valueOf(textRipple), shape, mask));
                break;
        }

        setTextColor(textColor);
    }
}
