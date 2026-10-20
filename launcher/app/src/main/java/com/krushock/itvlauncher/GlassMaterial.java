package com.krushock.itvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * tvOS 26's frosted glass, for the menus: what's behind the panel, blurred, under a soft tint, with a light sheen
 * at the top and a thin bright rim that fades downwards. The backdrop is a small blurred copy of the screen under
 * the panel ({@link #setBackdrop}); until it arrives (or where the TV can't copy the screen) the panel is a solid,
 * slightly see-through tint, so text stays readable either way.
 */
final class GlassMaterial extends Drawable {

    private final float radius, rimWidth;
    private final boolean light;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();
    private Bitmap backdrop;
    private float scale = 1f;

    GlassMaterial(float density, boolean light) {
        this.light = light;
        radius = 30 * density;
        rimWidth = 1.2f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
        applyTint();
    }

    /** The blurred screen under the panel, {@code scale} times smaller than the panel. */
    void setBackdrop(Bitmap blurred, float scale) {
        backdrop = blurred;
        this.scale = scale;
        applyTint();
        invalidateSelf();
    }

    /** Over a backdrop the tint is light enough to see through; without one it has to carry the text alone. */
    private void applyTint() {
        if (light) tint.setColor(backdrop != null ? 0xB8F4F4F6 : 0xF2F4F4F6);
        else tint.setColor(backdrop != null ? 0x8A1C1C21 : 0xF01E1E22);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        box.set(b);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        float h = b.height();
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.45f,
                light ? 0x66FFFFFF : 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                light ? 0xFFFFFFFF : 0xB3FFFFFF, light ? 0x33FFFFFF : 0x1FFFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        if (backdrop != null) {
            canvas.save();
            canvas.translate(box.left, box.top);
            canvas.scale(scale, scale);
            canvas.drawBitmap(backdrop, 0, 0, image);
            canvas.restore();
        }
        canvas.drawRect(box, tint);
        canvas.drawRect(box, sheen);
        canvas.restore();
        float in = rimWidth / 2f;
        canvas.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, radius - in, radius - in, rim);
    }

    @Override
    public void setAlpha(int alpha) {
        image.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** A cheap blur for the small copy of the screen: three passes of a box blur, each way. */
    static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            int ra = 0, rr = 0, rg = 0, rb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[start + clamp(i, len) * step];
                ra += c >>> 24; rr += (c >> 16) & 0xff; rg += (c >> 8) & 0xff; rb += c & 0xff;
            }
            int n = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                out[start + i * step] = ((ra / n) << 24) | ((rr / n) << 16) | ((rg / n) << 8) | (rb / n);
                int add = in[start + clamp(i + r + 1, len) * step], sub = in[start + clamp(i - r, len) * step];
                ra += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                rg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                rb += (add & 0xff) - (sub & 0xff);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }
}
