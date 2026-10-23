package com.majnioui.tv.channels;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
