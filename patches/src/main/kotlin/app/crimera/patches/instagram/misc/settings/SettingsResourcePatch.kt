/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.settings

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Registers the activities the patches launch themselves. The settings suite is not part of this
 * bundle; the folder picker is, because the shared downloader needs to select a destination
 * directory through SAF.
 */
val addSettingsActivityPatch =
    resourcePatch(
        description = "Adds extension activities to the Android manifest.",
    ) {
        finalize {
            document("AndroidManifest.xml").use { document ->
                val application = document.getElementsByTagName("application").item(0) as Element

                val activity = document.createElement("activity")
                activity.setAttribute("android:name", "app.morphe.extension.crimera.downloader.FolderPickerActivity")
                activity.setAttribute("android:exported", "false")
                application.appendChild(activity)
            }
        }
    }
