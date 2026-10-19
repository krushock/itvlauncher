package com.krushock.itvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Backs up the whole arrangement (profiles, tile order, folders, hidden apps, tiles per row, weather city, display
 * settings, recent apps) to Downloads/tvhome-layout.json, plus a copy in this app's own folder on each USB drive or SD card
 * (Android/data/com.krushock.itvlauncher/files), and restores from any of those: carry a layout to another TV, or bring it
 * back after a reset. Since Android 11 an app can only read files it wrote itself or its own folder on a drive, so
 * the drive copy is the one that travels; a file at the top of a drive is still found where the TV allows it.
 * A backup can also be put in this app's own folder on the TV's storage over adb
 * (/sdcard/Android/data/com.krushock.itvlauncher/files): how a layout moves to a differently-signed install.
 */
final class LayoutBackup {

    static final String FILE = "tvhome-layout.json";
    private static final String[] PREFS = {"home_layout", "weather", "look", "recents"};

    private LayoutBackup() {
    }

    /** Writes the backup; returns the file, or null when it couldn't be written. */
    static File save(Context ctx) {
        try {
            JSONObject root = new JSONObject().put("version", 1);
            JSONObject all = new JSONObject();
            for (String name : PREFS) {
                JSONObject values = new JSONObject();
                for (Map.Entry<String, ?> e : ctx.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object v = e.getValue();
                    JSONObject typed = new JSONObject();
                    if (v instanceof String) typed.put("t", "s").put("v", v);
                    else if (v instanceof Boolean) typed.put("t", "b").put("v", v);
                    else if (v instanceof Integer) typed.put("t", "i").put("v", v);
                    else if (v instanceof Long) typed.put("t", "l").put("v", v);
                    else if (v instanceof Float) typed.put("t", "f").put("v", ((Float) v).doubleValue());
                    else if (v instanceof Set) typed.put("t", "set").put("v", new JSONArray((Set<?>) v));
                    else continue;
                    values.put(e.getKey(), typed);
                }
                all.put(name, values);
            }
            root.put("prefs", all);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            byte[] json = root.toString(2).getBytes(StandardCharsets.UTF_8);
            File[] own = ctx.getExternalFilesDirs(null);   // [0] is the TV's storage, the rest are drives
            for (int i = 1; i < own.length; i++) {
                if (own[i] == null) continue;
                try (FileOutputStream os = new FileOutputStream(new File(own[i], FILE))) {
                    os.write(json);
                } catch (Exception ignored) {
                    // A drive that's read-only or just went away: Downloads still has it.
                }
            }
            File out = new File(dir, FILE);
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(json);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** A backup to restore: Downloads first, then the top folder of each USB drive or SD card. */
    static File find(Context ctx) {
        File downloads = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE);
        if (downloads.canRead()) return downloads;
        File[] own = ctx.getExternalFilesDirs(null);
        for (int i = 0; i < own.length; i++) {   // this app's own folder on the TV and on each drive
            File f = own[i] != null ? new File(own[i], FILE) : null;
            if (f != null && f.canRead()) return f;
        }
        for (File appDir : own) {
            if (appDir == null) continue;
            // .../<volume>/Android/data/com.krushock.itvlauncher/files → .../<volume>
            String path = appDir.getAbsolutePath();
            int cut = path.indexOf("/Android/data/");
            if (cut < 0) continue;
            File f = new File(path.substring(0, cut), FILE);
            if (f.canRead()) return f;
        }
        return null;
    }

    /** Replaces the current arrangement with the backup's; false when the file isn't a readable backup. */
    static boolean restore(Context ctx, File file) {
        try {
            byte[] data;
            try (InputStream in = new FileInputStream(file)) {
                data = new byte[(int) Math.min(file.length(), 2 * 1024 * 1024)];
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            }
            JSONObject all = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONObject("prefs");
            for (String name : PREFS) {
                JSONObject values = all.optJSONObject(name);
                if (values == null) continue;
                SharedPreferences.Editor ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
                for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONObject typed = values.getJSONObject(key);
                    switch (typed.getString("t")) {
                        case "s": ed.putString(key, typed.getString("v")); break;
                        case "b": ed.putBoolean(key, typed.getBoolean("v")); break;
                        case "i": ed.putInt(key, typed.getInt("v")); break;
                        case "l": ed.putLong(key, typed.getLong("v")); break;
                        case "f": ed.putFloat(key, (float) typed.getDouble("v")); break;
                        case "set":
                            JSONArray arr = typed.getJSONArray("v");
                            Set<String> set = new HashSet<>();
                            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                            ed.putStringSet(key, set);
                            break;
                        default:
                            break;
                    }
                }
                ed.commit();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
