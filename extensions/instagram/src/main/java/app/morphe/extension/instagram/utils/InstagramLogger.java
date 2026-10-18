/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.utils;

import app.morphe.extension.crimera.logging.LogSanitizer;
import app.morphe.extension.crimera.logging.PikoLogger;
import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * Instagram binding of the shared {@link PikoLogger}. Morphe's info and exception log methods are
 * unconditional, so Instagram code logs through here and output follows {@link #isLoggingEnabled()}.
 *
 * <p>Nothing is captured for export yet: Instagram has no equivalent of the server error hooks,
 * so the capture switch is off.
 */
public final class InstagramLogger {
    public static final String LOGGING_SETTING_ID = "instagram.debug";

    private static volatile Setting<?> cachedLoggingSetting;

    private static final PikoLogger LOGGER = new PikoLogger(
            InstagramLogger::isLoggingEnabled,
            () -> false,
            LogSanitizer.standard()
    );

    private InstagramLogger() {
    }

    /** The shared logger, for the settings host. */
    public static PikoLogger logger() {
        return LOGGER;
    }

    /**
     * Diagnostics are opt-in through the built-in {@code instagram.debug} toggle; unexpected
     * reads before the settings registry is ready fail closed.
     */
    public static boolean isLoggingEnabled() {
        Setting<?> cached = cachedLoggingSetting;
        if (cached instanceof BooleanSetting booleanSetting) {
            try {
                return booleanSetting.get();
            } catch (RuntimeException ignored) {
            }
        }

        try {
            boolean enabled = SettingsRegistry.getBooleanOrDefault(LOGGING_SETTING_ID, false);
            Setting<?> resolved = SettingsRegistry.settingOrNull(LOGGING_SETTING_ID);
            if (resolved instanceof BooleanSetting) cachedLoggingSetting = resolved;
            return enabled;
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
