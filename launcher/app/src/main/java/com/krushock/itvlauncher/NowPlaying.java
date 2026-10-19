package com.krushock.itvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/** Apple TV's Now Playing: the media session that's playing (or paused) right now, and its transport controls. */
final class NowPlaying {

    private NowPlaying() {
    }

    /** The session to show: a playing one first, else any with something to show; null without access or media. */
    static MediaController current(Context ctx) {
        MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
        if (msm == null) return null;
        List<MediaController> sessions;
        try {
            sessions = msm.getActiveSessions(new ComponentName(ctx, MediaWatcher.class));
        } catch (SecurityException e) {
            return null;   // the notification listener isn't enabled (setup switches it on)
        }
        MediaController fallback = null;
        for (MediaController c : sessions) {
            if (c.getMetadata() == null) continue;
            if (isPlaying(c)) return c;
            if (fallback == null) fallback = c;
        }
        return fallback;
    }

    /** Paused less than 10 minutes ago: still worth a Now Playing pill to resume from. */
    static boolean recentlyPaused(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PAUSED
                && android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime() < 10 * 60_000L;
    }

    /** The playing app's name, e.g. "Spotify". */
    static CharSequence appName(Context ctx, MediaController c) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0));
        } catch (Exception e) {
            return c.getPackageName();
        }
    }

    /** Back into the playing app: its own player screen when it says which, else its start screen. */
    static void open(Context ctx, MediaController c) {
        android.app.PendingIntent pi = c.getSessionActivity();
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (Exception ignored) {
                // gone: fall back to launching the app
            }
        }
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.Intent i = pm.getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = pm.getLaunchIntentForPackage(c.getPackageName());
        if (i != null) ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Playing, or between two moments of playing (buffering, skipping to the next track, seeking). */
    static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        if (s == null) return false;
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return true;
            default:
                return false;
        }
    }

    static String title(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return "";
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        return t != null ? t.toString() : "";
    }

    static String subtitle(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return "";
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        return t != null ? t.toString() : "";
    }

    /**
     * The best picture this update carries, ranked: artwork (3), album art (2), or the small display icon (1).
     * Spotify puts the album cover in only some of its updates and a different, smaller picture in the icon field
     * in the others.
     */
    private static Bitmap artBitmap(MediaMetadata m, int[] rank) {
        String[] names = {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON};
        for (int i = 0; i < names.length; i++) {
            Bitmap b = m.getBitmap(names[i]);
            if (b != null) {
                rank[0] = 3 - i;
                return b;
            }
        }
        rank[0] = 0;
        return null;
    }

    /** Which track an update is about: its cover's address when the app gives one, else title and artist. */
    private static String trackOf(MediaController c) {
        MediaMetadata m = c.getMetadata();
        String uri = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null && m != null) uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
        return c.getPackageName() + "|" + (uri != null ? uri : title(c) + "|" + subtitle(c));
    }

    /** What an artwork view is showing, so updates that don't improve on it leave it alone. */
    private static final class Shown {
        /** The cover address being shown (or loading), when the app gives one. */
        String uri;
        String track;
        int rank;
        int width;
        /** Apps that have sent a real cover (artwork or album art): their icon-only updates are ignored. */
        final java.util.Set<String> coverApps = new java.util.HashSet<>();
    }

    /** The cover's address, when it's one the image loader can fetch (Spotify gives an https link). */
    private static String coverUri(MediaMetadata m) {
        if (m == null) return null;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI}) {
            String u = m.getString(key);
            if (u != null && (u.startsWith("https://") || u.startsWith("content://"))) return u;
        }
        return null;
    }

    /**
     * Puts the playing track's cover in {@code view}. The cover's address is the reliable part of an update: apps
     * send several updates per button press, and the picture attached to them can be the previous track's (Spotify
     * names the new track before it has its cover) or a different, smaller icon. So the cover is loaded from its
     * address, and the old one stays up until it's ready. Apps that give no address get the attached picture, which
     * for one track only ever gets better, never swaps back.
     */
    static void showArt(Context ctx, ImageLoader images, ImageView view, MediaController c) {
        Shown shown = view.getTag() instanceof Shown ? (Shown) view.getTag() : new Shown();
        view.setTag(shown);
        String uri = coverUri(c.getMetadata());
        if (uri != null && images != null) {
            if (uri.equals(shown.uri)) return;   // this cover is on screen, or on its way
            shown.uri = uri;
            images.get(uri, 512, 512, bmp -> {
                if (!uri.equals(shown.uri)) return;   // a newer track took over meanwhile
                if (bmp != null) {
                    view.setImageDrawable(new BitmapDrawable(ctx.getResources(), bmp));
                    shown.track = trackOf(c);
                    shown.rank = 4;
                    shown.width = bmp.getWidth();
                } else {
                    shown.uri = null;   // offline or refused: tried again next update; meanwhile the attached one
                    showAttached(ctx, view, c, shown);
                }
            });
            return;
        }
        shown.uri = null;
        showAttached(ctx, view, c, shown);
    }

    private static void showAttached(Context ctx, ImageView view, MediaController c, Shown shown) {
        MediaMetadata m = c.getMetadata();
        int[] rank = {0};
        Bitmap b = m != null ? artBitmap(m, rank) : null;
        String pkg = c.getPackageName();
        if (rank[0] >= 2) shown.coverApps.add(pkg);
        else if (rank[0] == 1 && shown.coverApps.contains(pkg)) b = null;   // its icon isn't the cover: wait for it
        String track = trackOf(c);
        boolean sameTrack = track.equals(shown.track) && view.getDrawable() != null;
        if (b == null) {
            if (sameTrack || (shown.track != null && shown.track.startsWith(pkg + "|")
                    && view.getDrawable() != null && shown.rank > 0)) {
                return;   // keep the cover; the next track's arrives a moment after its title
            }
            try {
                view.setImageDrawable(ctx.getPackageManager().getApplicationIcon(pkg));
            } catch (Exception e) {
                view.setImageDrawable(null);
            }
            shown.track = track;
            shown.rank = 0;
            shown.width = 0;
            return;
        }
        if (sameTrack && (rank[0] < shown.rank || (rank[0] == shown.rank && b.getWidth() <= shown.width))) return;
        view.setImageDrawable(new BitmapDrawable(ctx.getResources(), b));
        shown.track = track;
        shown.rank = rank[0];
        shown.width = b.getWidth();
    }
}
