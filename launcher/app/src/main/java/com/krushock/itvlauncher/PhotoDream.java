package com.krushock.itvlauncher;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: photos from any folder named "Screensaver" (on a USB drive or the TV's storage), shuffled, each
 * shown for a while with a slow Ken Burns drift and cross-faded into the next. Decoded at screen size in RGB_565
 * so two photos in memory stay around 8 MB on a 1 GB TV. No network.
 */
public class PhotoDream extends DreamService {

    private static final long SHOW_MS = 15_000L;
    private static final long FADE_MS = 1_600L;
    private static final String FOLDER = "screensaver";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final List<Uri> photos = new ArrayList<>();
    private ImageView front, back;
    private int next;
    private volatile boolean running;

    private final Runnable advance = this::showNext;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_photos);
        front = findViewById(R.id.photoA);
        back = findViewById(R.id.photoB);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        decoder.execute(() -> {
            List<Uri> found = findPhotos();
            handler.post(() -> {
                if (!running) return;
                photos.clear();
                photos.addAll(found);
                Collections.shuffle(photos, random);
                if (photos.isEmpty()) findViewById(R.id.photoEmpty).setVisibility(View.VISIBLE);
                else showNext();
            });
        });
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        handler.removeCallbacks(advance);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacks(advance);
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    /** Images whose folder is called "Screensaver", on every storage volume MediaStore knows. */
    private List<Uri> findPhotos() {
        List<Uri> out = new ArrayList<>();
        List<Uri> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Set<String> names = MediaStore.getExternalVolumeNames(this);
            for (String v : names) volumes.add(MediaStore.Images.Media.getContentUri(v));
        } else {
            volumes.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
        ContentResolver cr = getContentResolver();
        String[] cols = {MediaStore.Images.Media._ID};
        String where = "LOWER(" + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + ") = ?";
        for (Uri volume : volumes) {
            try (Cursor c = cr.query(volume, cols, where, new String[]{FOLDER}, null)) {
                if (c == null) continue;
                while (c.moveToNext()) out.add(Uri.withAppendedPath(volume, String.valueOf(c.getLong(0))));
            } catch (SecurityException | IllegalArgumentException ignored) {
                // No storage permission (granted by build-and-install) or the volume went away.
            }
        }
        return out;
    }

    private void showNext() {
        if (!running || photos.isEmpty()) return;
        Uri uri = photos.get(next % photos.size());
        next++;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        decoder.execute(() -> {
            Bitmap bmp = decode(uri, dm.widthPixels, dm.heightPixels);
            handler.post(() -> {
                if (!running) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {   // unreadable file: skip it
                    handler.postDelayed(advance, 200);
                    return;
                }
                crossFadeTo(bmp);
                handler.postDelayed(advance, SHOW_MS);
            });
        });
    }

    private void crossFadeTo(Bitmap bmp) {
        ImageView incoming = back, outgoing = front;
        incoming.setImageBitmap(bmp);
        // Ken Burns: a slow zoom from a random corner towards the middle (none with Reduce Motion).
        boolean still = Look.reduceMotion(this);
        float from = still ? 1f : 1.12f, to = still ? 1f : 1.02f;
        incoming.setScaleX(from);
        incoming.setScaleY(from);
        incoming.setTranslationX(still ? 0f : (random.nextFloat() - 0.5f) * incoming.getWidth() * 0.06f);
        incoming.setTranslationY(still ? 0f : (random.nextFloat() - 0.5f) * incoming.getHeight() * 0.06f);
        incoming.animate().scaleX(to).scaleY(to).translationX(0f).translationY(0f)
                .setDuration(SHOW_MS + FADE_MS).setInterpolator(new LinearInterpolator()).start();
        incoming.setAlpha(0f);
        incoming.bringToFront();
        reorderOverlays();   // the shade, clock and hint stay above the photos
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            outgoing.setAlpha(0f);
            outgoing.animate().cancel();
            Drawable old = outgoing.getDrawable();
            outgoing.setImageDrawable(null);
            if (old instanceof BitmapDrawable) {
                Bitmap ob = ((BitmapDrawable) old).getBitmap();
                if (ob != null && ob != bmp) ob.recycle();
            }
        }).start();
        front = incoming;
        back = outgoing;
    }

    /** Photos stay at the bottom of the stack; everything else (shade, clock, hint) on top. */
    private void reorderOverlays() {
        ViewGroup parent = (ViewGroup) front.getParent();
        List<View> overlays = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v != front && v != back) overlays.add(v);
        }
        for (View v : overlays) v.bringToFront();
    }

    /** Decodes at about screen size, upright (EXIF rotation), in RGB_565. */
    private Bitmap decode(Uri uri, int w, int h) {
        ContentResolver cr = getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;
            int degrees = 0;
            try (InputStream in = cr.openInputStream(uri)) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            } catch (Exception ignored) {
            }
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
