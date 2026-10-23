package com.gathin.tvhome;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** The latest videos of a few YouTube channels, from their public feeds (no account or key). */
final class YouTubeFeed {
    static final String PACKAGE = "com.google.android.youtube.tv";
    private static final String TAG = "TvHome.YouTube";

    private YouTubeFeed() {
    }

    /** Newest first across the channels, at most max; a channel that fails is skipped. Blocks. */
    static List<ShelfItem> fetch(List<String> channels, int max) {
        List<ShelfItem> all = new ArrayList<>();
        for (String channel : channels) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/feeds/videos.xml?channel_id="
                        + URLEncoder.encode(channel, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    XmlPullParser p = Xml.newPullParser();
                    p.setInput(in, "UTF-8");
                    all.addAll(parse(p));
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "channel " + channel + ": " + e);
            }
        }
        return newest(all, max);
    }

    static List<ShelfItem> newest(List<ShelfItem> items, int max) {
        List<ShelfItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(b.published, a.published));
        return sorted.size() > max ? new ArrayList<>(sorted.subList(0, max)) : sorted;
    }

    /** The entries of one channel's Atom feed; an entry without a valid video id is skipped. */
    static List<ShelfItem> parse(XmlPullParser p) throws Exception {
        List<ShelfItem> out = new ArrayList<>();
        String id = null, title = "", channel = "", published = "";
        boolean inEntry = false, inAuthor = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event == XmlPullParser.START_TAG) {
                String name = local(p.getName());
                if (name.equals("entry")) {
                    inEntry = true;
                    id = null;
                    title = channel = published = "";
                } else if (inEntry && name.equals("author")) {
                    inAuthor = true;
                } else if (inEntry && name.equals("videoId")) {
                    id = p.nextText().trim();
                } else if (inEntry && name.equals("title") && !inAuthor) {
                    title = p.nextText().trim();
                } else if (inEntry && inAuthor && name.equals("name")) {
                    channel = p.nextText().trim();
                } else if (inEntry && name.equals("published")) {
                    published = p.nextText().trim();
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = local(p.getName());
                if (name.equals("author")) {
                    inAuthor = false;
                } else if (name.equals("entry")) {
                    inEntry = false;
                    if (id != null && isVideoId(id)) {
                        out.add(new ShelfItem(ShelfItem.Source.YOUTUBE, id, title, channel,
                                "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", -1, time(published)));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isVideoId(String id) {
        if (id.isEmpty() || id.length() > 20) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) && c < 128) && c != '-' && c != '_') return false;
        }
        return true;
    }

    static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    /** "yt:videoId" and "videoId" alike, whether or not the parser processes namespaces. */
    private static String local(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static long time(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
