package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the PC's games: which are new, the seen file, and which pinned games the PC dropped. */
public class PcGamesTest {

    @Test
    public void aGameIsNewWhenNeitherSeenNorOnHome() {
        assertEquals(Arrays.asList("trepang2", "zero-parades"),
                PcGames.arrived(Arrays.asList("noita", "trepang2", "zero-parades", "baba-is-you"),
                        new HashSet<>(Collections.singletonList("noita")),
                        new HashSet<>(Collections.singletonList("baba-is-you"))));
    }

    @Test
    public void nothingIsNewTheSecondTime() {
        assertTrue(PcGames.arrived(Arrays.asList("noita"), new HashSet<>(Arrays.asList("noita")),
                Collections.emptySet()).isEmpty());
    }

    @Test
    public void seenRoundTrips() {
        PcGames.Seen seen = new PcGames.Seen(new LinkedHashSet<>(Arrays.asList("noita", "trepang2")), "3");
        String text = PcGames.encode(seen);
        assertEquals("tvhome-games-seen 1\nfolder 3\nnoita\ntrepang2\n", text);
        PcGames.Seen back = PcGames.decode(text);
        assertEquals(seen.slugs, back.slugs);
        assertEquals("3", back.folderId);
    }

    @Test
    public void seenWithoutAFolder() {
        PcGames.Seen back = PcGames.decode("tvhome-games-seen 1\nnoita\n");
        assertNull(back.folderId);
        assertEquals(new HashSet<>(Collections.singletonList("noita")), back.slugs);
    }

    @Test
    public void aWrongSeenFileCountsAsNone() {
        assertNull(PcGames.decode("tvhome-folders 1\n"));
        assertNull(PcGames.decode(null));
    }

    @Test
    public void onlyThisPcsPinnedGamesThatItNoLongerListsAreDropped() {
        PcGames pc = new PcGames("PC", "8076FCD2-1EC0-C503-5390-42FF0A9422E6",
                Arrays.asList(new PcGames.Game("Noita", 1912334158L, true)));
        assertFalse(pc.dropped("com.limelight", "Noita (PC)", "Noita"));                   // still on the PC
        assertTrue(pc.dropped("com.limelight", "Dominion (PC)", "Dominion"));              // uninstalled
        assertFalse(pc.dropped("com.limelight", "Dominion (Salón)", "Dominion"));          // another PC
        assertFalse(pc.dropped("org.videolan.vlc", "Dominion (PC)", "Dominion"));          // not Moonlight
        assertFalse(pc.dropped("com.limelight", null, "Dominion"));
    }
}
