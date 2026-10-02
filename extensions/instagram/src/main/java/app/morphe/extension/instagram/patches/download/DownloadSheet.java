/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.instagram.ui.BottomSheetView;
import app.morphe.extension.instagram.ui.ButtonView;
import app.morphe.extension.instagram.ui.IconView;
import app.morphe.extension.instagram.ui.ListItem;
import app.morphe.extension.instagram.ui.SheetTheme;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Media picker bottom sheet for the feed download button, shown for posts with more than one media.
 *
 * <ul>
 *   <li>Tap a row to download that media.
 *   <li>Long-press a row to enter selection mode; checkboxes then replace the copy-link action and
 *       the action button downloads the selection.
 *   <li>The action button downloads everything until a partial selection is made.
 *   <li>Leaving selection mode happens automatically once nothing is selected.
 * </ul>
 */
final class DownloadSheet {
    interface Listener {
        void onDownloadItem(int index);

        void onDownloadAll();
    }

    private DownloadSheet() {
    }

    static void show(Context context, List<DownloadItem> downloads, String username, Listener listener) {
        Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Logger.printDebug(() -> "Download sheet skipped: no usable activity");
            return;
        }

        final int total = downloads.size();
        final BottomSheetView sheet = new BottomSheetView(activity);
        final String defaultTitle = str("piko_download_sheet_title");
        final String defaultSubtitle = username != null && !username.trim().isEmpty()
                ? str("piko_download_sheet_from", username.trim())
                : str("piko_download_sheet_subtitle");
        sheet.setTitle(defaultTitle);
        sheet.setSubtitle(defaultSubtitle);

        final ButtonView downloadButton = new ButtonView(
                activity, ButtonView.ButtonStyle.FILLED, str("piko_download_all_count", total));
        sheet.addButton(downloadButton);

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        final Set<Integer> selected = new LinkedHashSet<>();
        final boolean[] selecting = {false};
        final List<ListItem> rows = new ArrayList<>(total);
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            if (selecting[0] && selected.isEmpty()) selecting[0] = false;

            if (selecting[0]) {
                sheet.setTitle(str("piko_download_sheet_select_title"));
                sheet.setSubtitle(str("piko_download_sheet_selected", selected.size(), total));
                downloadButton.setText(selected.size() == total
                        ? str("piko_download_all_count", total)
                        : str("piko_download_count", selected.size()));
            } else {
                sheet.setTitle(defaultTitle);
                sheet.setSubtitle(defaultSubtitle);
                downloadButton.setText(str("piko_download_all_count", total));
            }

            for (int i = 0; i < total; i++) {
                bindRow(activity, rows.get(i), downloads.get(i), i, selecting[0], selected, sheet, listener, refresh[0]);
            }
        };

        for (int i = 0; i < total; i++) {
            final int index = i;
            ListItem row = new ListItem(activity);
            row.setTitle(downloads.get(i).label + " " + (i + 1));
            row.setOnClickListener(v -> {
                if (selecting[0]) {
                    toggle(selected, index);
                    refresh[0].run();
                } else {
                    sheet.dismiss();
                    listener.onDownloadItem(index);
                }
            });
            row.setOnLongClickListener(v -> {
                if (selecting[0]) return false;
                try {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (RuntimeException ignored) {
                    // Haptics are best effort.
                }
                selecting[0] = true;
                selected.clear();
                selected.add(index);
                refresh[0].run();
                return true;
            });
            rows.add(row);
            list.addView(row);
        }

        downloadButton.setOnClickListener(v -> {
            if (selecting[0] && selected.isEmpty()) return;
            sheet.dismiss();
            if (!selecting[0] || selected.size() == total) {
                listener.onDownloadAll();
            } else {
                for (int index : new ArrayList<>(selected)) listener.onDownloadItem(index);
            }
        });

        refresh[0].run();
        sheet.setScrollableBodyView(list);
        sheet.show();
    }

    private static void bindRow(
            Activity activity,
            ListItem row,
            DownloadItem item,
            int index,
            boolean selecting,
            Set<Integer> selected,
            BottomSheetView sheet,
            Listener listener,
            Runnable refresh
    ) {
        boolean isSelected = selected.contains(index);
        row.setLeadingIcon(
                item.video ? IconView.IconType.VIDEO : IconView.IconType.IMAGE,
                SheetTheme.primaryAccent(activity),
                selecting && isSelected ? SheetTheme.primaryContainer(activity) : SheetTheme.surfaceVariant(activity));

        if (selecting) {
            row.createTrailingIconButton(
                    isSelected ? IconView.IconType.CHECKBOX_CHECKED : IconView.IconType.CHECKBOX_UNCHECKED,
                    isSelected ? SheetTheme.checkboxChecked(activity) : SheetTheme.secondaryText(activity),
                    v -> {
                        toggle(selected, index);
                        refresh.run();
                    });
        } else {
            row.createTrailingIconButton(
                    IconView.IconType.COPY_LINK,
                    SheetTheme.secondaryText(activity),
                    v -> {
                        sheet.dismiss();
                        Utils.setClipboard(item.url);
                        Utils.showToastShort(str("piko_copied_media_link"));
                    });
        }
    }

    private static void toggle(Set<Integer> selected, int index) {
        if (!selected.remove(index)) selected.add(index);
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return Utils.getActivity();
    }
}
