/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches.theme;

import app.morphe.extension.instagram.settings.Settings;

/** Converts the Compose palette's blue-grey near-black to pure black when the AMOLED option is enabled. */
public final class AmoledMessages {
    private static final long BLUE_GREY_BLACK = 0xFF0C1014L;
    private static final long PURE_BLACK = 0xFF000000L << 32;

    private AmoledMessages() {
    }

    public static long composeBlack(long color) {
        try {
            if ((color >>> 32) != BLUE_GREY_BLACK || !Settings.amoledBlack()) {
                return color;
            }
            return PURE_BLACK;
        } catch (Throwable ignored) {
            return color;
        }
    }
}
