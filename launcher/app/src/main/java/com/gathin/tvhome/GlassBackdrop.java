package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * The wallpaper as the glass tiles see it (glass_tiles): a small, blurred copy and how it maps onto the screen, so
 * each tile can paint the part of the picture that lies behind it. One copy for every tile, made once per wallpaper.
 */
final class GlassBackdrop {

    /** The blurred copy is the wallpaper at this fraction of its width: the blur hides the lost detail. */
    private static final float SCALE = 1f / 6f;
    private static Bitmap blurred;
    /** The screen the wallpaper covers (centre crop), in px. */
    private static int screenW, screenH;
    private static Runnable listener;

    private GlassBackdrop() {
    }

    /** Called with the decoded software bitmap before it moves to the GPU; returns the blurred copy, off the UI thread. */
    static Bitmap blur(Bitmap source, int radius) {
        if (source == null) return null;
        int w = Math.max(16, Math.round(source.getWidth() * SCALE));
        int h = Math.max(9, Math.round(source.getHeight() * SCALE));
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(small).drawBitmap(source, null, new Rect(0, 0, w, h), filter);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        // Box blur, radius in pixels of the small copy; three passes approach a Gaussian. 0 keeps it as it is.
        for (int pass = 0; pass < 3 && radius > 0; pass++) {
            boxBlur(px, tmp, w, h, radius, true);
            boxBlur(tmp, px, w, h, radius, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
    }

    /** Puts a blurred copy (from {@link #blur}) behind the tiles for a wallpaper shown on a screen of this size. */
    static void set(Bitmap blurredCopy, int width, int height) {
        blurred = blurredCopy;
        screenW = width;
        screenH = height;
        if (listener != null) listener.run();
    }

    /** Runs when the backdrop changes, so the tiles redraw with the new picture. */
    static void setListener(Runnable r) {
        listener = r;
    }

    /** The blurred picture, or null when there is no wallpaper (the tiles then show only their tint). */
    static Bitmap bitmap() {
        return blurred;
    }

    /**
     * The part of the blurred copy behind a screen rectangle, magnified about its centre by {@code lens} (1.1 shows
     * 10% less, larger, as a convex glass does).
     */
    static boolean source(RectF screen, float lens, Rect out) {
        Bitmap b = blurred;
        if (b == null || screenW <= 0 || screenH <= 0) return false;
        // Centre crop, as the wallpaper's ImageView lays it out.
        float scale = Math.max(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
        float dx = (screenW - b.getWidth() * scale) / 2f, dy = (screenH - b.getHeight() * scale) / 2f;
        float cx = screen.centerX(), cy = screen.centerY();
        float hw = screen.width() / 2f / lens, hh = screen.height() / 2f / lens;
        out.set(Math.round((cx - hw - dx) / scale), Math.round((cy - hh - dy) / scale),
                Math.round((cx + hw - dx) / scale), Math.round((cy + hh - dy) / scale));
        return true;
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int ra = 0, ga = 0, ba = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[index(Math.max(0, Math.min(len - 1, i)), line, w, horizontal)];
                ra += (p >> 16) & 0xFF;
                ga += (p >> 8) & 0xFF;
                ba += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(i, line, w, horizontal)] = 0xFF000000 | (ra / div) << 16 | (ga / div) << 8 | (ba / div);
                int add = in[index(Math.min(len - 1, i + r + 1), line, w, horizontal)];
                int sub = in[index(Math.max(0, i - r), line, w, horizontal)];
                ra += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                ga += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                ba += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int index(int i, int line, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
