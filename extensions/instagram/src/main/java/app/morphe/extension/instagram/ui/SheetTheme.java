/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;

import app.morphe.extension.shared.ResourceUtils;

/**
 * Color tokens and metrics for the bottom sheet components. Every color is resolved from the
 * host's `igds_*` theme attributes against the activity context, so the sheet follows whatever
 * theme (light, dark, Material You) the app is currently showing.
 */
public final class SheetTheme {
    private static final int LIGHT_SURFACE = Color.WHITE;
    private static final int LIGHT_TEXT = Color.rgb(0, 0, 0);
    private static final int DARK_TEXT = Color.rgb(245, 245, 245);
    private static final int LIGHT_SECONDARY_TEXT = Color.rgb(115, 115, 115);
    private static final int DARK_SECONDARY_TEXT = Color.rgb(168, 168, 168);

    private SheetTheme() {
    }

    public static int dpToPx(Context context, float dp) {
        if (context == null) return Math.round(dp);
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        ));
    }

    public static boolean isDark(Context context) {
        int background = attrColor(context, "igds_color_primary_background", Color.WHITE);
        return Color.luminance(background) < 0.5f;
    }

    /** Sheet background: the same color as the feed behind it. */
    public static int surface(Context context) {
        return attrColor(context, "igds_color_primary_background",
                isDark(context) ? Color.BLACK : LIGHT_SURFACE);
    }

    /** Leading badge background: Instagram's own secondary surface. */
    public static int surfaceVariant(Context context) {
        return attrColor(context, "igds_color_secondary_background",
                isDark(context) ? Color.rgb(26, 26, 26) : Color.rgb(239, 239, 239));
    }

    public static int primaryText(Context context) {
        return attrColor(context, "igds_color_primary_text", isDark(context) ? DARK_TEXT : LIGHT_TEXT);
    }

    public static int secondaryText(Context context) {
        return attrColor(context, "igds_color_secondary_text",
                isDark(context) ? DARK_SECONDARY_TEXT : LIGHT_SECONDARY_TEXT);
    }

    /**
     * Instagram's Prism surfaces are monochrome: controls are tinted with the text color, never
     * `primary_button` blue. This is the filled button background and the icon tint.
     */
    public static int primaryAccent(Context context) {
        return attrColor(context, "igds_color_primary_icon", primaryText(context));
    }

    /** Filled button label: the sheet color, inverted against the button. */
    public static int onPrimaryAccent(Context context) {
        return surface(context);
    }

    /** Tonal button and selected badge background: a neutral tint of the text color. */
    public static int primaryContainer(Context context) {
        return blend(surface(context), primaryText(context), isDark(context) ? 0.16f : 0.10f);
    }

    public static int onPrimaryContainer(Context context) {
        return primaryText(context);
    }

    public static int checkboxChecked(Context context) {
        return primaryAccent(context);
    }

    public static int dividerColor(Context context) {
        return attrColor(context, "igds_color_divider", withAlpha(primaryText(context), 31));
    }

    public static int rippleColor(Context context) {
        return withAlpha(primaryText(context), isDark(context) ? 40 : 32);
    }

    public static int dragHandleColor(Context context) {
        return attrColor(context, "igds_color_creation_tools_grey_02",
                isDark(context) ? Color.rgb(85, 85, 85) : Color.rgb(219, 219, 219));
    }

    /** Resolves a theme attribute that points at a color, or holds one directly. */
    private static int attrColor(Context context, String attrName, int fallback) {
        if (context == null) return fallback;
        try {
            int attrId = ResourceUtils.getAttrIdentifier(attrName);
            if (attrId == 0) return fallback;
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attrId, value, true)) return fallback;
            if (value.resourceId != 0) return context.getColor(value.resourceId);
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the neutral fallback; a missing attr must never break the sheet.
        }
        return fallback;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int blend(int base, int overlay, float ratio) {
        float inverse = 1f - ratio;
        return Color.argb(
                (int) (Color.alpha(base) * inverse + Color.alpha(overlay) * ratio),
                (int) (Color.red(base) * inverse + Color.red(overlay) * ratio),
                (int) (Color.green(base) * inverse + Color.green(overlay) * ratio),
                (int) (Color.blue(base) * inverse + Color.blue(overlay) * ratio)
        );
    }
}
