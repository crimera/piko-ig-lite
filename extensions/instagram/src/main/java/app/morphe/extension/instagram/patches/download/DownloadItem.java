/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

/** One downloadable media of a post (a single photo or video, or one carousel page). */
final class DownloadItem {
    final String label;
    final String url;
    final boolean video;

    DownloadItem(String label, String url, boolean video) {
        this.label = label;
        this.url = url;
        this.video = video;
    }
}
