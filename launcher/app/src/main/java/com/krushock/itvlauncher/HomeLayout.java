package com.krushock.itvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Profiles, and each profile's arrangement: tile order, hidden apps and folders, keyed by component
 * (pkg/activity) or {@link #FOLDER_PREFIX}id. Until a profile is edited, the defaults from
 * res/values/config.xml apply: dock_apps first, the rest alphabetical, and hidden_apps hidden.
 */
final class HomeLayout {

    static final String FOLDER_PREFIX = "folder:";

    static final class Folder {
        final String id;
        String name;
        final List<String> members;

        Folder(String id, String name, List<String> members) {
            this.id = id;
            this.name = name;
            this.members = members;
        }

        String key() {
            return FOLDER_PREFIX + id;
        }
    }

    private static final String PREFS = "home_layout";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_ASK_ON_WAKE = "ask_on_wake";
    private static final String FIRST_PROFILE = "p0";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Set<String> defaultHiddenPkgs;
    private String profile;
    /** Saved order, or null while the defaults apply. */
    private List<String> order;
    /** Hidden components, or null while config.xml's hidden_apps (package names) apply. */
    private Set<String> hidden;
    private final List<Folder> folders = new ArrayList<>();

    HomeLayout(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        defaultHiddenPkgs = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        profile = prefs.getString(KEY_CURRENT, FIRST_PROFILE);
        if (!profileIds().contains(profile)) profile = profileIds().get(0);
        load();
    }

    // ---- tiles per row (for the whole TV, not per profile) ---------------------------------------

    /** Tiles per row and dock slots: chosen in Controls, else res/values/config.xml's columns. */
    static int columns(Context ctx) {
        int def = ctx.getResources().getInteger(R.integer.columns);
        return Math.max(3, Math.min(7, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("columns", def)));
    }

    static void setColumns(Context ctx, int n) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("columns", n).apply();
    }

    // ---- profiles --------------------------------------------------------------------------------

    List<String> profileIds() {
        String saved = prefs.getString(KEY_PROFILES, null);
        if (saved == null || saved.isEmpty()) return Collections.singletonList(FIRST_PROFILE);
        return Arrays.asList(TextUtils.split(saved, "\n"));
    }

    String profileName(String id) {
        String name = prefs.getString("name." + id, null);
        if (name != null) return name;
        return ctx.getString(R.string.profile_n, profileIds().indexOf(id) + 1);
    }

    String currentProfile() {
        return profile;
    }

    boolean hasSeveralProfiles() {
        return profileIds().size() > 1;
    }

    void switchTo(String id) {
        if (!profileIds().contains(id)) return;
        profile = id;
        prefs.edit().putString(KEY_CURRENT, id).apply();
        load();
    }

    /** A new profile with the default arrangement; returns its id. */
    String addProfile() {
        List<String> ids = new ArrayList<>(profileIds());
        int n = 1;
        while (ids.contains("p" + n)) n++;
        String id = "p" + n;
        ids.add(id);
        prefs.edit().putString(KEY_PROFILES, TextUtils.join("\n", ids)).apply();
        return id;
    }

    void renameProfile(String id, String name) {
        prefs.edit().putString("name." + id, name).apply();
    }

    /** Deletes a profile and its arrangement; the last one can't be deleted. */
    void deleteProfile(String id) {
        List<String> ids = new ArrayList<>(profileIds());
        if (ids.size() < 2 || !ids.remove(id)) return;
        prefs.edit()
                .putString(KEY_PROFILES, TextUtils.join("\n", ids))
                .remove("name." + id).remove("order." + id).remove("hidden." + id).remove("folders." + id)
                .remove("shown.#upnext." + id)   // from the old Up Next tile
                .apply();
        if (id.equals(profile)) switchTo(ids.get(0));
    }

    boolean askOnWake() {
        return prefs.getBoolean(KEY_ASK_ON_WAKE, false);
    }

    void setAskOnWake(boolean ask) {
        prefs.edit().putBoolean(KEY_ASK_ON_WAKE, ask).apply();
    }

    // ---- the current profile's arrangement -------------------------------------------------------

    private void load() {
        String saved = prefs.getString("order." + profile, null);
        Set<String> h = prefs.getStringSet("hidden." + profile, null);
        if (saved == null && FIRST_PROFILE.equals(profile)) {
            // Arrangements saved before profiles existed belong to the first profile.
            saved = prefs.getString("order", null);
            h = prefs.getStringSet("hidden", null);
        }
        order = saved == null ? null : new ArrayList<>(Arrays.asList(TextUtils.split(saved, "\n")));
        hidden = h == null ? null : new HashSet<>(h);
        folders.clear();
        String f = prefs.getString("folders." + profile, "");
        for (String line : TextUtils.split(f, "\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) continue;
            List<String> members = new ArrayList<>(Arrays.asList(TextUtils.split(parts[2], ",")));
            folders.add(new Folder(parts[0], parts[1], members));
        }
    }

    boolean hasCustomOrder() {
        return order != null;
    }

    /** Position in the saved order, or -1 for a tile the user hasn't placed (new, or no saved order). */
    int indexOf(String key) {
        return order == null ? -1 : order.indexOf(key);
    }

    boolean isHidden(String key, String pkg) {
        return hidden != null ? hidden.contains(key) : defaultHiddenPkgs.contains(pkg);
    }

    /** A copy of this profile's folders, for the adapter to edit and pass back to {@link #save}. */
    List<Folder> folders() {
        List<Folder> copy = new ArrayList<>();
        for (Folder f : folders) copy.add(new Folder(f.id, f.name, new ArrayList<>(f.members)));
        return copy;
    }

    String newFolderId(List<Folder> existing) {
        int n = 1;
        outer:
        while (true) {
            for (Folder f : existing) {
                if (f.id.equals("f" + n)) {
                    n++;
                    continue outer;
                }
            }
            return "f" + n;
        }
    }

    /**
     * Saves the arrangement: the visible tiles in order, then the hidden ones (so an app that is unhidden later
     * comes back at the end, like a newly installed one), plus the folders and their contents.
     */
    void save(List<String> visibleOrder, Set<String> hiddenKeys, List<Folder> newFolders) {
        List<String> all = new ArrayList<>(visibleOrder);
        for (String k : hiddenKeys) if (!all.contains(k)) all.add(k);
        order = all;
        hidden = new HashSet<>(hiddenKeys);
        folders.clear();
        List<String> lines = new ArrayList<>();
        for (Folder f : newFolders) {
            if (f.members.isEmpty()) continue;
            folders.add(new Folder(f.id, f.name, new ArrayList<>(f.members)));
            // Names are typed on the TV: keep them to one line without the separators used here.
            String name = f.name.replace('\t', ' ').replace('\n', ' ');
            lines.add(f.id + "\t" + name + "\t" + TextUtils.join(",", f.members));
        }
        prefs.edit()
                .putString("order." + profile, TextUtils.join("\n", all))
                .putStringSet("hidden." + profile, Collections.unmodifiableSet(new HashSet<>(hiddenKeys)))
                .putString("folders." + profile, TextUtils.join("\n", lines))
                .apply();
    }
}
