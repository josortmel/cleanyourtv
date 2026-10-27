package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * A glass tile (glass_tiles), after tvOS 26's Liquid Glass: the wallpaper behind the tile, blurred and slightly
 * magnified as through a lens, a faint white body, a sheen across the top, and a rim lit at the top-left and
 * bottom-right corners. The card's outline clips the corners, so nothing here clips.
 *
 * Two layers: the back (the picture and the body) goes under a tile's artwork where it lets light through; the front
 * (the sheen and the rim) goes over it as the card's foreground, so the app's image fills the tile under the glass.
 */
final class GlassTileDrawable extends Drawable {

    /** How much the lens magnifies what lies behind the tile. */
    private static final float LENS = 1.12f;

    private final float density;
    private final float radius;
    private final int fallback;
    /** 0 clear to 100 frosted (glass_frost): the white in the body and the sheen. */
    private final int frost;
    /** The front layer (sheen and rim), drawn over the artwork; false, the back layer (picture and body). */
    private final boolean front;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF screen = new RectF();
    private final Rect src = new Rect();
    private final int[] loc = new int[2];

    /**
     * @param radiusPx  the card's corner radius, for the rim
     * @param fallback  the fill when there is no wallpaper to see through (the theme's background colour)
     * @param frost     0 clear to 100 frosted
     * @param front     the layer over the artwork, or the one under it
     */
    GlassTileDrawable(float density, float radiusPx, int fallback, int frost, boolean front) {
        this.front = front;
        this.density = density;
        this.radius = radiusPx;
        this.fallback = fallback;
        this.frost = frost;
        // Glass gathers light: what shows through is a little more saturated and brighter than around it.
        ColorMatrix vivid = new ColorMatrix();
        vivid.setSaturation(1.5f);
        float lift = 18f;
        vivid.postConcat(new ColorMatrix(new float[]{1.08f, 0, 0, 0, lift, 0, 1.08f, 0, 0, lift, 0, 0, 1.08f, 0, lift, 0, 0, 0, 1, 0}));
        picture.setColorFilter(new ColorMatrixColorFilter(vivid));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        innerRim.setStyle(Paint.Style.STROKE);
        innerRim.setStrokeWidth(density);
        innerRim.setColor(0x1AFFFFFF);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        super.onBoundsChange(b);
        int top = Math.round(0x40 * frost / 100f), bottom = Math.round(0x14 * frost / 100f);
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom, top << 24 | 0xFFFFFF, bottom << 24 | 0xFFFFFF,
                Shader.TileMode.CLAMP));
        int shine = 0x18 + Math.round(0x20 * frost / 100f);
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f, shine << 24 | 0xFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        // Light from the top left, caught again at the far corner: the bright arcs that make glass read as thick.
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xE6FFFFFF, 0x26FFFFFF, 0x14FFFFFF, 0x80FFFFFF},
                new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (front) {
            drawFront(canvas, b);
            return;
        }
        Bitmap backdrop = GlassBackdrop.bitmap();
        Callback cb = getCallback();
        if (backdrop != null && cb instanceof View) {
            View v = (View) cb;
            v.getLocationOnScreen(loc);
            // getLocationOnScreen includes the focus zoom; the picture behind is the unzoomed tile's.
            float w = v.getWidth() * v.getScaleX(), h = v.getHeight() * v.getScaleY();
            screen.set(loc[0], loc[1], loc[0] + w, loc[1] + h);
            if (GlassBackdrop.source(screen, LENS, src)) canvas.drawBitmap(backdrop, src, b, picture);
            else canvas.drawColor(fallback);
        } else {
            canvas.drawColor(fallback);
        }
        canvas.drawRect(b, body);
    }

    private void drawFront(Canvas canvas, Rect b) {
        canvas.drawRect(b.left, b.top, b.right, b.top + b.height() * 0.5f, sheen);
        float half = rim.getStrokeWidth() / 2f;
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawRoundRect(rect, radius - half, radius - half, rim);
        float in = 2.5f * density;
        rect.inset(in, in);
        canvas.drawRoundRect(rect, radius - half - in, radius - half - in, innerRim);
    }

    @Override
    public void setAlpha(int alpha) {
        picture.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
