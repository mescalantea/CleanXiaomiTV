package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YouTubeSubscriptionsTest {

    private static String item(String channel) {
        return "{\"kind\":\"youtube#subscription\",\"snippet\":{\"title\":\"T\",\"resourceId\":"
                + "{\"kind\":\"youtube#channel\",\"channelId\":\"" + channel + "\"}}}";
    }

    @Test
    public void pagesAddTheirChannelsAndGiveTheNextToken() throws Exception {
        List<String> out = new ArrayList<>();
        String next = YouTubeSubscriptions.parsePage("{\"nextPageToken\":\"CDIQAA\",\"items\":["
                + item("UC1") + "," + item("UC2") + "]}", out);
        assertEquals("CDIQAA", next);
        assertNull(YouTubeSubscriptions.parsePage("{\"items\":[" + item("UC2") + "," + item("UC3")
                + ",{\"snippet\":{}},{}]}", out));
        assertEquals(Arrays.asList("UC1", "UC2", "UC3"), out);
    }

    @Test
    public void emptyPage() throws Exception {
        List<String> out = new ArrayList<>();
        assertNull(YouTubeSubscriptions.parsePage("{\"pageInfo\":{\"totalResults\":0}}", out));
        assertTrue(out.isEmpty());
    }

    @Test(expected = Exception.class)
    public void brokenPageThrows() throws Exception {
        YouTubeSubscriptions.parsePage("<html>", new ArrayList<>());
    }

    @Test
    public void fixedChannelsComeFirstAndEachChannelOnce() {
        assertEquals(Arrays.asList("UCfixed", "UCboth", "UCsub"),
                YouTubeSubscriptions.merge(Arrays.asList("UCfixed", "UCboth"), Arrays.asList("UCboth", "UCsub")));
        assertEquals(Arrays.asList("UCsub"), YouTubeSubscriptions.merge(Collections.emptyList(), Arrays.asList("UCsub")));
    }
}
