package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The channels a person subscribes to on YouTube, through the Data API with an API key. That only works
 * while their subscriptions are public. The list is fetched at most once a day per person and kept, so a
 * failure keeps the last good one.
 */
final class YouTubeSubscriptions {
    private static final String TAG = "TvHome.YouTube";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** 50 per page: up to 1000 subscriptions. */
    private static final int MAX_PAGES = 20;

    private YouTubeSubscriptions() {
    }

    /** The saved list, fetched again when it is a day old. Blocks; call off the main thread. */
    static List<String> channels(Context ctx, String apiKey, String channelId) {
        File file = new File(ctx.getFilesDir(), "subscriptions-" + Integer.toHexString(channelId.hashCode()) + ".txt");
        List<String> saved = read(file);
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < DAY_MS) return saved;
        try {
            List<String> fresh = fetch(apiKey, channelId);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(String.join("\n", fresh).getBytes(StandardCharsets.UTF_8));
            }
            if (tmp.renameTo(file)) return fresh;
        } catch (Exception e) {
            Log.w(TAG, "subscriptions: " + e.getMessage());
            // Try again tomorrow, not on every refresh: the list may be private, or the key out of quota.
            try {
                if (!file.isFile()) new FileOutputStream(file).close();
                file.setLastModified(System.currentTimeMillis());
            } catch (IOException ignored) {
            }
        }
        return saved;
    }

    private static List<String> fetch(String apiKey, String channelId) throws Exception {
        List<String> out = new ArrayList<>();
        String page = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            String url = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&maxResults=50&channelId="
                    + URLEncoder.encode(channelId, "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8")
                    + (page == null ? "" : "&pageToken=" + URLEncoder.encode(page, "UTF-8"));
            page = parsePage(get(url), out);
            if (page == null) break;
        }
        return out;
    }

    /** Adds the subscribed channel ids of one page to out; returns the next page token, or null on the last page. */
    static String parsePage(String json, List<String> out) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray items = o.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                JSONObject snippet = item != null ? item.optJSONObject("snippet") : null;
                JSONObject resource = snippet != null ? snippet.optJSONObject("resourceId") : null;
                String id = resource != null ? resource.optString("channelId", "") : "";
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        String next = o.optString("nextPageToken", "");
        return next.isEmpty() ? null : next;
    }

    /** The fixed channels first, then the subscriptions, each channel once. */
    static List<String> merge(List<String> fixed, List<String> subscriptions) {
        Set<String> all = new LinkedHashSet<>(fixed);
        all.addAll(subscriptions);
        return new ArrayList<>(all);
    }

    /** The response body; errors are reported by status only, since the URL carries the key. */
    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                String body = c.getErrorStream() != null ? read(c.getErrorStream()) : "";
                throw new IOException("HTTP " + code + (body.contains("subscriptionForbidden")
                        ? ": the subscriptions of this channel are private; make them public in YouTube's settings" : ""));
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static List<String> read(File file) {
        if (!file.isFile()) return Collections.emptyList();
        try {
            List<String> out = new ArrayList<>();
            for (String line : read(new FileInputStream(file)).split("\n")) if (!line.trim().isEmpty()) out.add(line.trim());
            return out;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
