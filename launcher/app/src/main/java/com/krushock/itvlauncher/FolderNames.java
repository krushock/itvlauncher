package com.krushock.itvlauncher;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggested folder names, as Apple TV suggests "Games" or "Entertainment": from the category the apps declare
 * (android:appCategory). The most common category among the apps wins; apps that declare none don't count.
 */
final class FolderNames {

    private FolderNames() {
    }

    static String suggest(Context ctx, List<String> pkgs) {
        Map<Integer, Integer> votes = new HashMap<>();
        int best = -1, bestVotes = 0;
        for (String pkg : pkgs) {
            int cat = category(ctx, pkg);
            if (cat == ApplicationInfo.CATEGORY_UNDEFINED) continue;
            int v = votes.merge(cat, 1, Integer::sum);
            if (v > bestVotes) {
                bestVotes = v;
                best = cat;
            }
        }
        return ctx.getString(name(best));
    }

    @SuppressWarnings("deprecation")
    private static int category(Context ctx, String pkg) {
        if (pkg == null) return ApplicationInfo.CATEGORY_UNDEFINED;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category != ApplicationInfo.CATEGORY_UNDEFINED) return ai.category;
            // Older games set only the "is game" flag.
            return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0 ? ApplicationInfo.CATEGORY_GAME
                    : ApplicationInfo.CATEGORY_UNDEFINED;
        } catch (Exception e) {
            return ApplicationInfo.CATEGORY_UNDEFINED;
        }
    }

    private static int name(int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME: return R.string.folder_games;
            case ApplicationInfo.CATEGORY_AUDIO: return R.string.folder_music;
            case ApplicationInfo.CATEGORY_VIDEO: return R.string.folder_entertainment;
            case ApplicationInfo.CATEGORY_IMAGE: return R.string.folder_photos;
            case ApplicationInfo.CATEGORY_SOCIAL: return R.string.folder_social;
            case ApplicationInfo.CATEGORY_NEWS: return R.string.folder_news;
            case ApplicationInfo.CATEGORY_MAPS: return R.string.folder_travel;
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return R.string.folder_productivity;
            case ApplicationInfo.CATEGORY_ACCESSIBILITY: return R.string.folder_utilities;
            default: return R.string.folder_default_name;
        }
    }
}
