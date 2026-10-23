package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A person's name and picture as on their Google account, read from their YouTube channel (one Data API call
 * for every profile), and kept for a week per channel so the profile button works offline and spends no quota.
 */
final class YouTubeIdentity {
    final String name;
    /** Picture URL, or empty. */
    final String photo;

    private static final String TAG = "TvHome.YouTube";
    private static final long WEEK_MS = 7 * 24 * 60 * 60 * 1000L;

    YouTubeIdentity(String name, String photo) {
        this.name = name;
        this.photo = photo;
    }

    /** By channel id, from the saved copies and, for the ones missing or a week old, the API. Blocks. */
    static Map<String, YouTubeIdentity> load(Context ctx, String apiKey, List<String> channelIds) {
        Map<String, YouTubeIdentity> out = new HashMap<>();
        List<String> due = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : channelIds) {
            File f = file(ctx, id);
            YouTubeIdentity saved = read(f);
            if (saved != null) out.put(id, saved);
            if (saved == null || now - f.lastModified() >= WEEK_MS) due.add(id);
        }
        if (due.isEmpty() || apiKey.isEmpty()) return out;
        try {
            Map<String, YouTubeIdentity> fresh = parse(YouTubeSubscriptions.get(
                    "https://www.googleapis.com/youtube/v3/channels?part=snippet&maxResults=50&id="
                            + URLEncoder.encode(String.join(",", due), "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8")));
            for (Map.Entry<String, YouTubeIdentity> e : fresh.entrySet()) {
                out.put(e.getKey(), e.getValue());
                save(file(ctx, e.getKey()), e.getValue());
            }
        } catch (Exception e) {
            Log.w(TAG, "channels: " + e.getMessage());
        }
        return out;
    }

    /** channels.list: each item's id, snippet.title and its largest thumbnail. */
    static Map<String, YouTubeIdentity> parse(String json) throws Exception {
        Map<String, YouTubeIdentity> out = new HashMap<>();
        JSONArray items = new JSONObject(json).optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject snippet = item != null ? item.optJSONObject("snippet") : null;
            String id = item != null ? item.optString("id", "") : "";
            if (snippet == null || id.isEmpty()) continue;
            JSONObject thumbs = snippet.optJSONObject("thumbnails");
            String photo = "";
            for (String size : new String[] {"high", "medium", "default"}) {
                JSONObject t = thumbs != null ? thumbs.optJSONObject(size) : null;
                if (t != null && t.optString("url", "").startsWith("https://")) {
                    photo = t.optString("url");
                    break;
                }
            }
            out.put(id, new YouTubeIdentity(snippet.optString("title", "").trim(), photo));
        }
        return out;
    }

    private static File file(Context ctx, String channelId) {
        return new File(ctx.getFilesDir(), "youtube-channel-" + Integer.toHexString(channelId.hashCode()) + ".txt");
    }

    private static YouTubeIdentity read(File f) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(data);
            String[] lines = new String(data, 0, Math.max(0, n), StandardCharsets.UTF_8).split("\n", -1);
            return lines.length < 2 ? null : new YouTubeIdentity(lines[0], lines[1]);
        } catch (IOException e) {
            return null;
        }
    }

    private static void save(File f, YouTubeIdentity identity) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((identity.name.replace('\n', ' ') + "\n" + identity.photo).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "could not save a channel");
    }
}
