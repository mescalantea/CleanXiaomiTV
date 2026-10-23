package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LiveTvTest {

    @Test
    public void cardShowsTheProgrammeAndHowFarItHasGot() {
        ShelfItem i = LiveTv.item("La1.TV", "La 1", "https://example.com/la1.png", "News", 1000, 3000, 1500);
        assertEquals(ShelfItem.Source.LIVE, i.source);
        assertEquals("La1.TV", i.id);
        assertEquals("La 1", i.title);
        assertEquals("News", i.subtitle);
        assertEquals("https://example.com/la1.png", i.image);
        assertEquals(25, i.progress);
    }

    @Test
    public void withoutAProgrammeThereIsNoProgress() {
        ShelfItem i = LiveTv.item("La1.TV", "La 1", "", null, 0, 0, 1500);
        assertEquals("", i.subtitle);
        assertEquals(-1, i.progress);
        assertNull(i.image);
    }
}
