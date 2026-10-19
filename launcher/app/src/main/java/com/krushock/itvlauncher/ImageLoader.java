package com.krushock.itvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads Top Shelf artwork: content://, android.resource:// and https:// images, decoded at card size in RGB_565.
 * A small memory cache plus a disk cache (capped, in the app's cache folder) keep a 1 GB TV from downloading the
 * same posters again. https only: plain-http artwork is skipped.
 */
final class ImageLoader {

    private static final int MAX_BYTES = 4 * 1024 * 1024;        // refuse anything bigger than 4 MB
    private static final long DISK_CAP = 40L * 1024 * 1024;       // trim the disk cache above 40 MB

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final File dir;

    ImageLoader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /**
     * Shows {@code uri} in {@code view} when it's ready; a recycled view that moved on to another uri is left alone.
     * {@code loaded} hears true once the image is on screen. A failure (offline, timeout, bad file) isn't
     * remembered, so the next bind tries again.
     */
    void load(String uri, ImageView view, int w, int h, java.util.function.Consumer<Boolean> loaded) {
        view.setTag(R.id.banner, uri);
        if (uri == null || uri.isEmpty()) {
            view.setImageDrawable(null);
            loaded.accept(false);
            return;
        }
        Bitmap cached = memory.get(uri);
        if (cached != null) {
            view.setImageBitmap(cached);
            loaded.accept(true);
            return;
        }
        view.setImageDrawable(null);
        loaded.accept(false);
        pool.execute(() -> {
            Bitmap bmp = fetch(uri, w, h);
            if (bmp != null) memory.put(uri, bmp);
            main.post(() -> {
                if (bmp != null && uri.equals(view.getTag(R.id.banner))) {
                    view.setImageBitmap(bmp);
                    loaded.accept(true);
                }
            });
        });
    }

    /**
     * The image at {@code uri}, at once when cached or after loading, for callers that keep showing the old picture
     * until the new one is ready (Now Playing). Touches no view; {@code done} hears null when it can't be had.
     */
    void get(String uri, int w, int h, java.util.function.Consumer<Bitmap> done) {
        Bitmap cached = memory.get(uri);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = fetch(uri, w, h);
            if (bmp != null) memory.put(uri, bmp);
            main.post(() -> done.accept(bmp));
        });
    }

    /** Whether there's a network to download from; offline, only cached and local artwork is tried. */
    private boolean online() {
        android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network n = cm != null ? cm.getActiveNetwork() : null;
        android.net.NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private Bitmap fetch(String uri, int w, int h) {
        try {
            byte[] data = readDisk(uri);
            if (data == null) {
                data = readSource(uri);
                if (data == null) return null;
                writeDisk(uri, data);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private byte[] readSource(String uri) throws Exception {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("https")) {
            if (!online()) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                c.disconnect();
            }
        }
        if (scheme.equals("content") || scheme.equals("android.resource") || scheme.equals("file")) {
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                return in == null ? null : readAll(in);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }

    private File fileFor(String uri) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(uri.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return new File(dir, sb.toString());
    }

    private byte[] readDisk(String uri) {
        try {
            File f = fileFor(uri);
            if (!f.exists()) return null;
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            try (InputStream in = new FileInputStream(f)) {
                return readAll(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void writeDisk(String uri, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(fileFor(uri))) {
            out.write(data);
        } catch (Exception ignored) {
        }
        trimDisk();
    }

    /** Drops the least recently used files once the cache passes its cap. */
    private void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_CAP) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_CAP * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
