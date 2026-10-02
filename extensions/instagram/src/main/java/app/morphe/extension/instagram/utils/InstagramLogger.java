/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.utils;

import app.morphe.extension.crimera.logging.LogSanitizer;
import app.morphe.extension.crimera.logging.PikoLogger;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Instagram binding of the shared {@link PikoLogger}. Morphe's info and exception log methods are
 * unconditional, so Instagram code logs through here and output follows the "Piko Debug" setting.
 *
 * <p>Nothing is captured for export yet: Instagram has no equivalent of the server error hooks,
 * so the capture switch is off.
 */
public final class InstagramLogger {
    private static final PikoLogger LOGGER = new PikoLogger(
            InstagramLogger::isLoggingEnabled,
            () -> false,
            LogSanitizer.standard()
    );

    private InstagramLogger() {
    }

    /**
     * Instagram can read preferences before it has a context, in which case the setting resolves to
     * its default. Anything unexpected also reads as off: diagnostics are opt-in.
     */
    public static boolean isLoggingEnabled() {
        try {
            return Boolean.TRUE.equals(SharedPref.getBooleanPref(Settings.PIKO_DEBUG));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void printInfo(Logger.LogMessage message) {
        LOGGER.printInfo(message);
    }

    public static void printInfo(Logger.LogMessage message, Exception exception) {
        LOGGER.printInfo(message, exception);
    }

    public static void printException(Logger.LogMessage message) {
        LOGGER.printException(message);
    }

    public static void printException(Logger.LogMessage message, Throwable throwable) {
        LOGGER.printException(message, throwable);
    }
}
