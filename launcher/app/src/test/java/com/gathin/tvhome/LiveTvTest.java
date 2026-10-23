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

    @Test
    public void sportsCardSaysWhenAndWhereAndIsLiveOnlyWhileOn() {
        java.util.TimeZone madrid = java.util.TimeZone.getTimeZone("Europe/Madrid");
        java.util.Locale es = new java.util.Locale("es", "ES");
        long start = 1792868400000L;   // Saturday 24 October 2026, 19:00 UTC = 21:00 in Madrid
        ShelfItem later = LiveTv.sport("La1.TV", "La 1", "https://example.com/la1.png", "España - Japón",
                start, start + 7_200_000L, start - 60_000L, es, madrid);
        assertEquals(ShelfItem.Source.SPORT, later.source);
        assertEquals("España - Japón", later.title);
        assertEquals("Sáb 21:00 · La 1", later.subtitle);
        assertEquals("La1.TV", later.channel);
        assertEquals(-1, later.progress);
        ShelfItem on = LiveTv.sport("La1.TV", "La 1", "", "España - Japón", start, start + 7_200_000L, start + 3_600_000L, es, madrid);
        assertEquals(50, on.progress);
        assertNull(on.image);
        assertEquals("Sat 21:00 · La 1", LiveTv.sport("La1.TV", "La 1", "", "x", start, start + 1, 0, java.util.Locale.ENGLISH, madrid).subtitle);
    }
}
