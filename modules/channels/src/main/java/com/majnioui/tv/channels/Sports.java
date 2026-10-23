package com.majnioui.tv.channels;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Big sports events on free public channels, picked from the programme guide over its whole range (about
 * three days): live fixtures and events, not recaps, magazines or old matches. Kept in their own small file,
 * next to the guide, for the launcher's "Deportes en abierto" (GuideProvider).
 */
final class Sports {
    private static final String SAVED = "sports.txt";
    private static final long HOUR_MS = 60 * 60 * 1000L;

    /** Public broadcasters only; club channels (Real Madrid TV, Barça, Betis...) and private ones are left out. */
    static final Set<String> CHANNELS = new HashSet<>(Arrays.asList(
            "La1.TV", "La2.TV", "TDP.TV", "GOL.TV", "CanalSurA.TV", "AragonTV.TV", "Canarias.TV", "CMM.TV", "TV3.TV",
            "C33.TV", "Telemadrid.TV", "LaOtra.TV", "APUNT.TV", "Extremadura.TV", "TVGAL.TV", "TVG2.TV", "IB3.TV",
            "TPA.TV", "7RM.TV", "ETB1.TV", "ETB2.TV", "NaTV.TV", "TVR.TV"));

    /** "Team A - Team B", "España - Japón". */
    private static final Pattern FIXTURE = Pattern.compile("\\p{L}[\\p{L}.']*\\s+-\\s+\\p{L}");
    /** A date in the description marks an old match being shown again ("02/05/09, jornada 34"). */
    private static final Pattern OLD_DATE = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b|temporada \\d{4}");

    private static final String[] NOT_LIVE = {"resumen", "resumenes", "highlights", "historia", "historico", "previa",
            "post", "magacin", "magazine", "estudio", "conexion", "informativo", "reportaje", "deportes", "retro",
            "en accion", "en xogo", "redifusion", "la pachanga", "combates"};
    private static final String[] EVENTS = {"olimpic", "olimpiad", "jjoo", "paralimpic", "mundial", "campeonato del mundo",
            "campeonato de europa", "eurocopa", "europeo", "copa del rey", "supercopa", "champions", "final", "gran premio",
            "tour", "vuelta", "giro", "roland garros", "wimbledon", "us open", "open de australia", "copa davis",
            "seleccion", "liga f"};
    /** Most important first: each group's index is the event's rank; anything else ranks after all of them. */
    private static final String[][] RANKS = {
            {"olimpic", "olimpiad", "jjoo", "paralimpic"},
            {"mundial", "campeonato del mundo", "world championship"},
            {"eurocopa", "europeo", "campeonato de europa"},
            {"final"},
            {"seleccion", "espana -", "- espana"},
            {"roland garros", "wimbledon", "us open", "open de australia", "copa davis"},
            {"gran premio"}};

    static final class Event {
        final String channel, title;
        final long start, stop;

        Event(String channel, long start, long stop, String title) {
            this.channel = channel;
            this.start = start;
            this.stop = stop;
            this.title = title;
        }

        int rank() {
            return Sports.rank(title);
        }
    }

    private Sports() {
    }

    /** Whether this guide entry is a live sports event worth the shelf. */
    static boolean isEvent(String channel, String category, String title, String desc, long start, long stop) {
        if (!CHANNELS.contains(channel) || !"SPORTS".equals(category) || title == null) return false;
        String t = fold(title);
        for (String w : NOT_LIVE) if (contains(t, w, true)) return false;
        if (desc != null && OLD_DATE.matcher(fold(desc)).find()) return false;
        if (FIXTURE.matcher(title).find()) return true;
        // A keyword alone also names short magazine slots ("Paralímpicos", 10 minutes): ask for a real broadcast.
        if (stop - start < 45 * 60_000L) return false;
        for (String w : EVENTS) if (contains(t, w, false)) return true;
        return false;
    }

    static int rank(String title) {
        String t = fold(title);
        for (int i = 0; i < RANKS.length; i++) for (String w : RANKS[i]) if (contains(t, w, false)) return i;
        return RANKS.length;
    }

    /** Not over yet, each title once (its first showing), most important first, then by start; at most max. */
    static List<Event> upcoming(List<Event> events, long now, int max) {
        List<Event> sorted = new ArrayList<>(events);
        Collections.sort(sorted, (a, b) -> Long.compare(a.start, b.start));
        List<Event> out = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (Event e : sorted) if (e.stop > now && titles.add(fold(e.title))) out.add(e);
        Collections.sort(out, (a, b) -> a.rank() != b.rank() ? Integer.compare(a.rank(), b.rank()) : Long.compare(a.start, b.start));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Lower case, accents dropped, so "Selección" and "seleccion" match. */
    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The word at a word start; with prefix, also as the start of a longer word ("reportajes"). "final" also
     * counts inside "semifinal", and the Olympic stems in any ending ("olímpicos", "Olimpiadas").
     */
    private static boolean contains(String folded, String word, boolean prefix) {
        boolean stem = prefix || word.equals("olimpic") || word.equals("paralimpic") || word.equals("olimpiad");
        for (int i = folded.indexOf(word); i >= 0; i = folded.indexOf(word, i + 1)) {
            int end = i + word.length();
            boolean before = i == 0 || !Character.isLetter(folded.charAt(i - 1)) || word.equals("final") && folded.startsWith("semi", i - 4);
            boolean after = stem || end == folded.length() || !Character.isLetter(folded.charAt(end));
            if (before && after) return true;
        }
        return false;
    }

    static void save(Context ctx, List<Event> events) throws Exception {
        File tmp = new File(ctx.getFilesDir(), SAVED + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            write(events, out);
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), SAVED))) throw new java.io.IOException("could not save " + SAVED);
    }

    static List<Event> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), SAVED);
        if (!f.isFile()) return Collections.emptyList();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            return read(in);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static void write(List<Event> events, Writer out) throws Exception {
        for (Event e : events) out.write(e.channel + '\t' + e.start + '\t' + e.stop + '\t' + e.title + '\n');
    }

    static List<Event> read(BufferedReader in) throws Exception {
        List<Event> out = new ArrayList<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] f = line.split("\t", 4);
            if (f.length < 4) continue;
            try {
                out.add(new Event(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
