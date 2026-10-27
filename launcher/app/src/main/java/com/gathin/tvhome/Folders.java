package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner's folders on Home (config.xml folders), kept in the launcher's private files (folders.txt) as the saved
 * order is (Order). A folder holds tiles by key: a tile's component, flattened short, as Order keys it; or, in the
 * folders start_folders made, an app as dock_apps names it (a package, or "shortcut:" and a pinned shortcut's slug).
 * A tile in a folder is not on Home. The folder is, as a tile of its own keyed by the launcher's package and
 * {@link #TILE_CLASS} with its id, so the saved order and Move treat it as any other tile.
 *
 * start_folders ({@code "Name=app,app"}) are the folders Home starts with: they are written to the file the first time
 * Home loads with folders on, and from then on the file is the owner's, so a theme pushed later never undoes them.
 *
 * Format, UTF-8 text: {@code tvhome-folders 1}, then per folder {@code folder <id> <name>} and one tile key per line.
 */
final class Folders {

    private static final String TAG = "TvHome.Folders";
    static final String FILE_NAME = "folders.txt";
    private static final String MAGIC = "tvhome-folders 1";
    private static final String FOLDER = "folder ";
    /** A folder tile's component class: this and the folder's id. */
    static final String TILE_CLASS = "folder/";

    static final class Folder {
        /** Digits, unique among the folders and never reused while the folder lives, so the saved order keeps its place. */
        final String id;
        String name;
        final List<String> keys = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    final List<Folder> list = new ArrayList<>();

    Folder byId(String id) {
        for (Folder f : list) if (f.id.equals(id)) return f;
        return null;
    }

    /** A new folder with this name holding this tile, after the others. */
    Folder create(String name, String key) {
        int next = 1;
        for (Folder f : list) {
            try {
                next = Math.max(next, Integer.parseInt(f.id) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        Folder f = new Folder(String.valueOf(next), name);
        f.keys.add(key);
        list.add(f);
        return f;
    }

    /** base, or base and the first number no folder is called yet ("Folder 2"). */
    String freeName(String base) {
        Set<String> taken = new HashSet<>();
        for (Folder f : list) taken.add(f.name);
        if (!taken.contains(base)) return base;
        int n = 2;
        while (taken.contains(base + " " + n)) n++;
        return base + " " + n;
    }

    /** A name as a tile can show it (one line, no control characters, 40 at most), or null when nothing is left. */
    static String cleanName(String name) {
        return Cards.cleanLabel(name, null);
    }

    static String encode(Folders folders) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (Folder f : folders.list) {
            sb.append(FOLDER).append(f.id).append(' ').append(f.name).append('\n');
            for (String k : f.keys) sb.append(k).append('\n');
        }
        return sb.toString();
    }

    /**
     * The folders in this text, or null when it is not a folders file: a wrong header, a tile before any folder, an
     * id used twice, a folder without a name, a blank line, or a tile in two folders.
     */
    static Folders decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (!lines[0].equals(MAGIC)) return null;
        Folders out = new Folders();
        Folder current = null;
        Set<String> ids = new HashSet<>(), keys = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return null;
            }
            // A tile key has no space in it (a component, a package, or "shortcut:" and a slug), so this is a folder.
            if (line.startsWith(FOLDER)) {
                int space = line.indexOf(' ', FOLDER.length());
                if (space < 0) return null;
                String id = line.substring(FOLDER.length(), space), name = cleanName(line.substring(space + 1));
                if (id.isEmpty() || name == null || !ids.add(id)) return null;
                current = new Folder(id, name);
                out.list.add(current);
            } else {
                if (current == null || !keys.add(line)) return null;
                current.keys.add(line);
            }
        }
        return out;
    }

    /**
     * The folders start_folders lists. An entry without a name or an app is left out, and an app already in an
     * earlier folder stays there.
     */
    static Folders start(List<String> entries) {
        Folders out = new Folders();
        Set<String> taken = new HashSet<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            String name = eq > 0 ? cleanName(entry.substring(0, eq)) : null;
            if (name == null) continue;
            Folder f = null;
            for (String app : entry.substring(eq + 1).split(",")) {
                String key = app.trim();
                if (key.isEmpty() || key.contains(" ") || !taken.add(key)) continue;
                if (f == null) f = out.create(out.freeName(name), key);
                else f.keys.add(key);
            }
        }
        return out;
    }

    // ---- file ------------------------------------------------------------------------------------

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The saved folders, or null when there are none or the file does not decode (then it is ignored, not deleted). */
    static Folders load(File file) {
        if (!file.exists()) return null;
        try {
            Folders f = decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (f == null) Log.w(TAG, file.getName() + " is not valid; ignored");
            return f;
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return null;
        }
    }

    /** Writes the folders atomically. False (and a log line) when the write failed. */
    static boolean save(File file, Folders folders) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(folders).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }
}
