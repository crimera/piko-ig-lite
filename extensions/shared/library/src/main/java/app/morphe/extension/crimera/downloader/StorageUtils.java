/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import app.morphe.extension.shared.Utils;

public class StorageUtils {
    private static final String PREFERENCES_NAME = "piko_settings";
    private static final String KEY_TREE_URI = "custom_download_tree_uri";

    private static SharedPreferences preferences() {
        return Utils.getContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static void saveCustomTreeUri(Uri treeUri) {
        preferences().edit().putString(KEY_TREE_URI, treeUri.toString()).apply();
    }

    public static boolean checkStoragePermissions() {
        return getDownloadTreeUri() != null;
    }

    public static Uri getDownloadTreeUri() {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }

        String treeUriString = preferences().getString(KEY_TREE_URI, "");
        if (treeUriString == null || treeUriString.isBlank()) {
            return null;
        }

        Uri treeUri = Uri.parse(treeUriString);
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                return treeUri;
            }
        }

        return null;
    }

    public static void allowStorageAccess() {
        try {
            Context context = Utils.getContext();
            Intent intent = new Intent(context, FolderPickerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Utils.showToastShort(DownloadMessages.GRANT_PERMISSION);
        } catch (Exception e) {
            Utils.showToastShort(DownloadMessages.GRANT_PERMISSION_FAILED);
        }
    }
}
