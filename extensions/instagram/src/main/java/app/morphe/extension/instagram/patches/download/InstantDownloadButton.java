/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.MediaType;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Download button on the Instants viewer. The viewer is its own Activity, and its top-bar icons are drawn by
 * Compose. They are not Views, so they cannot be found by id. The button is a native ImageView in the activity's
 * content frame, placed from fixed dp offsets to the left of the grid icon.
 *
 * <p>The patch calls {@link #onInstantMediaSeen} at the entry of every viewer method that takes the viewer
 * state, passing the `Media` of the Instant on screen. A null media (empty list) never reaches this method.
 *
 * <p>Stopgap: the position is not read from the layout. Calibrate the offsets below on each device.
 */
@SuppressWarnings("unused")
public final class InstantDownloadButton {
    /*
     * Measured from the 449 viewer on one device (POCO peridot, 1220x2712 px, density 3.0). The camera icon
     * ends 12 dp from the right edge, the grid icon sits 4 dp to the left of the camera icon, and each top-bar
     * icon is 48 dp. The download button goes 4 dp to the left of the grid icon.
     */
    private static final int ICON_DP = 48;
    private static final int ICON_GAP_DP = 4;
    private static final int RIGHT_EDGE_DP = 12;

    /**
     * Top of the top-bar icons below the status-bar inset. Measured on the same device: the grid icon's top is at
     * 127 px, and the status-bar inset is 115 px at density 3.0, so 12 px = 4 dp. Calibrate on other devices.
     */
    private static final int TOP_BELOW_STATUS_BAR_DP = 4;

    /** The Instants viewer's Activity. Its class name is not obfuscated, and the probe logged it at runtime. */
    private static final String VIEWER_ACTIVITY = "com.instagram.modal.TransparentModalActivity";
    private static final Object BUTTON_TAG = new Object();
    private static final String DRAWABLE_DOWNLOAD_ICON = "instagram_download_outline_24";

    private static boolean installed;
    private static WeakReference<Activity> viewerActivity = new WeakReference<>(null);
    private static Object currentMedia;
    private static String lastLayout;

    private InstantDownloadButton() {
    }

    /** Tracks the resumed Activity so the button can be placed in the one hosting the viewer. Idempotent. */
    public static void install() {
        if (installed) return;
        Context context = Utils.getContext();
        if (context == null) return;
        installed = true;
        Application application = (Application) context.getApplicationContext();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                viewerActivity = new WeakReference<>(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (!isViewerActivity(activity)) return;
                FrameLayout content = contentFrame(activity);
                if (content != null) hideButton(content);
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (viewerActivity.get() != activity) return;
                viewerActivity = new WeakReference<>(null);
                currentMedia = null;
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }
        });
    }

    /** Called on every viewer composition. Remembers the Instant's media and places the button. */
    public static void onInstantMediaSeen(Object media) {
        try {
            install();
            currentMedia = media;
            Activity activity = viewerActivity.get();
            if (!isViewerActivity(activity)) return;

            FrameLayout content = contentFrame(activity);
            if (content == null) return;
            if (!Settings.instantDownloadButton()) {
                hideButton(content);
                return;
            }
            placeButton(content);
        } catch (Exception e) {
            Logger.printException(() -> "InstantDownloadButton failure", e);
        }
    }

    private static boolean isViewerActivity(Activity activity) {
        return activity != null && VIEWER_ACTIVITY.equals(activity.getClass().getName());
    }

    private static FrameLayout contentFrame(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        return content instanceof FrameLayout ? (FrameLayout) content : null;
    }

    /** Places the button from the content frame's width and the status-bar inset. The offsets are in the constants. */
    private static void placeButton(FrameLayout content) {
        int contentWidth = content.getWidth();
        if (contentWidth <= 0) {
            // Not laid out yet, which is common on the first composition of a new viewer. Try again after layout.
            if (content.isAttachedToWindow()) content.post(() -> placeButton(content));
            return;
        }
        if (content.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            // Not tested in RTL, and the mirrored position of the icons is unknown. Hide rather than guess.
            hideButton(content);
            return;
        }
        WindowInsets insets = content.getRootWindowInsets();
        if (insets == null) {
            hideButton(content);
            return;
        }

        float density = content.getResources().getDisplayMetrics().density;
        int size = dp(ICON_DP, density);
        int gap = dp(ICON_GAP_DP, density);
        // Right to left: the camera icon, the grid icon, then the download button, with a gap between each.
        int left = contentWidth - dp(RIGHT_EDGE_DP, density) - size * 3 - gap * 2;

        int statusTop = statusBarTop(insets);
        int[] contentOnScreen = new int[2];
        content.getLocationOnScreen(contentOnScreen);
        int top = statusTop + dp(TOP_BELOW_STATUS_BAR_DP, density) - contentOnScreen[1];

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = left;
        params.topMargin = top;

        ImageView button = content.findViewWithTag(BUTTON_TAG);
        if (button == null) {
            button = createButton(content.getContext());
            content.addView(button, params);
        } else {
            button.setLayoutParams(params);
        }
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> downloadInstant(v.getContext()));

        String layout = "contentWidth=" + contentWidth + " left=" + left + " top=" + top
                + " statusTop=" + statusTop + " contentScreenY=" + contentOnScreen[1]
                + " size=" + size + " density=" + density;
        if (!layout.equals(lastLayout)) {
            lastLayout = layout;
            Logger.printInfo(() -> "Instant download button placed: " + layout);
        }
    }

    /** Status-bar height in px. The inset API differs by version, and this extension's minSdk is 26. */
    private static int statusBarTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private static int dp(int value, float density) {
        return Math.round(value * density);
    }

    private static ImageView createButton(Context context) {
        ImageView button = new ImageView(context);
        button.setTag(BUTTON_TAG);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int drawableId = ResourceUtils.getIdentifier(context, ResourceType.DRAWABLE, DRAWABLE_DOWNLOAD_ICON);
        if (drawableId != 0) button.setImageDrawable(context.getDrawable(drawableId));
        // The viewer is always dark, and its icons are white.
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(str("piko_download_current_media"));
        return button;
    }

    private static void hideButton(FrameLayout content) {
        View button = content.findViewWithTag(BUTTON_TAG);
        if (button != null) button.setVisibility(View.GONE);
    }

    /** Downloads the Instant that was on screen last. Photo and video both use the media's own link. */
    private static void downloadInstant(Context context) {
        Object media = currentMedia;
        if (media == null) return;
        try {
            DownloadUtils.downloadMedia(context, new MediaData(media), 0, MediaType.ANY);
        } catch (Exception e) {
            Logger.printException(() -> "Error at instant download", e);
            Utils.showToastShort(e.getMessage());
        }
    }
}
