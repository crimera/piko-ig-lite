/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.crimera.downloader;

/** User-facing texts of the shared downloader. */
final class DownloadMessages {
    static final String MEDIA_EXISTS = "Media exists";
    static final String ONGOING = "Downloading: ";
    static final String COMPLETED = "Downloaded: ";
    static final String ERROR = "Download Error: ";

    static final String SET_PATH_FAILED = "Failed to save download folder";
    static final String SET_PATH_SUCCESS = "Download directory updated!";
    static final String GRANT_PERMISSION = "Choose a download folder to continue";
    static final String GRANT_PERMISSION_FAILED = "Could not open folder picker";

    private DownloadMessages() {
    }
}
