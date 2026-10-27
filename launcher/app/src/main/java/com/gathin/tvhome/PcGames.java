package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The PC's games (config.xml pc_games). games.json, pushed beside config.json by the PC, names the PC as Moonlight
 * knows it and each game Sunshine streams with the number Sunshine gives it:
 * <pre>{"computer": {"name": "PC", "uuid": "8076FCD2-…"}, "games": [{"name": "Noita", "app_id": 1912334158, "hdr": true}]}</pre>
 * Each game is a tile that opens straight into it through Moonlight's ShortcutTrampoline, which wakes the PC first, as
 * a shortcut pinned from Moonlight does; so a game installed on the PC reaches Home with no step on the TV, and one
 * removed leaves it. Its art is the banner pushed for its name (banners/&lt;slug&gt;.jpg).
 *
 * The games already seen are kept in the launcher's private files (pc_games_seen.txt), so that only a game new to the
 * list goes into pc_games_folder; one the owner took out of that folder stays out.
 */
final class PcGames {

    private static final String TAG = "TvHome.PcGames";
    static final String FILE_NAME = "games.json";
    static final String SEEN_FILE = "pc_games_seen.txt";
    private static final String SEEN_MAGIC = "tvhome-games-seen 1";
    private static final String SEEN_FOLDER = "folder ";
    /** Moonlight, and the activity its own pinned shortcuts open (exported). */
    static final String MOONLIGHT = "com.limelight";
    private static final String TRAMPOLINE = "com.limelight.ShortcutTrampoline";

    static final class Game {
        final String name;
        final long appId;
        final boolean hdr;

        Game(String name, long appId, boolean hdr) {
            this.name = name;
            this.appId = appId;
            this.hdr = hdr;
        }
    }

    final String computerName;
    final String computerUuid;
    final List<Game> games;

    PcGames(String computerName, String computerUuid, List<Game> games) {
        this.computerName = computerName;
        this.computerUuid = computerUuid;
        this.games = Collections.unmodifiableList(new ArrayList<>(games));
    }

    static File file(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), FILE_NAME);
    }

    /** The games in games.json, or null when there is none or it does not parse (then the PC has no tiles). */
    static PcGames load(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            JSONObject pc = o.getJSONObject("computer");
            JSONArray list = o.getJSONArray("games");
            List<Game> games = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject g = list.getJSONObject(i);
                String name = Cards.cleanLabel(g.optString("name"), null);
                if (name == null || !g.has("app_id")) continue;
                games.add(new Game(name, g.getLong("app_id"), g.optBoolean("hdr", true)));
            }
            return new PcGames(pc.optString("name"), pc.optString("uuid"), games);
        } catch (IOException | JSONException e) {
            Log.w(TAG, file.getName() + " is not valid; ignored: " + e);
            return null;
        }
    }

    /**
     * A game Moonlight pinned for this PC ("Name (PC)") that the PC no longer lists: it was uninstalled, so its tile
     * goes too. A shortcut for another PC, or not from Moonlight, is never this PC's to drop.
     */
    boolean dropped(String pkg, CharSequence pinnedLabel, String name) {
        if (!MOONLIGHT.equals(pkg) || pinnedLabel == null || computerName.isEmpty()
                || !pinnedLabel.toString().trim().endsWith(" (" + computerName + ")")) return false;
        String slug = PinnedShortcuts.slug(name);
        for (Game g : games) if (PinnedShortcuts.slug(g.name).equals(slug)) return false;
        return true;
    }

    /** Opens the game through Moonlight, as its own pinned shortcut would. False when Moonlight is not there. */
    boolean launch(Context ctx, Game g) {
        Intent i = new Intent(Intent.ACTION_VIEW).setClassName(MOONLIGHT, TRAMPOLINE)
                .putExtra("UUID", computerUuid)
                .putExtra("Name", computerName)
                .putExtra("AppId", String.valueOf(g.appId))   // Moonlight reads it as a string
                .putExtra("AppName", g.name)
                .putExtra("HDR", g.hdr)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- games seen ------------------------------------------------------------------------------

    /** The games seen so far (by slug) and the folder new ones went into, as the seen file keeps them. */
    static final class Seen {
        final Set<String> slugs;
        /** Folders id of the folder new games go into, or null until one is made or found. */
        final String folderId;

        Seen(Set<String> slugs, String folderId) {
            this.slugs = Collections.unmodifiableSet(new LinkedHashSet<>(slugs));
            this.folderId = folderId;
        }
    }

    static String encode(Seen seen) {
        StringBuilder sb = new StringBuilder(SEEN_MAGIC).append('\n');
        if (seen.folderId != null) sb.append(SEEN_FOLDER).append(seen.folderId).append('\n');
        for (String s : seen.slugs) sb.append(s).append('\n');
        return sb.toString();
    }

    /** The seen file's content, or null when it is not one: then every game counts as new once more. */
    static Seen decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (!lines[0].equals(SEEN_MAGIC)) return null;
        String folder = null;
        Set<String> slugs = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            if (l.startsWith(SEEN_FOLDER)) folder = l.substring(SEEN_FOLDER.length());
            else slugs.add(l);
        }
        return new Seen(slugs, folder);
    }

    /**
     * The games of this list that are new: not seen before and with no tile on Home yet (a game the owner pinned from
     * Moonlight before pc_games is there already, wherever they put it).
     */
    static List<String> arrived(List<String> slugs, Set<String> seen, Set<String> onHome) {
        List<String> out = new ArrayList<>();
        for (String s : slugs) if (!seen.contains(s) && !onHome.contains(s)) out.add(s);
        return out;
    }

    static File seenFile(Context ctx) {
        return new File(ctx.getFilesDir(), SEEN_FILE);
    }

    static Seen loadSeen(File file) {
        if (!file.exists()) return null;
        try {
            return decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return null;
        }
    }

    static void saveSeen(File file, Seen seen) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(seen).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
        }
    }
}
