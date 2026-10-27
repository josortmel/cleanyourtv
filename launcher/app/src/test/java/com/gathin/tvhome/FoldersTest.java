package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Plain JVM tests for the folders on Home: file format, start_folders and new folders. */
public class FoldersTest {

    @Test
    public void roundTrips() {
        Folders f = new Folders();
        Folders.Folder games = f.create("Juegos", "com.limelight/shortcut/a");
        games.keys.add("shortcut:baba-is-you");
        f.create("TCL", "com.tcl.gallery");
        String text = Folders.encode(f);
        assertEquals("tvhome-folders 1\nfolder 1 Juegos\ncom.limelight/shortcut/a\nshortcut:baba-is-you\n"
                + "folder 2 TCL\ncom.tcl.gallery\n", text);
        Folders back = Folders.decode(text);
        assertEquals(2, back.list.size());
        assertEquals("Juegos", back.byId("1").name);
        assertEquals(Arrays.asList("com.limelight/shortcut/a", "shortcut:baba-is-you"), back.byId("1").keys);
        assertEquals(Collections.singletonList("com.tcl.gallery"), back.byId("2").keys);
    }

    @Test
    public void aNameKeepsItsSpaces() {
        Folders back = Folders.decode("tvhome-folders 1\nfolder 3 Cine y series\na/.A\n");
        assertEquals("Cine y series", back.byId("3").name);
    }

    @Test
    public void anEmptyFileHasNoFolders() {
        assertEquals(0, Folders.decode("tvhome-folders 1\n").list.size());
    }

    @Test
    public void rejectsWhatIsNotAFoldersFile() {
        assertNull(Folders.decode(null));
        assertNull(Folders.decode("tvhome-order 1\ndock x\n"));
        assertNull(Folders.decode("tvhome-folders 1\na/.A\nfolder 1 X\n"));            // a tile before any folder
        assertNull(Folders.decode("tvhome-folders 1\nfolder 1 X\na/.A\nfolder 1 Y\n"));   // an id used twice
        assertNull(Folders.decode("tvhome-folders 1\nfolder 1 X\na/.A\nfolder 2 Y\na/.A\n"));   // a tile in two
        assertNull(Folders.decode("tvhome-folders 1\nfolder 1 ‎\na/.A\n"));         // no name left
        assertNull(Folders.decode("tvhome-folders 1\nfolder 1\na/.A\n"));                 // no name at all
        assertNull(Folders.decode("tvhome-folders 1\nfolder 1 X\n\na/.A\n"));             // a blank line
    }

    @Test
    public void startFoldersAreNameAndApps() {
        Folders f = Folders.start(Arrays.asList(
                "Juegos=shortcut:alien-isolation, shortcut:noita",
                "Tele = com.rtve.androidtv,com.tcl.tv,",
                "=com.x",                      // no name
                "Empty=",                      // no app
                "Nothing here",                // no '='
                "Again=com.tcl.tv,com.y"));    // com.tcl.tv is in Tele already
        assertEquals(3, f.list.size());
        assertEquals("Juegos", f.list.get(0).name);
        assertEquals(Arrays.asList("shortcut:alien-isolation", "shortcut:noita"), f.list.get(0).keys);
        assertEquals("Tele", f.list.get(1).name);
        assertEquals(Arrays.asList("com.rtve.androidtv", "com.tcl.tv"), f.list.get(1).keys);
        assertEquals(Collections.singletonList("com.y"), f.list.get(2).keys);
        // What start_folders makes, the file keeps.
        assertEquals(Folders.encode(f), Folders.encode(Folders.decode(Folders.encode(f))));
    }

    @Test
    public void twoStartFoldersWithOneNameAreTwoFolders() {
        Folders f = Folders.start(Arrays.asList("Juegos=a", "Juegos=b"));
        assertEquals("Juegos", f.list.get(0).name);
        assertEquals("Juegos 2", f.list.get(1).name);
    }

    @Test
    public void aNewFolderGetsAnIdNoFolderHas() {
        Folders f = Folders.decode("tvhome-folders 1\nfolder 7 X\na/.A\nfolder 2 Y\nb/.B\n");
        assertEquals("8", f.create("Z", "c/.C").id);
    }

    @Test
    public void aNewFolderGetsAFreeName() {
        Folders f = new Folders();
        assertEquals("Folder", f.freeName("Folder"));
        f.create("Folder", "a/.A");
        f.create("Folder 2", "b/.B");
        assertEquals("Folder 3", f.freeName("Folder"));
    }

    @Test
    public void namesAreOneLine() {
        assertEquals("Cine y series", Folders.cleanName("  Cine\ny\tseries "));
        assertNull(Folders.cleanName(" \n "));
    }
}
