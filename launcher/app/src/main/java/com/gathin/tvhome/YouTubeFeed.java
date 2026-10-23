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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The latest videos of a few YouTube channels, from their public feeds (no account or key). */
final class YouTubeFeed {
    static final String PACKAGE = "com.google.android.youtube.tv";
    private static final String TAG = "TvHome.YouTube";

    private YouTubeFeed() {
    }

    /**
     * Each channel's latest video, handed to batch as its feed completes, on a worker thread; a channel whose
     * feed is empty, fails or is too slow is skipped. Eight feeds at a time and a minute in all, since
     * a list of subscriptions can hold a few hundred channels. Returns every batch together, newest first.
     * Blocks.
     */
    static List<ShelfItem> fetch(List<String> channels, Consumer<List<ShelfItem>> batch) {
        List<ShelfItem> all = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (String channel : channels) pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/feeds/videos.xml?channel_id="
                        + URLEncoder.encode(channel, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    XmlPullParser p = Xml.newPullParser();
                    p.setInput(in, "UTF-8");
                    List<ShelfItem> videos = latest(parse(p, channel));
                    if (!videos.isEmpty()) {
                        all.addAll(videos);
                        batch.accept(videos);
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "channel " + channel + ": " + e);
            }
        });
        pool.shutdown();
        try {
            pool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        synchronized (all) {
            return newest(all);
        }
    }

    /** The feed's newest video alone, or nothing for an empty feed. */
    static List<ShelfItem> latest(List<ShelfItem> feed) {
        List<ShelfItem> sorted = newest(feed);
        return sorted.isEmpty() ? sorted : new ArrayList<>(sorted.subList(0, 1));
    }

    /**
     * shown with the new videos added at its end, at most max in all; a video, or another video of a channel
     * already shown, is skipped, and nothing already shown moves.
     */
    static List<ShelfItem> append(List<ShelfItem> shown, List<ShelfItem> batch, int max) {
        List<ShelfItem> out = new ArrayList<>(shown);
        Set<String> ids = new HashSet<>(), channels = new HashSet<>();
        for (ShelfItem i : shown) {
            ids.add(i.id);
            channels.add(channelKey(i));
        }
        for (ShelfItem i : batch) {
            if (out.size() >= max) break;
            if (ids.add(i.id) && channels.add(channelKey(i))) out.add(i);
        }
        return out;
    }

    /** The channel id; the channel name only for videos saved before ids were kept. */
    private static String channelKey(ShelfItem i) {
        return i.channel.isEmpty() ? "name:" + i.subtitle : i.channel;
    }

    static List<ShelfItem> newest(List<ShelfItem> items) {
        List<ShelfItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(b.published, a.published));
        return sorted;
    }

    static List<ShelfItem> parse(XmlPullParser p) throws Exception {
        return parse(p, "");
    }

    /**
     * The entries of one channel's Atom feed; an entry without a valid video id is skipped.
     * @param channelId the channel the feed was asked for, used where an entry names none
     */
    static List<ShelfItem> parse(XmlPullParser p, String channelId) throws Exception {
        List<ShelfItem> out = new ArrayList<>();
        String id = null, title = "", channel = "", published = "", owner = channelId, description = "";
        boolean inEntry = false, inAuthor = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event == XmlPullParser.START_TAG) {
                String name = local(p.getName());
                if (name.equals("entry")) {
                    inEntry = true;
                    id = null;
                    title = channel = published = description = "";
                    owner = channelId;
                } else if (inEntry && name.equals("author")) {
                    inAuthor = true;
                } else if (inEntry && name.equals("channelId")) {
                    owner = p.nextText().trim();
                } else if (inEntry && name.equals("videoId")) {
                    id = p.nextText().trim();
                } else if (inEntry && name.equals("title") && !inAuthor) {
                    title = p.nextText().trim();
                } else if (inEntry && inAuthor && name.equals("name")) {
                    channel = p.nextText().trim();
                } else if (inEntry && name.equals("description")) {
                    description = p.nextText().trim();
                    // The hero shows two lines; the feed can carry pages of links.
                    if (description.length() > 300) description = description.substring(0, 300);
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
                                "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", -1, time(published), owner,
                                description, backdrop(id), false));
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

    /** The video's largest still, for the hero; not every video has one, see Home's hero. */
    static String backdrop(String id) {
        return "https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg";
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
