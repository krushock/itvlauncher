package com.krushock.itvlauncher;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV's Top Shelf, Android TV style: when a dock app is focused, a row of what that app publishes to the
 * system's TV provider, its Continue watching (Watch Next) items first, then its preview programs. Reading other
 * apps' rows needs READ_TV_LISTINGS (granted at install); without it, or for apps that publish nothing, the
 * shelf simply stays empty.
 */
final class TopShelf {

    interface Host {
        /** Down or Back on the shelf: back to the dock tile it belongs to. */
        void onShelfExit();

        /** Up on the shelf: to the top bar. Android's focus search doesn't leave a horizontal row upwards. */
        void onShelfUp(View card);

        /** A card got focus: show its artwork full screen behind everything (and maybe take over the screen). */
        void onCardFocused(Item item, int index);
    }

    static final class Item {
        /** Card caption: "Show · Episode". */
        String title;
        /** The show or film alone, its episode, and the app's description, for the full-screen shelf. */
        String name, episode, description;
        long positionMs, durationMs;
        String art;
        String intentUri;
        String pkg;
        float progress;   // 0..1 for Continue watching, 0 otherwise
        long when;        // last engagement, for ordering Continue watching
        int weight;       // preview program order within the app
    }

    private static final long CACHE_MS = 60_000L;
    private static final int MAX_ITEMS = 12;

    private final Activity activity;
    private final RecyclerView view;
    private final Host host;
    private final ImageLoader images;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Item>> cache = new HashMap<>();
    private final Map<String, Long> cachedAt = new HashMap<>();
    private final ShelfAdapter adapter = new ShelfAdapter();
    private final float density;
    private String wanted;   // package the shelf should show now
    private boolean shown;
    private float targetY;
    /** Card focused last in this app's shelf, so coming back down from the top bar lands on it. */
    private int lastIndex;

    TopShelf(Activity activity, RecyclerView view, Host host) {
        this.activity = activity;
        this.view = view;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        images = new ImageLoader(activity);
        view.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        view.setItemAnimator(null);
        view.setAdapter(adapter);
    }

    ImageLoader images() {
        return images;
    }

    /** Total height the shelf takes above the dock. */
    int height() {
        return view.getLayoutParams().height;
    }

    boolean hasItems() {
        return shown && adapter.items.size() > 0;
    }

    /**
     * Shows the shelf for {@code pkg} with its bottom at {@code bottomY} (root coordinates), or hides it for null.
     * {@code onChange} hears whether the shelf ended up visible, once its rows are known.
     */
    void showFor(String pkg, float bottomY, java.util.function.Consumer<Boolean> onChange) {
        wanted = pkg;
        targetY = bottomY - height();
        if (shown) view.animate().y(targetY).setDuration(200).start();
        if (pkg == null) {
            hide();
            onChange.accept(false);
            return;
        }
        Long at = cachedAt.get(pkg);
        if (at != null && SystemClock.uptimeMillis() - at < CACHE_MS) {
            apply(pkg, cache.get(pkg), onChange);
            return;
        }
        worker.execute(() -> {
            List<Item> rows = query(pkg);
            main.post(() -> {
                cache.put(pkg, rows);
                cachedAt.put(pkg, SystemClock.uptimeMillis());
                if (pkg.equals(wanted)) apply(pkg, rows, onChange);
            });
        });
    }

    /** Forget cached rows, e.g. when coming back from an app that may have changed its Continue watching. */
    void invalidate() {
        cache.clear();
        cachedAt.clear();
    }

    /** The network is back: retry artwork that couldn't load while offline. */
    void onOnline() {
        if (shown) adapter.notifyDataSetChanged();
    }

    private void apply(String pkg, List<Item> rows, java.util.function.Consumer<Boolean> onChange) {
        if (rows == null || rows.isEmpty()) {
            hide();
            onChange.accept(false);
            return;
        }
        boolean samePkg = shown && pkg.equals(adapter.pkg);
        adapter.set(pkg, rows);
        if (!samePkg) {
            view.scrollToPosition(0);
            lastIndex = 0;
        }
        if (!shown) {
            shown = true;
            view.setVisibility(View.VISIBLE);
            view.setY(targetY + (Look.reduceMotion(activity) ? 0 : 12 * density));   // rise into place
            view.animate().alpha(1f).y(targetY).setDuration(260).start();
        }
        onChange.accept(true);
    }

    void hide() {
        if (!shown) return;
        shown = false;
        view.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!shown) view.setVisibility(View.GONE);
        }).start();
    }

    /** The cards shown now, for the full-screen shelf to page through. */
    List<Item> items() {
        return new ArrayList<>(adapter.items);
    }

    /** The card view at {@code index}, or null when it isn't laid out. */
    View cardAt(int index) {
        RecyclerView.ViewHolder vh = view.findViewHolderForAdapterPosition(index);
        return vh != null ? vh.itemView.findViewById(R.id.shelfCard) : null;
    }

    /** Back from the full-screen shelf: onto the card it ended on. */
    void focusIndex(int index) {
        lastIndex = Math.max(0, Math.min(index, adapter.getItemCount() - 1));
        focusAt(lastIndex);
    }

    /** Into the shelf, from the dock or the top bar: onto the card you were on (the first, for a new app). */
    void focusLast() {
        focusAt(Math.min(lastIndex, adapter.getItemCount() - 1));
    }

    private void focusAt(int index) {
        RecyclerView.ViewHolder vh = view.findViewHolderForAdapterPosition(index);
        if (vh != null) {
            vh.itemView.findViewById(R.id.shelfCard).requestFocus();
            return;
        }
        // Scrolled out of view: bring it back, then focus it once it's laid out.
        view.scrollToPosition(index);
        view.post(() -> {
            RecyclerView.ViewHolder h = view.findViewHolderForAdapterPosition(index);
            if (h != null) h.itemView.findViewById(R.id.shelfCard).requestFocus();
        });
    }

    // ---- reading the TV provider -----------------------------------------------------------------

    private List<Item> query(String pkg) {
        List<Item> cont = new ArrayList<>();
        List<Item> previews = new ArrayList<>();
        ContentResolver cr = activity.getContentResolver();
        // Selections aren't allowed for non-system callers, so read everything visible and filter here.
        read(cr, TvContract.WatchNextPrograms.CONTENT_URI, pkg, cont, true);
        read(cr, TvContract.PreviewPrograms.CONTENT_URI, pkg, previews, false);
        Collections.sort(cont, (a, b) -> Long.compare(b.when, a.when));
        Collections.sort(previews, (a, b) -> Integer.compare(b.weight, a.weight));
        List<Item> out = new ArrayList<>(cont);
        for (Item p : previews) {
            if (out.size() >= MAX_ITEMS) break;
            boolean dup = false;
            for (Item c : cont) if (c.title != null && c.title.equals(p.title)) dup = true;
            if (!dup) out.add(p);
        }
        return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
    }

    private static void read(ContentResolver cr, Uri uri, String pkg, List<Item> out, boolean watchNext) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name");
            int iTitle = c.getColumnIndex("title");
            int iEpisode = c.getColumnIndex("episode_title");
            int iThumb = c.getColumnIndex("thumbnail_uri");
            int iPoster = c.getColumnIndex("poster_art_uri");
            int iIntent = c.getColumnIndex("intent_uri");
            int iBrowsable = c.getColumnIndex("browsable");
            int iPos = c.getColumnIndex("last_playback_position_millis");
            int iDur = c.getColumnIndex("duration_millis");
            int iWhen = c.getColumnIndex("last_engagement_time_utc_millis");
            int iWeight = c.getColumnIndex("weight");
            int iDesc = c.getColumnIndex("short_description");
            while (c.moveToNext()) {
                String rowPkg = iPkg >= 0 ? c.getString(iPkg) : null;
                if (rowPkg == null || !rowPkg.equals(pkg)) continue;
                if (iBrowsable >= 0 && c.getInt(iBrowsable) == 0) continue;
                Item it = new Item();
                it.pkg = rowPkg;
                String title = iTitle >= 0 ? c.getString(iTitle) : null;
                String episode = iEpisode >= 0 ? c.getString(iEpisode) : null;
                it.title = episode != null && !episode.isEmpty() && title != null ? title + " · " + episode
                        : title != null ? title : episode;
                it.name = title != null ? title : episode;
                it.episode = title != null && episode != null && !episode.isEmpty() ? episode : null;
                it.description = iDesc >= 0 ? c.getString(iDesc) : null;
                if (iPos >= 0) it.positionMs = c.getLong(iPos);
                if (iDur >= 0) it.durationMs = c.getLong(iDur);
                String thumb = iThumb >= 0 ? c.getString(iThumb) : null;
                String poster = iPoster >= 0 ? c.getString(iPoster) : null;
                it.art = thumb != null && !thumb.isEmpty() ? thumb : poster;   // cards are 16:9, thumbnails fit best
                it.intentUri = iIntent >= 0 ? c.getString(iIntent) : null;
                if (watchNext && iPos >= 0 && iDur >= 0 && c.getLong(iDur) > 0) {
                    it.progress = Math.min(1f, (float) c.getLong(iPos) / c.getLong(iDur));
                }
                it.when = iWhen >= 0 ? c.getLong(iWhen) : 0;
                it.weight = iWeight >= 0 ? c.getInt(iWeight) : 0;
                out.add(it);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // No READ_TV_LISTINGS (run setup again) or a TV without the TV provider.
        }
    }

    void open(Item it) {
        Recents.record(activity, it.pkg);
        Intent i = null;
        try {
            if (it.intentUri != null) i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (Exception ignored) {
        }
        if (i == null) i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- cards -----------------------------------------------------------------------------------

    private final class ShelfAdapter extends RecyclerView.Adapter<CardHolder> {
        final List<Item> items = new ArrayList<>();
        String pkg;

        void set(String pkg, List<Item> rows) {
            this.pkg = pkg;
            items.clear();
            items.addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shelf, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Item it = items.get(position);
            h.title.setText(it.title);
            h.fallback.setText(it.title);
            images.load(it.art, h.art, (int) (200 * density), (int) (112 * density),
                    ok -> h.fallback.setVisibility(ok ? View.INVISIBLE : View.VISIBLE));
            boolean progress = it.progress > 0.02f;
            h.track.setVisibility(progress ? View.VISIBLE : View.GONE);
            h.fill.setVisibility(progress ? View.VISIBLE : View.GONE);
            if (progress) {
                ViewGroup.LayoutParams lp = h.fill.getLayoutParams();
                lp.width = (int) (200 * density * it.progress);
                h.fill.setLayoutParams(lp);
            }
            h.card.setOnClickListener(v -> open(it));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final View card, track, fill;
        final ImageView art;
        final TextView title, fallback;

        CardHolder(View v) {
            super(v);
            card = v.findViewById(R.id.shelfCard);
            art = v.findViewById(R.id.shelfArt);
            track = v.findViewById(R.id.shelfTrack);
            fill = v.findViewById(R.id.shelfProgress);
            title = v.findViewById(R.id.shelfTitle);
            fallback = v.findViewById(R.id.shelfFallback);
            AppAdapter.roundCorners(card, 12 * density);
            card.setOnFocusChangeListener((x, focused) -> {
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) {
                    lastIndex = pos;
                    host.onCardFocused(adapter.items.get(pos), pos);
                }
                x.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(200).start();
                title.setTextColor(activity.getColor(focused ? R.color.text_primary : R.color.text_secondary));
            });
            card.setOnKeyListener((x, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        host.onShelfExit();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        host.onShelfUp(x);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;                                   // the row's ends hold focus
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == adapter.getItemCount() - 1;
                    default:
                        return false;
                }
            });
        }
    }
}
