package com.majnioui.tv.channels;

import org.junit.Test;
import org.kxml2.io.KXmlParser;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EpgTest {

    private static final String GUIDE = "<?xml version='1.0' encoding='utf-8'?><tv>"
            + "<channel id=\"La1.TV\"><display-name>La1.TV</display-name></channel>"
            + "<programme channel=\"La1.TV\" start=\"20261023080000 +0000\" stop=\"20261023100000 +0000\">"
            + "<title>Morning</title><desc>Long text that is not kept</desc><icon src=\"x\" /></programme>"
            + "<programme channel=\"La1.TV\" start=\"20261023120000 +0200\" stop=\"20261023130000 +0200\">"
            + "<title>News\tat ten</title></programme>"
            + "<programme channel=\"La1.TV\" start=\"20261023020000 +0000\" stop=\"20261023030000 +0000\">"
            + "<title>Too early</title></programme>"
            + "<programme channel=\"La1.TV\" start=\"20261024230000 +0000\" stop=\"20261025000000 +0000\">"
            + "<title>Tomorrow</title></programme>"
            + "<programme channel=\"Other.TV\" start=\"20261023080000 +0000\" stop=\"20261023090000 +0000\">"
            + "<title>Not listed</title></programme>"
            + "<programme channel=\"La1.TV\" start=\"bad\" stop=\"20261023090000 +0000\"><title>Bad</title></programme>"
            + "</tv>";

    private static long utc(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s).getTime();
    }

    private static Map<String, List<Epg.Programme>> parse(long from, long to) throws Exception {
        KXmlParser p = new KXmlParser();
        p.setInput(new StringReader(GUIDE));
        return Epg.parse(p, new HashSet<>(Arrays.asList("La1.TV", "Missing.TV")), from, to);
    }

    @Test
    public void keepsListedChannelsInTheWindowWithTheirOffsets() throws Exception {
        Map<String, List<Epg.Programme>> g = parse(utc("202610230700"), utc("202610232200"));
        assertEquals(1, g.size());
        List<Epg.Programme> la1 = g.get("La1.TV");
        assertEquals(2, la1.size());
        assertEquals("Morning", la1.get(0).title);
        assertEquals(utc("202610230800"), la1.get(0).start);
        assertEquals("News at ten", la1.get(1).title);
        assertEquals(utc("202610231000"), la1.get(1).start);
        assertEquals(utc("202610231100"), la1.get(1).stop);
        assertNull(g.get("Other.TV"));
    }

    @Test
    public void aProgrammeStillOnAirAtTheWindowStartIsKept() throws Exception {
        List<Epg.Programme> la1 = parse(utc("202610230930"), utc("202610232200")).get("La1.TV");
        assertEquals("Morning", la1.get(0).title);
    }

    @Test
    public void nowAndNext() throws Exception {
        List<Epg.Programme> la1 = parse(utc("202610230700"), utc("202610232200")).get("La1.TV");
        assertEquals(0, Epg.onAir(la1, utc("202610230900")));
        assertEquals(1, Epg.next(la1, utc("202610230900")));
        assertEquals(-1, Epg.onAir(la1, utc("202610230730")));
        assertEquals(0, Epg.next(la1, utc("202610230730")));
        assertEquals(-1, Epg.onAir(la1, utc("202610231130")));
        assertEquals(1, Epg.onAir(la1, utc("202610231000")));
        assertEquals(-1, Epg.next(la1, utc("202610231000")));
        assertEquals(-1, Epg.onAir(null, 0));
    }

    @Test
    public void windowRunsToTheEndOfTheLocalDayOrSixHoursAhead() throws Exception {
        TimeZone madrid = TimeZone.getTimeZone("Europe/Madrid");
        long[] morning = Epg.window(utc("202610230800"), madrid);
        assertEquals(utc("202610230600"), morning[0]);
        assertEquals(utc("202610232200"), morning[1]);
        long[] late = Epg.window(utc("202610232100"), madrid);
        assertEquals(utc("202610240300"), late[1]);
    }

    @Test
    public void savedFormatRoundTrips() throws Exception {
        Map<String, List<Epg.Programme>> g = parse(utc("202610230700"), utc("202610232200"));
        StringWriter out = new StringWriter();
        Epg.write(g, out);
        Map<String, List<Epg.Programme>> back = Epg.read(new BufferedReader(new StringReader(out + "broken line\n")));
        assertEquals(2, back.get("La1.TV").size());
        assertEquals("News at ten", back.get("La1.TV").get(1).title);
        assertEquals(utc("202610231000"), back.get("La1.TV").get(1).start);
    }

    @Test
    public void emptyGuideGivesNothing() throws Exception {
        KXmlParser p = new KXmlParser();
        p.setInput(new StringReader("<tv></tv>"));
        assertTrue(Epg.parse(p, new HashSet<>(Arrays.asList("La1.TV")), 0, Long.MAX_VALUE).isEmpty());
        assertFalse(Epg.time(new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT), "20261023080000 +0000") < 0);
        assertEquals(-1, Epg.time(new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT), "nope"));
    }
}
