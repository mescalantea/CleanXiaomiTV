package com.gathin.tvhome;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JellyfinTest {

    private static final String BASE = "https://jellyfin.example.com";
    private static final String FORMAT = "S%1$dE%2$d";

    @Test
    public void resumeEpisodeShowsSeriesNumberAndProgress() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"0123456789abcdef0123456789abcdef\",\"Name\":\"Pilot\",\"Type\":\"Episode\","
                + "\"SeriesName\":\"Some Show\",\"ParentIndexNumber\":1,\"IndexNumber\":2,"
                + "\"ImageTags\":{\"Primary\":\"t\"},\"UserData\":{\"PlayedPercentage\":41.6}}]}";
        List<ShelfItem> items = Jellyfin.parse(json, BASE, FORMAT);
        assertEquals(1, items.size());
        ShelfItem i = items.get(0);
        assertEquals(ShelfItem.Source.JELLYFIN, i.source);
        assertEquals("Some Show", i.title);
        assertEquals("S1E2 · Pilot", i.subtitle);
        assertEquals(42, i.progress);
        assertEquals(BASE + "/Items/0123456789abcdef0123456789abcdef/Images/Primary?maxHeight=360", i.image);
    }

    @Test
    public void nextUpEpisodeHasNoProgress() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"e1\",\"Name\":\"Two\",\"Type\":\"Episode\",\"SeriesName\":\"Show\","
                + "\"ParentIndexNumber\":3,\"IndexNumber\":10,\"UserData\":{\"PlaybackPositionTicks\":0}}]}";
        ShelfItem i = Jellyfin.parse(json, BASE, "T%1$dE%2$d").get(0);
        assertEquals("T3E10 · Two", i.subtitle);
        assertEquals(-1, i.progress);
    }

    @Test
    public void movieUsesItsNameAndAWidePicture() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"m1\",\"Name\":\"A Film\",\"Type\":\"Movie\","
                + "\"ImageTags\":{\"Primary\":\"p\"},\"BackdropImageTags\":[\"b\"]}]}";
        ShelfItem i = Jellyfin.parse(json, BASE, FORMAT).get(0);
        assertEquals("A Film", i.title);
        assertEquals("", i.subtitle);
        assertEquals(BASE + "/Items/m1/Images/Backdrop?maxHeight=360", i.image);
    }

    @Test
    public void episodeWithoutItsOwnPictureFallsBackToTheSeries() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"e2\",\"Name\":\"X\",\"Type\":\"Episode\",\"SeriesName\":\"S\","
                + "\"ParentBackdropItemId\":\"s1\",\"ParentBackdropImageTags\":[\"b\"]}]}";
        assertEquals(BASE + "/Items/s1/Images/Backdrop?maxHeight=360", Jellyfin.parse(json, BASE, FORMAT).get(0).image);
    }

    @Test
    public void itemWithoutAnyPictureHasNoImage() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"x\",\"Name\":\"Bare\",\"Type\":\"Movie\"}]}";
        assertNull(Jellyfin.parse(json, BASE, FORMAT).get(0).image);
    }

    @Test
    public void emptyAndMissingListsGiveNoItems() throws Exception {
        assertTrue(Jellyfin.parse("{\"Items\":[]}", BASE, FORMAT).isEmpty());
        assertTrue(Jellyfin.parse("{}", BASE, FORMAT).isEmpty());
        assertTrue(Jellyfin.parse("{\"Items\":[{\"Name\":\"no id\"}]}", BASE, FORMAT).isEmpty());
    }

    @Test(expected = Exception.class)
    public void brokenJsonThrows() throws Exception {
        Jellyfin.parse("<html>", BASE, FORMAT);
    }

    @Test
    public void idsBecomeDashedUuids() {
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", Jellyfin.uuid("0123456789abcdef0123456789abcdef"));
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", Jellyfin.uuid("01234567-89ab-cdef-0123-456789abcdef"));
        assertEquals("short", Jellyfin.uuid("short"));
    }

    private static ShelfItem episode(String id, String series) {
        return new ShelfItem(ShelfItem.Source.JELLYFIN, id, "Show", "S1E1 · Name", null, -1, 0, series, "", null, false);
    }

    @Test
    public void newEpisodesKeepStartedSeriesOnceInOrderMarkedNew() {
        java.util.List<ShelfItem> latest = java.util.Arrays.asList(episode("a", "s1"), episode("b", "other"),
                episode("c", "s2"), episode("a", "s1"), episode("d", "s1"), episode("e", ""));
        java.util.List<ShelfItem> out = Jellyfin.fresh(latest, new java.util.HashSet<>(java.util.Arrays.asList("s1", "s2")),
                new java.util.HashSet<>(java.util.Arrays.asList("d")), 12);
        assertEquals(2, out.size());
        assertEquals("a", out.get(0).id);
        assertEquals("c", out.get(1).id);
        assertTrue(out.get(0).fresh);
        assertEquals(1, Jellyfin.fresh(latest, new java.util.HashSet<>(java.util.Arrays.asList("s1", "s2")),
                new java.util.HashSet<>(), 1).size());
    }

    @Test
    public void episodesCarryTheirSeriesOverviewAndALargePicture() throws Exception {
        String json = "{\"Items\":[{\"Id\":\"e1\",\"Name\":\"Two\",\"Type\":\"Episode\",\"SeriesName\":\"Show\","
                + "\"SeriesId\":\"s1\",\"Overview\":\" What happens. \",\"ImageTags\":{\"Primary\":\"t\"}}]}";
        ShelfItem i = Jellyfin.parse(json, BASE, FORMAT).get(0);
        assertEquals("s1", i.channel);
        assertEquals("What happens.", i.description);
        assertEquals(BASE + "/Items/e1/Images/Primary?maxWidth=1280", i.backdrop);
    }
}
