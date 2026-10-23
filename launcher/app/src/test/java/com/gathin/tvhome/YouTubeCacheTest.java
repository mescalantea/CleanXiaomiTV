package com.gathin.tvhome;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YouTubeCacheTest {

    @Test
    public void roundTrip() throws Exception {
        List<ShelfItem> items = Arrays.asList(
                new ShelfItem(ShelfItem.Source.YOUTUBE, "abc", "Title\twith tab", "Channel", "https://i.ytimg.com/vi/abc/mqdefault.jpg", -1, 1792490400000L, "UC1"),
                new ShelfItem(ShelfItem.Source.YOUTUBE, "def", "", "", null, -1, 5));
        StringWriter out = new StringWriter();
        YouTubeCache.write(items, out);
        List<ShelfItem> back = YouTubeCache.read(new BufferedReader(new StringReader(out + "broken\nx\tnot-a-number\t\tT\tC\nold\t7\t\tTitle\tName\n")));
        assertEquals(3, back.size());
        assertEquals("UC1", back.get(0).channel);
        assertEquals("", back.get(1).channel);
        assertEquals("old", back.get(2).id);
        assertEquals("", back.get(2).channel);
        assertEquals("abc", back.get(0).id);
        assertEquals("Title with tab", back.get(0).title);
        assertEquals("Channel", back.get(0).subtitle);
        assertEquals("https://i.ytimg.com/vi/abc/mqdefault.jpg", back.get(0).image);
        assertEquals(1792490400000L, back.get(0).published);
        assertEquals(ShelfItem.Source.YOUTUBE, back.get(0).source);
        assertNull(back.get(1).image);
    }

    @Test
    public void freshForHalfAnHour() {
        long now = 10_000_000L;
        assertTrue(YouTubeCache.fresh(now - 60_000L, now));
        assertFalse(YouTubeCache.fresh(now - YouTubeCache.MAX_AGE_MS, now));
        assertFalse(YouTubeCache.fresh(0, now));
        assertFalse(YouTubeCache.fresh(now + 60_000L, now));
    }
}
