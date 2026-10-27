package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pinned shortcuts as Home tiles (config.xml pinned_shortcuts): what an app pins to the launcher, such as Moonlight's
 * "Create shortcut" on a game, shows as a tile of its own and opens straight into it. Only the default launcher may
 * read and start shortcuts, which this launcher is once it owns Home.
 */
final class PinnedShortcuts {

    /** Moonlight labels a game's shortcut "Game (PC name)"; the tile shows the game's name alone. */
    private static final String MOONLIGHT = "com.limelight";
    /** Banners pushed to the TV beside config.json: banners/&lt;slug of the tile name&gt;.jpg or .png. */
    static final String BANNER_DIR = "banners";

    private PinnedShortcuts() {
    }

    static boolean available(Context ctx) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        try {
            return la != null && la.hasShortcutHostPermission();
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }

    /** Every enabled shortcut pinned to this launcher, or none when it is not the default launcher. */
    static List<ShortcutInfo> pinned(Context ctx) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        if (la == null || !available(ctx)) return Collections.emptyList();
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
        List<ShortcutInfo> out = new ArrayList<>();
        try {
            List<ShortcutInfo> found = la.getShortcuts(q, Process.myUserHandle());
            if (found != null) for (ShortcutInfo s : found) if (s.isEnabled()) out.add(s);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        return out;
    }

    /**
     * What Home shows of the pinned shortcuts: each one's app, id and label. An app may update its shortcuts every
     * few seconds (Moonlight refreshes its PC shortcut on each poll of the PC); only a change here is worth a rebuild.
     */
    static String signature(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (ShortcutInfo s : pinned(ctx)) {
            sb.append(s.getPackage()).append('/').append(s.getId()).append('=').append(label(s)).append('\n');
        }
        return sb.toString();
    }

    /** The tile's name: the short label, without Moonlight's " (PC name)" suffix. */
    static String label(ShortcutInfo s) {
        CharSequence l = s.getShortLabel() != null ? s.getShortLabel() : s.getLongLabel();
        String label = l == null ? s.getId() : l.toString().trim();
        if (MOONLIGHT.equals(s.getPackage()) && label.endsWith(")")) {
            int open = label.lastIndexOf(" (");
            if (open > 0) label = label.substring(0, open);
        }
        return label;
    }

    /**
     * File name of a tile's banner: accents and signs dropped, lower case, runs of anything else as one hyphen.
     * The same rule as the PC script that composes the banners (tele/juegos/sincronizar_juegos.py, slug()).
     */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /**
     * The tile's art: a banner pushed for this name when there is one (the game's own key art with its logo), else
     * the shortcut's icon on a blurred, darkened copy of itself, so a poster-shaped icon still fills a 16:9 tile.
     */
    static Drawable banner(Context ctx, ShortcutInfo s, String label) {
        File dir = ctx.getExternalFilesDir(BANNER_DIR);
        if (dir != null) {
            String base = slug(label);
            for (String ext : new String[]{".jpg", ".png", ".webp"}) {
                File f = new File(dir, base + ext);
                if (!f.isFile()) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null) return new BitmapDrawable(ctx.getResources(), b);
            }
        }
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        Drawable icon = null;
        try {
            if (la != null) icon = la.getShortcutIconDrawable(s, ctx.getResources().getDisplayMetrics().densityDpi);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        return icon == null ? null : composed(ctx, icon);
    }

    private static Drawable composed(Context ctx, Drawable icon) {
        int w = 480, h = 270;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // Backdrop: the icon drawn tiny and scaled up with filtering, which blurs it for free, then darkened.
        Bitmap tiny = Bitmap.createBitmap(12, 7, Bitmap.Config.ARGB_8888);
        Canvas t = new Canvas(tiny);
        icon.setBounds(-6, -16, 18, 23);
        icon.draw(t);
        c.drawBitmap(Bitmap.createScaledBitmap(tiny, w, h, true), 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.drawColor(0x66000000);
        int side = (int) (h * 0.74f);
        icon.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        icon.draw(c);
        tiny.recycle();
        return new BitmapDrawable(ctx.getResources(), out);
    }

    /** Opens the shortcut as the app defined it (Moonlight's starts the game and wakes the PC if it sleeps). */
    static boolean start(Context ctx, String pkg, String id) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        if (la == null) return false;
        try {
            la.startShortcut(pkg, id, null, null, Process.myUserHandle());
            return true;
        } catch (RuntimeException e) {   // ActivityNotFoundException, IllegalStateException, SecurityException
            return false;
        }
    }

    /** Takes one shortcut off Home: the app's other pinned shortcuts stay. */
    static boolean unpin(Context ctx, String pkg, String id) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        if (la == null) return false;
        UserHandle user = Process.myUserHandle();
        List<String> keep = new ArrayList<>();
        for (ShortcutInfo s : pinned(ctx)) if (s.getPackage().equals(pkg) && !s.getId().equals(id)) keep.add(s.getId());
        try {
            la.pinShortcuts(pkg, keep, user);
            return true;
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }
}
