package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.RequiresApi;

/**
 * The glass of a tile as a lens (glass_lens, Android 13+): the tile's own image bends near the edge as through the
 * thickness of a pane, with a little colour fringing in the bevel and a specular rim lit from one side. When the
 * tile takes focus the light sweeps along the rim and the tile tilts towards the side focus came from, then settles,
 * its image drifting the other way as if it sat deeper than the glass (tvOS's parallax).
 *
 * The lens is an AGSL shader run as the card's RenderEffect, so the GPU does the work on the layer the card already
 * draws into; below Android 13 nothing here runs and the tile keeps glass_tiles' flat sheen and rim.
 */
final class GlassLens {

    /** Signed distance to the rounded tile, its normal, and the refraction and light the bevel gets from it. */
    private static final String AGSL =
            "uniform shader content;\n"
          + "uniform float2 size;\n"
          + "uniform float radius;\n"
          + "uniform float bevel;\n"
          + "uniform float strength;\n"
          + "uniform float2 light;\n"
          + "uniform float glint;\n"
          + "float sdf(float2 p) {\n"
          + "  float2 h = size * 0.5;\n"
          + "  float2 q = abs(p - h) - (h - float2(radius));\n"
          + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
          + "}\n"
          + "half4 main(float2 p) {\n"
          + "  float d = sdf(p);\n"
          + "  float t = clamp(1.0 + d / bevel, 0.0, 1.0);\n"           // 0 deep inside, 1 at the edge
          + "  if (t <= 0.0) { return content.eval(p); }\n"
          + "  float e = 1.0;\n"
          + "  float2 n = normalize(float2(sdf(p + float2(e, 0.0)) - sdf(p - float2(e, 0.0)),\n"
          + "                              sdf(p + float2(0.0, e)) - sdf(p - float2(0.0, e))) + 1e-5);\n"
          + "  float k = t * t * t;\n"                                     // the bevel curves harder near the edge
          + "  float2 disp = -n * strength * k;\n"                         // looks inward: the edge magnifies
          + "  half4 c = content.eval(p + disp);\n"
          + "  half r = content.eval(p + disp * 1.08).r;\n"
          + "  half b = content.eval(p + disp * 0.92).b;\n"
          + "  c = half4(r, c.g, b, c.a);\n"
          + "  float facing = max(dot(n, -normalize(light)), 0.0);\n"      // the side of the rim that faces the light
          + "  float spec = pow(facing, 4.0) * k * (0.55 + 0.9 * glint);\n"
          + "  float back = pow(max(dot(n, normalize(light)), 0.0), 6.0) * k * 0.22;\n"   // caught again opposite
          + "  c.rgb = c.rgb + half3(spec + back) * c.a;\n"
          + "  return c;\n"
          + "}\n";

    /** How much brighter the focused tile's rim stays once the sweep is over. */
    private static final float FOCUS_GLINT = 0.45f;

    /** Where the last focused tile was on screen, to tell which side focus comes from. */
    private static int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
    /** The shader failed to build on this GPU once: the lens stays off for the rest of the process. */
    private static boolean broken;

    private GlassLens() {
    }

    /** Records this tile as focused and says whether focus came to it from the left (or from above, same column). */
    static boolean cameFromLeft(View card) {
        int[] at = new int[2];
        card.getLocationOnScreen(at);
        boolean fromLeft = lastX == Integer.MIN_VALUE || at[0] > lastX + 4 || (Math.abs(at[0] - lastX) <= 4 && at[1] > lastY);
        lastX = at[0];
        lastY = at[1];
        return fromLeft;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken;
    }

    /** Puts the lens on a card at rest: light from the top left, no glint. Again whenever the card changes size. */
    static void attach(View card, float density) {
        if (!supported()) return;
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) apply(v, density, -0.7f, -1f, 0f);
        });
        if (card.getWidth() > 0) apply(card, density, -0.7f, -1f, 0f);
    }

    /**
     * Focus arrived (or left). Arriving, the light sweeps across the rim from the side focus came from and the tile
     * tilts that way and springs back; leaving, it returns to rest.
     *
     * @param fromLeft focus came from a tile on the left (or above, which reads as the same diagonal)
     */
    static void onFocus(View card, View image, float density, boolean focused, boolean fromLeft) {
        if (!supported()) return;
        Object running = card.getTag(R.id.glass_lens_anim);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        float side = fromLeft ? -1f : 1f;
        card.setCameraDistance(9000 * density);
        if (!focused) {
            // ObjectAnimators, not card.animate(): that one carries the tile's own zoom out at the same moment.
            ObjectAnimator.ofFloat(card, View.ROTATION_Y, 0f).setDuration(180).start();
            ObjectAnimator.ofFloat(card, View.ROTATION_X, 0f).setDuration(180).start();
            if (image != null) {
                ObjectAnimator.ofFloat(image, View.TRANSLATION_X, 0f).setDuration(180).start();
                ObjectAnimator.ofFloat(image, View.TRANSLATION_Y, 0f).setDuration(180).start();
            }
            apply(card, density, -0.7f, -1f, 0f);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(620);
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            // Tilt: leans 7 degrees towards where focus came from, then settles with one small overshoot.
            float spring = (float) (Math.exp(-5.0 * f) * Math.cos(f * Math.PI * 2.2));
            card.setRotationY(7f * side * spring);
            card.setRotationX(-2.5f * spring);
            if (image != null) {
                // Within the image's own focus zoom (3.5%), so its edge never shows.
                image.setTranslationX(-2.5f * density * side * spring);
                image.setTranslationY(1f * density * spring);
            }
            // Light: swings from the side focus came from across the top, then rests at the top left.
            float angle = (float) Math.toRadians(-90 + 70 * side * (1f - f) - 35 * f);
            // The glint fades from the sweep to a brighter rest than the other tiles': that is the focus now.
            apply(card, density, (float) Math.cos(angle), (float) Math.sin(angle), FOCUS_GLINT + (1f - FOCUS_GLINT) * (1f - f));
        });
        card.setTag(R.id.glass_lens_anim, a);
        a.start();
    }

    private static void apply(View card, float density, float lx, float ly, float glint) {
        if (card.getWidth() <= 0 || card.getHeight() <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        set(card, AppAdapter.cornerRadius(card.getWidth(), density), density, lx, ly, glint);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static void set(View card, float radiusPx, float density, float lx, float ly, float glint) {
        Object kept = card.getTag(R.id.glass_lens_shader);
        RuntimeShader s;
        if (kept instanceof RuntimeShader) {
            s = (RuntimeShader) kept;
        } else {
            try {
                s = new RuntimeShader(AGSL);   // compiled once per tile; the frames only change its uniforms
            } catch (RuntimeException e) {
                broken = true;
                Log.w("TvHome", "glass_lens shader unavailable, lens off: " + e);
                return;
            }
            card.setTag(R.id.glass_lens_shader, s);
        }
        s.setFloatUniform("size", card.getWidth(), card.getHeight());
        s.setFloatUniform("radius", radiusPx);
        s.setFloatUniform("bevel", 9f * density);
        s.setFloatUniform("strength", 7f * density);
        s.setFloatUniform("light", lx, ly);
        s.setFloatUniform("glint", glint);
        card.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
    }
}
