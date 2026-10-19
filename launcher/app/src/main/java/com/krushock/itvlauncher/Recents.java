package com.krushock.itvlauncher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recently used apps, for the Recent tile (Apple TV's app switcher): Android's usage history when the launcher has
 * usage access (granted over adb at install), merged with the launches the launcher records itself, so it still
 * works without that access.
 */
final class Recents {

    private static final long WINDOW_MS = 14L * 24 * 60 * 60_000L;

    private Recents() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("recents", Context.MODE_PRIVATE);
    }

    /** Remember that an app was opened from Home. */
    static void record(Context ctx, String pkg) {
        if (pkg != null) prefs(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** Packages that can be opened on the TV, most recently used first. */
    static List<String> recent(Context ctx, int max) {
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (e.getValue() instanceof Long) last.put(e.getKey(), (Long) e.getValue());
        }
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm != null) {
            try {
                for (Map.Entry<String, UsageStats> e : usm.queryAndAggregateUsageStats(now - WINDOW_MS, now).entrySet()) {
                    long t = e.getValue().getLastTimeUsed();
                    Long mine = last.get(e.getKey());
                    if (t > 0 && (mine == null || t > mine)) last.put(e.getKey(), t);
                }
            } catch (SecurityException ignored) {
                // No usage access: the launcher's own record still works.
            }
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : sorted) {
            String pkg = e.getKey();
            if (pkg.equals(ctx.getPackageName()) || now - e.getValue() > WINDOW_MS) continue;
            if (ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) == null) continue;   // not a TV app
            out.add(pkg);
            if (out.size() >= max) break;
        }
        return out;
    }
}
