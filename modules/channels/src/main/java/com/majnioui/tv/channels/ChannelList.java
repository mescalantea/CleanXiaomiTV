package com.majnioui.tv.channels;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The channels come from TDTChannels' public list (res/values/config.xml, sources), fetched once a day, in
 * its order: Spanish national and regional groups first, international ones after. The list is saved, so
 * the app starts with the last good links and still works when it can't be reached; before the first fetch
 * it falls back to res/raw/channels.m3u, a snapshot of the Spanish channels.
 */
final class ChannelList {
    private static final String TAG = "Channels";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final String SAVED = "channels.m3u";
    private static final Pattern ATTR = Pattern.compile("([a-z-]+)=\"([^\"]*)\"");

    static final class Channel {
        final String id, name, group;
        String logo, url, userAgent, referrer;

        Channel(String id, String name, String group, String logo, String url, String userAgent, String referrer) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.url = url;
            this.userAgent = userAgent;
            this.referrer = referrer;
        }
    }

    private ChannelList() {
    }

    /** The saved list if there is one, else the bundled one. Fast; safe on the main thread. */
    static List<Channel> load(Context ctx) {
        File saved = new File(ctx.getFilesDir(), SAVED);
        try {
            if (saved.isFile()) {
                List<Channel> list = parse(new String(readAll(new FileInputStream(saved)), StandardCharsets.UTF_8));
                if (!list.isEmpty()) return list;
            }
        } catch (Exception e) {
            Log.w(TAG, "saved list: " + e);
        }
        return bundled(ctx);
    }

    static List<Channel> bundled(Context ctx) {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.channels)) {
            return parse(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "bundled list: " + e);
            return new ArrayList<>();
        }
    }

    static boolean refreshDue(Context ctx) {
        File saved = new File(ctx.getFilesDir(), SAVED);
        return !saved.isFile() || System.currentTimeMillis() - saved.lastModified() > DAY_MS;
    }

    /**
     * The channels of the public lists, in their order; saves and returns them, or null when no list could be
     * fetched. Blocking: run off the main thread.
     */
    static synchronized List<Channel> refresh(Context ctx) {
        // One fetch at a time (the app and its guide provider both ask); a caller that waited gets the saved list.
        if (!refreshDue(ctx)) return load(ctx);
        List<Channel> fetched = new ArrayList<>();
        for (String src : ctx.getResources().getStringArray(R.array.sources)) {
            try {
                fetched.addAll(parse(new String(fetch(src), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Log.w(TAG, "source " + src + ": " + e);
            }
        }
        List<Channel> list = unique(fetched);
        if (list.isEmpty()) return null;
        // Written aside and renamed, so a reader never sees half a list.
        File tmp = new File(ctx.getFilesDir(), SAVED + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(write(list).getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(new File(ctx.getFilesDir(), SAVED))) Log.w(TAG, "could not save the list");
        } catch (Exception e) {
            Log.w(TAG, "save: " + e);
        }
        return list;
    }

    /**
     * One entry per channel, the first listed: the lists repeat a channel with backup links, and the saved
     * last channel is found by its id.
     */
    static List<Channel> unique(List<Channel> channels) {
        Map<String, Channel> byKey = new LinkedHashMap<>();
        for (Channel c : channels) {
            if (c.url.isEmpty()) continue;
            String key = c.id.isEmpty() ? c.name : c.id;
            if (!byKey.containsKey(key)) byKey.put(key, c);
        }
        return new ArrayList<>(byKey.values());
    }

    /** M3U with #EXTINF attributes (tvg-id, tvg-logo, group-title) and optional #EXTVLCOPT headers. */
    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.startsWith("#EXTINF")) continue;
            int comma = l.lastIndexOf("\",");
            comma = comma >= 0 ? comma + 1 : l.indexOf(',');
            if (comma < 0) continue;
            String name = l.substring(comma + 1).trim();
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher m = ATTR.matcher(l.substring(0, comma));
            while (m.find()) attrs.put(m.group(1), m.group(2));
            String ua = null, ref = null, url = "";
            for (int j = i + 1; j < lines.length && j < i + 8; j++) {
                String x = lines[j].trim();
                if (x.startsWith("#EXTVLCOPT:http-user-agent=")) ua = x.substring(27);
                else if (x.startsWith("#EXTVLCOPT:http-referrer=")) ref = x.substring(25);
                else if (x.startsWith("#EXTINF")) break;
                else if (!x.isEmpty() && !x.startsWith("#")) {
                    url = x;
                    break;
                }
            }
            out.add(new Channel(value(attrs, "tvg-id"), name, value(attrs, "group-title"),
                    value(attrs, "tvg-logo"), url, ua, ref));
        }
        return out;
    }

    private static String write(List<Channel> list) {
        StringBuilder b = new StringBuilder("#EXTM3U\n");
        for (Channel c : list) {
            b.append("#EXTINF:-1 tvg-id=\"").append(c.id).append("\" tvg-logo=\"").append(c.logo)
                    .append("\" group-title=\"").append(c.group).append("\",").append(c.name).append('\n');
            if (c.userAgent != null) b.append("#EXTVLCOPT:http-user-agent=").append(c.userAgent).append('\n');
            if (c.referrer != null) b.append("#EXTVLCOPT:http-referrer=").append(c.referrer).append('\n');
            b.append(c.url).append('\n');
        }
        return b.toString();
    }

    private static String value(Map<String, String> attrs, String key) {
        String v = attrs.get(key);
        return v == null ? "" : v.trim();
    }

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
