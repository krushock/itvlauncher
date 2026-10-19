package com.krushock.itvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.provider.Settings;

import java.util.Calendar;

/**
 * Display settings from Controls → Display, as on Apple TV: Light, Dark or Automatic appearance, Larger text, and
 * Reduce Motion. Appearance and text size apply to the launcher's own screens (screensavers stay dark).
 */
final class Look {

    static final int DARK = 0, LIGHT = 1, AUTO = 2;

    private Look() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    static int mode(Context ctx) {
        return prefs(ctx).getInt("mode", DARK);
    }

    static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt("mode", mode).apply();
    }

    /** Whether the launcher is light right now; Automatic is light from 7:00 to 19:00, like a day/night switch. */
    static boolean light(Context ctx) {
        int m = mode(ctx);
        if (m == LIGHT) return true;
        if (m == DARK) return false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 7 && hour < 19;
    }

    static boolean largeText(Context ctx) {
        return prefs(ctx).getBoolean("large_text", false);
    }

    static void setLargeText(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("large_text", on).apply();
    }

    static boolean reduceMotionSetting(Context ctx) {
        return prefs(ctx).getBoolean("reduce_motion", false);
    }

    static void setReduceMotion(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("reduce_motion", on).apply();
    }

    /** Reduce Motion is on, or the TV's animations are switched off altogether (developer options / accessibility). */
    static boolean reduceMotion(Context ctx) {
        if (reduceMotionSetting(ctx)) return true;
        return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    /** The configuration override for the launcher's activity: light or dark resources, and the text size. */
    static Configuration override(Context base) {
        Configuration c = new Configuration();
        int ui = base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        c.uiMode = ui | (light(base) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
        float scale = base.getResources().getConfiguration().fontScale;
        c.fontScale = (scale > 0 ? scale : 1f) * (largeText(base) ? 1.25f : 1f);
        return c;
    }
}
