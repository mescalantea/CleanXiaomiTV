package com.majnioui.tv.channels;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SportsTest {

    private static final long MIN = 60_000L;

    private static boolean event(String channel, String category, String title, String desc, long minutes) {
        return Sports.isEvent(channel, category, title, desc, 0, minutes * MIN);
    }

    @Test
    public void liveFixturesAndBigEventsOnPublicChannels() {
        assertTrue(event("TDP.TV", "SPORTS", "España - Japón", "La selección española de balonmano femenino se mide a la de Japón.", 80));
        assertTrue(event("TDP.TV", "SPORTS", "ASOBAL: Bidasoa - Ciudad Real", "La máxima competición del balonmano español.", 110));
        assertTrue(event("TDP.TV", "SPORTS", "Basketball Champions League: Joventut - Baskets Bonn", "Una competición de la FIBA.", 110));
        assertTrue(event("TV3.TV", "SPORTS", "Liga F: Real Sociedad - Espanyol", null, 120));
        assertTrue(event("TDP.TV", "SPORTS", "Mundial de gimnasia artística", "Final por aparatos.", 120));
        assertTrue(event("La1.TV", "SPORTS", "Juegos Olímpicos: Atletismo", null, 180));
    }

    @Test
    public void recapsMagazinesAndOldMatchesAreLeftOut() {
        assertFalse(event("TDP.TV", "SPORTS", "Resúmenes UEFA Champions League", null, 90));
        assertFalse(event("GOL.TV", "SPORTS", "Resúmenes LALIGA EA Sports: Deportivo Alavés - Málaga CF", null, 15));
        assertFalse(event("TDP.TV", "SPORTS", "Estudio Estadio Retro", null, 90));
        assertFalse(event("TDP.TV", "SPORTS", "Conexión Vintage", null, 60));
        assertFalse(event("TDP.TV", "SPORTS", "Reportajes TDP - Deportes náuticos", null, 10));
        assertFalse(event("La1.TV", "SPORTS", "Deportes 1", null, 20));
        assertFalse(event("TDP.TV", "SPORTS", "Paralímpicos", null, 10));
        assertFalse(event("GOL.TV", "SPORTS", "Fútbol español 1ª División: R. Madrid - Barcelona",
                "Real Madrid-Barcelona. 02/05/09, jornada 34ª.", 120));
        assertFalse(event("GOL.TV", "SPORTS", "Real Madrid - Barcelona. Copa del Rey",
                "Retransmisión de los partidos de la Copa del Rey de la temporada 1989/90.", 120));
    }

    @Test
    public void onlySportsOnPublicChannels() {
        assertFalse(event("RMTV.TV", "SPORTS", "Real Madrid - Barcelona", null, 120));
        assertFalse(event("Top_Barça.TV", "SPORTS", "Barcelona - Sevilla", null, 120));
        assertFalse(event("La1.TV", "NEWS", "España - Japón", null, 80));
        assertFalse(event("La1.TV", null, "España - Japón", null, 80));
    }

    @Test
    public void bigEventsRankFirst() {
        assertEquals(0, Sports.rank("Juegos Olímpicos: final de 100 m"));
        assertEquals(1, Sports.rank("Mundial de gimnasia artística"));
        assertEquals(2, Sports.rank("Eurocopa: Francia - Italia"));
        assertEquals(3, Sports.rank("Semifinal Copa del Rey: Betis - Osasuna"));
        assertEquals(4, Sports.rank("España - Japón"));
        assertEquals(5, Sports.rank("Roland Garros: Alcaraz - Sinner"));
        assertEquals(7, Sports.rank("ASOBAL: Bidasoa - Ciudad Real"));
        assertEquals(7, Sports.rank("Finalistas del año"));
    }

    @Test
    public void upcomingDropsWhatIsOverAndRepeatsThenSortsByImportanceAndTime() {
        List<Sports.Event> all = Arrays.asList(
                new Sports.Event("TDP.TV", 10 * MIN, 20 * MIN, "Over - Already"),
                new Sports.Event("TDP.TV", 300 * MIN, 400 * MIN, "Liga U: Real Madrid - Barcelona"),
                new Sports.Event("TDP.TV", 100 * MIN, 200 * MIN, "Liga U: Real Madrid - Barcelona"),
                new Sports.Event("La1.TV", 500 * MIN, 600 * MIN, "España - Japón"),
                new Sports.Event("TDP.TV", 50 * MIN, 150 * MIN, "ASOBAL: Bidasoa - Ciudad Real"));
        List<Sports.Event> out = Sports.upcoming(all, 60 * MIN, 8);
        assertEquals(3, out.size());
        assertEquals("España - Japón", out.get(0).title);
        assertEquals("ASOBAL: Bidasoa - Ciudad Real", out.get(1).title);
        assertEquals(100 * MIN, out.get(2).start);
        assertEquals(2, Sports.upcoming(all, 60 * MIN, 2).size());
    }

    @Test
    public void savedFormatRoundTrips() throws Exception {
        StringWriter out = new StringWriter();
        Sports.write(Arrays.asList(new Sports.Event("TDP.TV", 1, 2, "España - Japón")), out);
        List<Sports.Event> back = Sports.read(new BufferedReader(new StringReader(out + "broken\n")));
        assertEquals(1, back.size());
        assertEquals("TDP.TV", back.get(0).channel);
        assertEquals("España - Japón", back.get(0).title);
        assertEquals(2, back.get(0).stop);
    }
}
