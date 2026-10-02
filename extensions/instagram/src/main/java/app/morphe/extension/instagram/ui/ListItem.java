/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * List row for the bottom sheet: a leading icon badge (40x40dp tonal box), title, supporting
 * text and trailing icon buttons.
 */
public class ListItem extends LinearLayout {
    private FrameLayout leadingContainer;
    private IconView leadingIconView;
    private ImageView leadingImageView;
    private TextView titleView;
    private TextView subtitleView;
    private FrameLayout trailingContainer;

    public ListItem(Context context) {
        super(context);
        init();
    }

    private void init() {
        Context context = getContext();
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(SheetTheme.dpToPx(context, 72f));

        int padHoriz = SheetTheme.dpToPx(context, 16f);
        int padVert = SheetTheme.dpToPx(context, 12f);
        setPadding(padHoriz, padVert, padHoriz, padVert);

        // Rectangular ripple background (no rounded corners).
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.BLACK);
        setBackground(new RippleDrawable(
                ColorStateList.valueOf(SheetTheme.rippleColor(context)), null, mask));
        setClickable(true);
        setFocusable(true);

        // Leading badge.
        leadingContainer = new FrameLayout(context);
        int badgeSize = SheetTheme.dpToPx(context, 40f);
        LinearLayout.LayoutParams leadingParams = new LinearLayout.LayoutParams(badgeSize, badgeSize);
        leadingParams.setMarginEnd(SheetTheme.dpToPx(context, 16f));
        leadingContainer.setLayoutParams(leadingParams);
        setLeadingContainerBackground(SheetTheme.surfaceVariant(context));

        leadingIconView = new IconView(context);
        int iconSize = SheetTheme.dpToPx(context, 24f);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        leadingContainer.addView(leadingIconView, iconParams);

        leadingImageView = new ImageView(context);
        leadingImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        leadingImageView.setVisibility(View.GONE);
        leadingContainer.addView(leadingImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(leadingContainer);

        // Title and subtitle.
        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginEnd(SheetTheme.dpToPx(context, 12f));
        textContainer.setLayoutParams(textParams);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(SheetTheme.primaryText(context));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textContainer.addView(titleView);

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitleView.setTextColor(SheetTheme.secondaryText(context));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setPadding(0, SheetTheme.dpToPx(context, 2f), 0, 0);
        subtitleView.setVisibility(View.GONE);
        textContainer.addView(subtitleView);
        addView(textContainer);

        // Trailing actions.
        trailingContainer = new FrameLayout(context);
        trailingContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        trailingContainer.setVisibility(View.GONE);
        addView(trailingContainer);
    }

    public void setTitle(CharSequence title) {
        titleView.setText(title);
    }

    public void setSubtitle(@Nullable CharSequence subtitle) {
        if (TextUtils.isEmpty(subtitle)) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        }
    }

    public void setLeadingIcon(IconView.IconType iconType, int iconColor, int containerBgColor) {
        leadingImageView.setImageDrawable(null);
        leadingImageView.setVisibility(View.GONE);
        leadingIconView.setVisibility(View.VISIBLE);
        leadingIconView.setIconType(iconType);
        leadingIconView.setIconColor(iconColor);
        setLeadingContainerBackground(containerBgColor);
    }

    /** Swaps the leading badge to a media preview; the icon stays behind it as the fallback. */
    public void setLeadingImage(Bitmap image, int containerBgColor) {
        if (image == null || image.isRecycled()) return;
        setLeadingContainerBackground(containerBgColor);
        leadingImageView.setImageBitmap(image);
        leadingImageView.setVisibility(View.VISIBLE);
        leadingIconView.setVisibility(View.GONE);
    }

    private void setLeadingContainerBackground(int color) {
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(SheetTheme.dpToPx(getContext(), 12f));
        badgeBg.setColor(color);
        leadingContainer.setBackground(badgeBg);
        leadingContainer.setClipToOutline(true);
    }

    public void setTrailingView(@Nullable View view) {
        trailingContainer.removeAllViews();
        if (view == null) {
            trailingContainer.setVisibility(View.GONE);
        } else {
            trailingContainer.addView(view);
            trailingContainer.setVisibility(View.VISIBLE);
        }
    }

    public View createTrailingIconButton(IconView.IconType iconType, int iconColor, OnClickListener listener) {
        View button = buildTrailingIconButton(iconType, iconColor, listener);
        setTrailingView(button);
        return button;
    }

    private View buildTrailingIconButton(IconView.IconType iconType, int iconColor, OnClickListener listener) {
        Context context = getContext();
        FrameLayout btnContainer = new FrameLayout(context);
        int btnSize = SheetTheme.dpToPx(context, 40f);
        btnContainer.setLayoutParams(new ViewGroup.LayoutParams(btnSize, btnSize));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.BLACK);
        btnContainer.setBackground(new RippleDrawable(
                ColorStateList.valueOf(SheetTheme.rippleColor(context)), null, mask));
        btnContainer.setClickable(true);
        btnContainer.setFocusable(true);

        IconView iconView = new IconView(context, iconType, iconColor);
        int iconSize = SheetTheme.dpToPx(context, 20f);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        btnContainer.addView(iconView, iconParams);

        if (listener != null) btnContainer.setOnClickListener(listener);
        return btnContainer;
    }
}
