package com.majnioui.tv.channels;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;

public class GuideProviderTest {

    private static final ChannelList.Channel LA1 =
            new ChannelList.Channel("La1.TV", "La 1", "Generalistas", "https://example.com/la1.png", "https://example.com/la1.m3u8", null, null);

    @Test
    public void rowCarriesTheProgrammeOnAir() {
        List<Epg.Programme> today = Arrays.asList(new Epg.Programme(100, 200, "Morning"), new Epg.Programme(200, 300, "News"));
        assertArrayEquals(new Object[] {"La1.TV", "La 1", "https://example.com/la1.png", "News", 200L, 300L},
                GuideProvider.row(LA1, today, 250));
    }

    @Test
    public void rowWithoutAGuideHasNoProgramme() {
        assertArrayEquals(new Object[] {"La1.TV", "La 1", "https://example.com/la1.png", "", 0L, 0L},
                GuideProvider.row(LA1, null, 250));
        assertArrayEquals(new Object[] {"La1.TV", "La 1", "https://example.com/la1.png", "", 0L, 0L},
                GuideProvider.row(LA1, Arrays.asList(new Epg.Programme(100, 200, "Over")), 250));
    }
}
