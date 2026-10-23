package com.gathin.tvhome;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class YouTubeIdentityTest {

    @Test
    public void readsNameAndTheLargestPicture() throws Exception {
        Map<String, YouTubeIdentity> m = YouTubeIdentity.parse("{\"items\":["
                + "{\"id\":\"UC1\",\"snippet\":{\"title\":\" Alex Example \",\"thumbnails\":{"
                + "\"default\":{\"url\":\"https://example.com/88.jpg\"},\"medium\":{\"url\":\"https://example.com/240.jpg\"},"
                + "\"high\":{\"url\":\"https://example.com/800.jpg\"}}}},"
                + "{\"id\":\"UC2\",\"snippet\":{\"title\":\"Sam\",\"thumbnails\":{\"default\":{\"url\":\"https://example.com/s.jpg\"}}}},"
                + "{\"id\":\"UC3\",\"snippet\":{\"title\":\"No picture\",\"thumbnails\":{\"high\":{\"url\":\"http://example.com/x.jpg\"}}}},"
                + "{\"snippet\":{\"title\":\"No id\"}}]}");
        assertEquals(3, m.size());
        assertEquals("Alex Example", m.get("UC1").name);
        assertEquals("https://example.com/800.jpg", m.get("UC1").photo);
        assertEquals("https://example.com/s.jpg", m.get("UC2").photo);
        assertEquals("", m.get("UC3").photo);
    }

    @Test
    public void noItems() throws Exception {
        assertTrue(YouTubeIdentity.parse("{\"pageInfo\":{\"totalResults\":0}}").isEmpty());
    }
}
