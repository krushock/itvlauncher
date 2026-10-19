package com.krushock.itvlauncher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiles: apps and folders. The first {@link MainActivity#COLUMNS} positions are the dock, the rest is the grid, in
 * the order the user arranged them ({@link HomeLayout}). A "Hidden apps" tile closes the grid while any app is hidden.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Every key pressed on a tile (key-down events, including repeats). Return true when handled. */
        boolean onTileKey(int position, KeyEvent event);

        /** OK held on an app or folder tile. */
        void onTileLongPressed(int position, View card);

        void onFolderClicked(int position, View card);

        void onHiddenTileClicked(View card);
    }

    /** Key of the "Hidden apps" tile, so focus can return to it like to any app. */
    static final String HIDDEN_TILE_KEY = "#hidden";

    static final class Entry {
        final String label;
        /** The app's activity; null for folders and the "Hidden apps" tile. */
        final ComponentName component;
        final String pkg;
        final String key;
        final Drawable banner;
        final Drawable icon;
        /** For a folder: its apps, in order. */
        final List<Entry> members;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(label, component, component != null ? component.flattenToShortString() : HIDDEN_TILE_KEY,
                    banner, icon, null);
        }

        private Entry(String label, ComponentName component, String key, Drawable banner, Drawable icon,
                      List<Entry> members) {
            this.label = label;
            this.component = component;
            this.pkg = component != null ? component.getPackageName() : null;
            this.key = key;
            this.banner = banner;
            this.icon = icon;
            this.members = members;
        }

        boolean isApp() {
            return component != null;
        }

        boolean isFolder() {
            return members != null;
        }

        boolean isHiddenTile() {
            return HIDDEN_TILE_KEY.equals(key);
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    /** Tile size for the current tiles per row: 180×101 dp at 4, smaller at 5 and 6 so a row still fits. */
    static int cardW, cardH;
    static float cornerRadius;

    static void computeSize(Context ctx, int columns) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float d = dm.density;
        float cell = (dm.widthPixels - 2 * 56 * d) / columns;   // the grid sits inside 56 dp margins
        cardW = (int) Math.min(180 * d, cell * 0.85f);
        cardH = Math.round(cardW * 101f / 180f);
        cornerRadius = 16 * d * cardW / (180 * d);
    }

    /** Height of one grid row: padding, the tile, the gap and the name underneath. */
    static int itemHeight(float density) {
        return cardH + (int) (45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Default dock order (res/values/config.xml, dock_apps), until the user arranges the tiles. */
    private final List<String> favourites;
    private final HomeLayout layout;
    /** Visible tiles (apps and folders) in order; the "Hidden apps" tile, when shown, is not in here. */
    private final List<Entry> items = new ArrayList<>();
    /** Installed apps the user has hidden, alphabetical. */
    private final List<Entry> hiddenApps = new ArrayList<>();
    /** This profile's folders as stored, including apps that are not installed right now. */
    private List<HomeLayout.Folder> folders = new ArrayList<>();
    private Entry hiddenTile;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.layout = new HomeLayout(ctx);
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    HomeLayout layout() {
        return layout;
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Entry> installed = new HashMap<>();
        Set<String> seenPkgs = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            // One tile per app. Some apps list the same screen twice (Sony's Timers & Clock does): keep the entry
            // Android itself would open for the app, or the first one.
            if (!seenPkgs.add(pkg)) continue;
            Intent main = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (main != null && main.getComponent() != null
                    && !main.getComponent().getClassName().equals(ri.activityInfo.name)) {
                for (ResolveInfo other : found) {
                    if (other.activityInfo != null && other.activityInfo.packageName.equals(pkg)
                            && other.activityInfo.name.equals(main.getComponent().getClassName())) {
                        ri = other;
                        break;
                    }
                }
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            Entry e = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            installed.put(e.key, e);
        }

        List<Entry> fresh = new ArrayList<>();
        List<Entry> hiddenFresh = new ArrayList<>();
        Set<String> inFolders = new HashSet<>();
        folders = layout.folders();
        for (HomeLayout.Folder f : folders) {
            List<Entry> members = new ArrayList<>();
            for (String k : f.members) {
                Entry app = installed.get(k);
                inFolders.add(k);
                if (app != null && !layout.isHidden(k, app.pkg)) members.add(app);
            }
            // A folder whose apps are all uninstalled stays stored, but has nothing to show.
            if (!members.isEmpty()) fresh.add(folderEntry(f, members));
        }
        for (Entry e : installed.values()) {
            if (layout.isHidden(e.key, e.pkg)) hiddenFresh.add(e);
            else if (!inFolders.contains(e.key)) fresh.add(e);
        }
        if (layout.hasCustomOrder()) {
            // The user's order; tiles they haven't placed yet (new installs, unhidden apps) go at the end.
            Collections.sort(fresh, (a, b) -> {
                int ia = layout.indexOf(a.key), ib = layout.indexOf(b.key);
                if (ia < 0 || ib < 0) {
                    if (ia >= 0) return -1;
                    if (ib >= 0) return 1;
                    return a.label.compareToIgnoreCase(b.label);
                }
                return Integer.compare(ia, ib);
            });
        } else {
            // Defaults: Up Next first (where Apple TV puts its TV app), then the dock_apps, the rest A–Z.
            Collections.sort(fresh, (a, b) -> {
                int ra = defaultRank(a), rb = defaultRank(b);
                if (ra != rb) return Integer.compare(ra, rb);
                return a.label.compareToIgnoreCase(b.label);
            });
        }
        Collections.sort(hiddenFresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
        items.clear();
        items.addAll(fresh);
        hiddenApps.clear();
        hiddenApps.addAll(hiddenFresh);
        hiddenTile = hiddenApps.isEmpty() ? null
                : new Entry(ctx.getString(R.string.hidden_apps), null, null, ctx.getDrawable(R.drawable.ic_hidden));
        notifyDataSetChanged();
    }

    private int defaultRank(Entry e) {
        int f = e.pkg != null ? favourites.indexOf(e.pkg) : -1;
        return f >= 0 ? f : Integer.MAX_VALUE - 1;
    }

    /** A folder tile: its first four apps' artwork in a 2×2 grid, Apple TV style. */
    private Entry folderEntry(HomeLayout.Folder f, List<Entry> members) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = (int) (180 * d), h = (int) (101 * d), gap = (int) (6 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(ctx.getColor(R.color.folder_bg));
        int cw = (w - 3 * gap) / 2, ch = (h - 3 * gap) / 2;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(ctx.getColor(R.color.folder_cell));
        for (int i = 0; i < Math.min(4, members.size()); i++) {
            Entry m = members.get(i);
            int x = gap + (i % 2) * (cw + gap), y = gap + (i / 2) * (ch + gap);
            RectF cell = new RectF(x, y, x + cw, y + ch);
            float r = 6 * d;
            c.save();
            Path clip = new Path();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(cell, bg);
            Drawable art = m.banner != null ? m.banner : m.icon;
            if (art != null) {
                Drawable copy = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
                Rect saved = new Rect(copy.getBounds());
                if (m.banner != null) {
                    copy.setBounds((int) cell.left, (int) cell.top, (int) cell.right, (int) cell.bottom);
                } else {
                    int s = (int) (ch * 0.7f);
                    int cx = (int) cell.centerX(), cy = (int) cell.centerY();
                    copy.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
                }
                copy.draw(c);
                copy.setBounds(saved);
            }
            c.restore();
        }
        BitmapDrawable banner = new BitmapDrawable(ctx.getResources(), bmp);
        return new Entry(f.name, null, f.key(), banner, ctx.getDrawable(R.drawable.ic_folder), members);
    }

    // ---- arranging -------------------------------------------------------------------------------

    /** Number of app and folder tiles, not counting the "Hidden apps" tile. */
    int appCount() {
        return items.size();
    }

    boolean isHiddenTile(int position) {
        return hiddenTile != null && position == items.size();
    }

    Entry entry(int position) {
        return entryAt(position);
    }

    List<Entry> hiddenApps() {
        return Collections.unmodifiableList(hiddenApps);
    }

    /** Folders to offer in "Move to folder", in grid order. */
    List<Entry> folderEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : items) if (e.isFolder()) out.add(e);
        return out;
    }

    /** Current order, to undo a move with {@link #restoreOrder}. */
    List<String> orderSnapshot() {
        List<String> keys = new ArrayList<>();
        for (Entry e : items) keys.add(e.key);
        return keys;
    }

    void restoreOrder(List<String> keys) {
        Collections.sort(items, (a, b) -> Integer.compare(keys.indexOf(a.key), keys.indexOf(b.key)));
        notifyDataSetChanged();
    }

    /** Moves a tile; the tile's view (and focus) moves with it. Not saved until {@link #save}. */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // Rebind the shifted tiles: whether a tile shows its name depends on being in the dock or the grid.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
    }

    /** Into the last dock slot; the tile there moves to the front of the grid. */
    void moveToDock(int position) {
        move(position, Math.min(dockSize, items.size()) - 1);
        save();
    }

    /** To the front of the grid; the first grid tile moves up into the dock. */
    void removeFromDock(int position) {
        move(position, Math.min(dockSize, items.size() - 1));
        save();
    }

    void hide(int position) {
        if (position < 0 || position >= items.size() || items.get(position).isFolder()) return;
        hiddenApps.add(items.remove(position));
        save();
        reload();
    }

    void unhide(String key) {
        for (Entry e : hiddenApps) {
            if (!e.key.equals(key)) continue;
            hiddenApps.remove(e);
            items.add(e);   // back at the end, like a newly installed app
            break;
        }
        save();
        reload();
    }

    /**
     * Puts the app at {@code position} into a folder: an existing one ({@code folderKey}), or a new one named
     * {@code newName} that takes the app's place. Returns the folder's key.
     */
    String addToFolder(int position, String folderKey, String newName) {
        if (position < 0 || position >= items.size() || !items.get(position).isApp()) return null;
        Entry app = items.get(position);
        HomeLayout.Folder target = null;
        for (HomeLayout.Folder f : folders) if (f.key().equals(folderKey)) target = f;
        if (target == null) {
            target = new HomeLayout.Folder(layout.newFolderId(folders), newName, new ArrayList<>());
            folders.add(target);
            items.set(position, new Entry(newName, null, target.key(), null, null, new ArrayList<>()));
        } else {
            items.remove(position);
        }
        target.members.remove(app.key);
        target.members.add(app.key);
        save();
        reload();
        return target.key();
    }

    /** Takes an app out of its folder and puts it right after the folder; an emptied folder disappears. */
    void removeFromFolder(String folderKey, String appKey) {
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(folderKey)) at = i;
        HomeLayout.Folder folder = findFolder(folderKey);
        if (folder == null || at < 0) return;
        Entry app = null;
        for (Entry m : items.get(at).members) if (m.key.equals(appKey)) app = m;
        folder.members.remove(appKey);
        if (app != null) items.add(at + 1, app);
        if (folder.members.isEmpty()) items.remove(at);
        save();
        reload();
    }

    /** Dissolves a folder: its apps take its place in the grid. */
    void ungroup(String folderKey) {
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(folderKey)) at = i;
        HomeLayout.Folder folder = findFolder(folderKey);
        if (folder == null || at < 0) return;
        List<Entry> members = items.get(at).members;
        items.remove(at);
        items.addAll(at, members);
        folders.remove(folder);
        save();
        reload();
    }

    void renameFolder(String folderKey, String name) {
        HomeLayout.Folder folder = findFolder(folderKey);
        if (folder == null) return;
        folder.name = name;
        save();
        reload();
    }

    private HomeLayout.Folder findFolder(String key) {
        for (HomeLayout.Folder f : folders) if (f.key().equals(key)) return f;
        return null;
    }

    void save() {
        Set<String> hiddenKeys = new HashSet<>();
        for (Entry e : hiddenApps) hiddenKeys.add(e.key);
        layout.save(orderSnapshot(), hiddenKeys, folders);
    }

    // ---- binding ---------------------------------------------------------------------------------

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float d = parent.getResources().getDisplayMetrics().density;
        float k = cardW / (180 * d);   // 1 at four per row
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = cardW;
        lp.height = cardH;
        TextView name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nlp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nlp.width = cardW;
        nlp.topMargin = cardH + (int) (7 * d);
        View icon = v.findViewById(R.id.icon);
        ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
        ilp.width = ilp.height = (int) (48 * d * k);
        ilp.bottomMargin = (int) (14 * d * k);
        TextView label = v.findViewById(R.id.label);
        label.setTextSize(Math.max(10f, 13f * k));
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = entryAt(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        bindArt(e, h.banner, h.icon, h.label);
        if (e.isHiddenTile()) {
            h.card.setOnClickListener(v -> listener.onHiddenTileClicked(v));
            h.card.setOnLongClickListener(v -> true);
            return;
        }
        h.card.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (e.isFolder()) listener.onFolderClicked(pos, v);
            else launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onTileLongPressed(pos, v);
            return true;
        });
    }

    /** A tile's artwork: the banner when there is one, otherwise the icon over the name. */
    static void bindArt(Entry e, ImageView banner, ImageView icon, TextView label) {
        if (e.banner != null) {
            banner.setImageDrawable(e.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            label.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            label.setText(e.label);
            label.setVisibility(View.VISIBLE);
        }
    }

    /** The system's App info screen for an app. */
    void openAppInfo(Entry e) {
        if (e == null || !e.isApp()) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private Entry entryAt(int position) {
        if (position >= 0 && position < items.size()) return items.get(position);
        return isHiddenTile(position) ? hiddenTile : null;
    }

    void launch(Entry e) {
        Recents.record(ctx, e.pkg);
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (hiddenTile != null ? 1 : 0);
    }

    @Override
    public long getItemId(int position) {
        return entryAt(position).key.hashCode();
    }

    /** Stable key for the tile at a position (its component, or folder:id), or null. */
    String keyAt(int position) {
        Entry e = entryAt(position);
        return e != null ? e.key : null;
    }

    /** Position of the tile with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < getItemCount(); i++) {
            if (entryAt(i).key.equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        Entry e = entryAt(position);
        return e != null ? e.label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        Entry e = entryAt(position);
        if (e == null) return 0xFF444444;
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            roundCorners(card, cornerRadius);
            // Room for the focus tilt: without depth the rotation looks flat.
            card.setCameraDistance(8000 * density);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileKey(pos, event);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }

    static void roundCorners(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }
}
