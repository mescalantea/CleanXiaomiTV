package com.gathin.tvhome;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class YouTubeFeedTest {

    private static final String FEED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" xmlns:media=\"http://search.yahoo.com/mrss/\""
            + " xmlns=\"http://www.w3.org/2005/Atom\">"
            + "<title>Channel One</title><author><name>Channel One</name></author>"
            + "<entry><id>yt:video:abc123DEF_-</id><yt:videoId>abc123DEF_-</yt:videoId><title>First &amp; best</title>"
            + "<author><name>Channel One</name></author><published>2026-10-20T10:00:00+00:00</published>"
            + "<media:group><media:title>First &amp; best</media:title></media:group></entry>"
            + "<entry><yt:videoId>older000001</yt:videoId><title>Older</title>"
            + "<author><name>Channel One</name></author><published>2026-10-01T10:00:00+00:00</published></entry>"
            + "<entry><title>No id</title></entry>"
            + "<entry><yt:videoId>bad id/..</yt:videoId><title>Bad id</title></entry>"
            + "</feed>";

    private static List<ShelfItem> parse(String xml, boolean namespaces) throws Exception {
        KXmlParser p = new KXmlParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, namespaces);
        p.setInput(new StringReader(xml));
        return YouTubeFeed.parse(p);
    }

    @Test
    public void readsTheEntries() throws Exception {
        List<ShelfItem> items = parse(FEED, false);
        assertEquals(2, items.size());
        ShelfItem first = items.get(0);
        assertEquals(ShelfItem.Source.YOUTUBE, first.source);
        assertEquals("abc123DEF_-", first.id);
        assertEquals("First & best", first.title);
        assertEquals("Channel One", first.subtitle);
        assertEquals("https://i.ytimg.com/vi/abc123DEF_-/mqdefault.jpg", first.image);
        assertEquals(1792490400000L, first.published);
    }

    @Test
    public void sameResultWithNamespaces() throws Exception {
        List<ShelfItem> items = parse(FEED, true);
        assertEquals(2, items.size());
        assertEquals("abc123DEF_-", items.get(0).id);
    }

    @Test
    public void emptyFeedGivesNothing() throws Exception {
        assertTrue(parse("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Empty</title></feed>", false).isEmpty());
    }

    @Test(expected = Exception.class)
    public void brokenFeedThrows() throws Exception {
        parse("<feed><entry><title>cut", false);
    }

    @Test
    public void newestFirstAcrossChannelsCapped() {
        ShelfItem a = new ShelfItem(ShelfItem.Source.YOUTUBE, "a", "", "", null, -1, 1);
        ShelfItem b = new ShelfItem(ShelfItem.Source.YOUTUBE, "b", "", "", null, -1, 3);
        ShelfItem c = new ShelfItem(ShelfItem.Source.YOUTUBE, "c", "", "", null, -1, 2);
        List<ShelfItem> out = YouTubeFeed.newest(Arrays.asList(a, b, c), 2);
        assertEquals(2, out.size());
        assertEquals("b", out.get(0).id);
        assertEquals("c", out.get(1).id);
    }
}
