/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.settings;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.instagram.utils.InstagramLogger;

/** Native settings shared by Instagram extension features, independent of optional patch settings. */
final class InstagramBuiltInSettings {
    private static final String ADVANCED_CATEGORY_ID = "instagram.advanced";
    private static final String DEBUG_TOOLS_GROUP_ID = "instagram.advanced.debug_tools";

    private InstagramBuiltInSettings() {
    }

    static void register() {
        SettingsRegistry.registerCategory(
                ADVANCED_CATEGORY_ID,
                "piko_ig_category_advanced_title",
                "piko_ig_category_advanced_summary",
                null,
                600
        );
        SettingsRegistry.registerGroup(
                ADVANCED_CATEGORY_ID,
                DEBUG_TOOLS_GROUP_ID,
                "piko_ig_group_debug_tools_title",
                "piko_ig_group_debug_tools_summary",
                null,
                100
        );
        SettingsRegistry.registerToggle(
                DEBUG_TOOLS_GROUP_ID,
                InstagramLogger.LOGGING_SETTING_ID,
                "piko_ig_logging_title",
                "piko_ig_logging_summary",
                100
        );
        SettingsRegistry.configureToggle(InstagramLogger.LOGGING_SETTING_ID, false, false);
    }
}
