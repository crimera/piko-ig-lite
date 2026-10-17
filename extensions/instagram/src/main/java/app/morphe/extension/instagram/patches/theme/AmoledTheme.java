/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/** Applies the AMOLED overlay only to a dark Prism activity theme. */
public final class AmoledTheme {
    private static final int DARK_PRISM_BACKGROUND = 0xFF0C1014;
    private static final String PRIMARY_BACKGROUND_ATTRIBUTE = "igds_color_primary_background";
    private static final String OVERLAY_STYLE = "PikoAmoledOverlay";

    private static volatile boolean resourceIdsResolved;
    private static int primaryBackgroundAttributeId;
    private static int overlayStyleId;

    private AmoledTheme() {
    }

    public static void applyOverlay(Context context) {
        try {
            if (context == null || !Settings.amoledBlack()) {
                return;
            }

            resolveResourceIds(context);
            if (primaryBackgroundAttributeId == 0 || overlayStyleId == 0) {
                return;
            }

            Resources.Theme theme = context.getTheme();
            TypedValue background = new TypedValue();
            if (!theme.resolveAttribute(primaryBackgroundAttributeId, background, true)) {
                return;
            }
            if (background.type < TypedValue.TYPE_FIRST_COLOR_INT
                    || background.type > TypedValue.TYPE_LAST_COLOR_INT
                    || background.data != DARK_PRISM_BACKGROUND) {
                return;
            }

            theme.applyStyle(overlayStyleId, true);
        } catch (Throwable ignored) {
            // A theme lookup must never break Activity creation.
        }
    }

    private static void resolveResourceIds(Context context) {
        if (resourceIdsResolved) {
            return;
        }
        synchronized (AmoledTheme.class) {
            if (resourceIdsResolved) {
                return;
            }
            primaryBackgroundAttributeId = ResourceUtils.getIdentifier(
                    context,
                    ResourceType.ATTR,
                    PRIMARY_BACKGROUND_ATTRIBUTE
            );
            overlayStyleId = ResourceUtils.getIdentifier(context, ResourceType.STYLE, OVERLAY_STYLE);
            resourceIdsResolved = true;
        }
    }
}
