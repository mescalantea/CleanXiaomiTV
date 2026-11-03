package com.majnioui.tv.channels;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelListTest {

    private static final String LIST = "#EXTM3U @someone\n"
            + "#EXTM3U url-tvg=\"https://example.com/epg.xml.gz\"\n"
            + "#EXTINF:-1 tvg-id=\"La1.TV\" tvg-logo=\"https://example.com/la1.png\" group-title=\"Generalistas\" tvg-name=\"La 1\",La 1\n"
            + "https://example.com/la1/main.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"La1.TV\" tvg-logo=\"\" group-title=\"Generalistas\" tvg-name=\"La 1\",La 1\n"
            + "https://backup.example.com/la1.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"Regional.TV\" group-title=\"Galicia\",Regional\n"
            + "#EXTVLCOPT:http-user-agent=Agent/1.0\n"
            + "#EXTVLCOPT:http-referrer=https://example.com/\n"
            + "https://example.com/regional.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"\" group-title=\"Int. Europa\",Abroad\n"
            + "https://example.com/abroad.m3u8\n"
            + "#EXTINF:-1 tvg-id=\"NoLink.TV\" group-title=\"Galicia\",No link\n";

    @Test
    public void keepsTheListOrderAndOneEntryPerChannel() {
        List<ChannelList.Channel> list = ChannelList.unique(ChannelList.parse(LIST));
        assertEquals(3, list.size());
        assertEquals("La 1", list.get(0).name);
        assertEquals("https://example.com/la1/main.m3u8", list.get(0).url);
        assertEquals("Generalistas", list.get(0).group);
        assertEquals("Regional", list.get(1).name);
        assertEquals("Agent/1.0", list.get(1).userAgent);
        assertEquals("https://example.com/", list.get(1).referrer);
        assertEquals("Int. Europa", list.get(2).group);
        assertNull(list.get(0).userAgent);
    }

    @Test
    public void channelsWithoutAnIdAreKeptByName() {
        List<ChannelList.Channel> list = ChannelList.unique(ChannelList.parse(LIST + LIST));
        assertEquals(3, list.size());
    }

    @Test
    public void internationalChannelsComeAfterTheListWithoutRepeatingANameOfIt() {
        List<ChannelList.Channel> list = ChannelList.unique(ChannelList.parse(LIST));
        List<ChannelList.Channel> extra = ChannelList.parse("#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"\" group-title=\"Internacional (oficial)\",REGIONAL\n"
                + "https://example.com/other-regional.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"\" group-title=\"Internacional (oficial)\",World News\n"
                + "https://example.com/world.m3u8\n");
        List<ChannelList.Channel> merged = ChannelList.merge(list, extra);
        assertEquals(4, merged.size());
        assertEquals("https://example.com/regional.m3u8", merged.get(1).url);
        assertEquals("World News", merged.get(3).name);
        assertEquals("Internacional (oficial)", merged.get(3).group);
    }

    @Test
    public void channelsAreRememberedByIdOrElseByLink() {
        List<ChannelList.Channel> list = ChannelList.unique(ChannelList.parse(LIST));
        assertEquals("La1.TV", ChannelList.key(list.get(0)));
        assertEquals("https://example.com/abroad.m3u8", ChannelList.key(list.get(2)));
    }

    @Test
    public void searchIgnoresCaseAndAccents() {
        assertTrue(ChannelList.matches("Telecinco", "CINCO"));
        assertTrue(ChannelList.matches("Telemadrid", "telemadrid "));
        assertTrue(ChannelList.matches("À Punt", "a pu"));
        assertTrue(ChannelList.matches("Canal Sur Andalucia", "andalucía"));
        assertTrue(ChannelList.matches("La 1", ""));
        assertFalse(ChannelList.matches("La 1", "la 2"));
    }
}
