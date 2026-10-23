package com.majnioui.tv.channels;

import android.content.Context;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Today's programmes per channel from TDTChannels' XMLTV guide, keyed by the channel's tvg-id (the guide
 * uses the same ids as the list). The guide is about 5 MB unpacked, so it is read as a stream and only the
 * titles in the window (two hours back to the end of the day) of the listed channels are kept, in a small
 * file that is fetched again once a day.
 */
final class Epg {
    static final String URL = "https://www.tdtchannels.com/epg/TV.xml.gz";
    private static final String TAG = "Channels.Epg";
    private static final String SAVED = "epg.txt";
    private static final long HOUR_MS = 60 * 60 * 1000L;

    static final class Programme {
        final long start, stop;
        final String title;

        Programme(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private Epg() {
    }

    /** From two hours ago to the end of today, and at least six hours ahead, so "next" exists late at night. */
    static long[] window(long now, TimeZone tz) {
        Calendar end = Calendar.getInstance(tz);
        end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        end.add(Calendar.DAY_OF_MONTH, 1);
        return new long[] {now - 2 * HOUR_MS, Math.max(end.getTimeInMillis(), now + 6 * HOUR_MS)};
    }

    /** The programmes of the given channels that overlap [from, to), each channel's sorted by start. */
    static Map<String, List<Programme>> parse(XmlPullParser p, Set<String> ids, long from, long to) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        Map<String, List<Programme>> out = new HashMap<>();
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event != XmlPullParser.START_TAG || !"programme".equals(p.getName())) continue;
            String channel = p.getAttributeValue(null, "channel");
            if (channel == null || !ids.contains(channel)) continue;
            long start = time(format, p.getAttributeValue(null, "start"));
            long stop = time(format, p.getAttributeValue(null, "stop"));
            if (start < 0 || stop <= start || stop <= from || start >= to) continue;
            String title = null;
            int depth = p.getDepth();
            while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.END_TAG && p.getDepth() == depth) break;
                if (event == XmlPullParser.START_TAG && title == null && "title".equals(p.getName())) {
                    title = clean(p.nextText());
                }
            }
            if (title == null || title.isEmpty()) continue;
            List<Programme> list = out.get(channel);
            if (list == null) out.put(channel, list = new ArrayList<>());
            list.add(new Programme(start, stop, title));
        }
        for (List<Programme> list : out.values()) Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** XMLTV time, "20261023054900 +0000"; -1 when it can't be read. */
    static long time(SimpleDateFormat format, String value) {
        if (value == null) return -1;
        Date d = format.parse(value.trim(), new ParsePosition(0));
        return d == null ? -1 : d.getTime();
    }

    /** The programme on air at now, or -1. */
    static int onAir(List<Programme> list, long now) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            Programme p = list.get(i);
            if (p.start <= now && now < p.stop) return i;
        }
        return -1;
    }

    /** The first programme that starts after now, or -1. */
    static int next(List<Programme> list, long now) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).start > now) return i;
        return -1;
    }

    /** True when the saved guide is missing or was fetched on another day. */
    static boolean refreshDue(Context ctx) {
        File f = new File(ctx.getFilesDir(), SAVED);
        if (!f.isFile()) return true;
        Calendar saved = Calendar.getInstance();
        saved.setTimeInMillis(f.lastModified());
        Calendar today = Calendar.getInstance();
        return saved.get(Calendar.YEAR) != today.get(Calendar.YEAR)
                || saved.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR);
    }

    /** Downloads and saves the guide for these channels; null on failure. Blocking. */
    static Map<String, List<Programme>> refresh(Context ctx, Set<String> ids) {
        long[] w = window(System.currentTimeMillis(), TimeZone.getDefault());
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            Map<String, List<Programme>> guide;
            try (InputStream in = new GZIPInputStream(c.getInputStream())) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(in, "UTF-8");
                guide = parse(p, ids, w[0], w[1]);
            } finally {
                c.disconnect();
            }
            File tmp = new File(ctx.getFilesDir(), SAVED + ".tmp");
            try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                write(guide, out);
            }
            if (!tmp.renameTo(new File(ctx.getFilesDir(), SAVED))) Log.w(TAG, "could not save the guide");
            return guide;
        } catch (Exception e) {
            Log.w(TAG, "guide: " + e);
            return null;
        }
    }

    /** The saved guide, or an empty one. */
    static Map<String, List<Programme>> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), SAVED);
        if (!f.isFile()) return Collections.emptyMap();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            return read(in);
        } catch (Exception e) {
            Log.w(TAG, "saved guide: " + e);
            return Collections.emptyMap();
        }
    }

    /** One line per programme: id, start, stop and title, separated by tabs. */
    static void write(Map<String, List<Programme>> guide, Writer out) throws Exception {
        for (Map.Entry<String, List<Programme>> e : guide.entrySet()) {
            for (Programme p : e.getValue()) {
                out.write(e.getKey() + '\t' + p.start + '\t' + p.stop + '\t' + p.title + '\n');
            }
        }
    }

    static Map<String, List<Programme>> read(BufferedReader in) throws Exception {
        Map<String, List<Programme>> out = new HashMap<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] f = line.split("\t", 4);
            if (f.length < 4) continue;
            try {
                List<Programme> list = out.get(f[0]);
                if (list == null) out.put(f[0], list = new ArrayList<>());
                list.add(new Programme(Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Titles on one line, so they fit the saved format. */
    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
