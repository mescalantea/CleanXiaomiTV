package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Continue watching" and "Next up" from a Jellyfin server, as shelf items. */
final class Jellyfin {
    static final String PACKAGE = "org.jellyfin.androidtv";
    static final String STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity";
    private static final int LIMIT = 12;

    private Jellyfin() {
    }

    /** "Continue watching". Blocks; call off the main thread. */
    static List<ShelfItem> resume(String server, String apiKey, String userId, String episodeFormat) throws Exception {
        String base = base(server);
        return parse(get(base + "/Users/" + URLEncoder.encode(userId, "UTF-8") + "/Items/Resume?Limit=" + LIMIT
                + "&MediaTypes=Video&Fields=PrimaryImageAspectRatio", apiKey), base, episodeFormat);
    }

    /**
     * "Next up" episodes. Blocks; call off the main thread.
     * @param episodeFormat season and episode numbers, e.g. "S%1$dE%2$d"
     */
    static List<ShelfItem> nextUp(String server, String apiKey, String userId, String episodeFormat) throws Exception {
        String base = base(server);
        return parse(get(base + "/Shows/NextUp?userId=" + URLEncoder.encode(userId, "UTF-8") + "&Limit=" + LIMIT, apiKey),
                base, episodeFormat);
    }

    private static String base(String server) {
        return server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
    }

    /** The Authorization header for an API key; also sent with image requests. */
    static String authorization(String apiKey) {
        return "MediaBrowser Token=\"" + apiKey + "\"";
    }

    /** Items of a Jellyfin query result ({"Items": [...]}); entries without an id are skipped. */
    static List<ShelfItem> parse(String json, String base, String episodeFormat) throws Exception {
        List<ShelfItem> out = new ArrayList<>();
        JSONArray items = new JSONObject(json).optJSONArray("Items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("Id", "");
            if (id.isEmpty()) continue;
            String name = o.optString("Name", "");
            String title = name, subtitle = "";
            if ("Episode".equals(o.optString("Type")) && o.has("SeriesName")) {
                title = o.optString("SeriesName");
                String number = o.has("ParentIndexNumber") && o.has("IndexNumber")
                        ? String.format(Locale.ROOT, episodeFormat, o.optInt("ParentIndexNumber"), o.optInt("IndexNumber")) : "";
                subtitle = number.isEmpty() ? name : name.isEmpty() ? number : number + " · " + name;
            }
            JSONObject userData = o.optJSONObject("UserData");
            int progress = userData != null && userData.has("PlayedPercentage")
                    ? (int) Math.round(userData.optDouble("PlayedPercentage")) : -1;
            out.add(new ShelfItem(ShelfItem.Source.JELLYFIN, id, title, subtitle, image(o, base), progress, 0));
        }
        return out;
    }

    /** A wide picture for the shelf: the episode still, else a thumb or backdrop of the item or its parent. */
    static String image(JSONObject o, String base) {
        JSONObject tags = o.optJSONObject("ImageTags");
        String id = o.optString("Id");
        if ("Episode".equals(o.optString("Type")) && tags != null && tags.has("Primary")) return url(base, id, "Primary");
        if (tags != null && tags.has("Thumb")) return url(base, id, "Thumb");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        if (backdrops != null && backdrops.length() > 0) return url(base, id, "Backdrop");
        if (!o.optString("ParentThumbItemId").isEmpty()) return url(base, o.optString("ParentThumbItemId"), "Thumb");
        JSONArray parentBackdrops = o.optJSONArray("ParentBackdropImageTags");
        if (!o.optString("ParentBackdropItemId").isEmpty() && parentBackdrops != null && parentBackdrops.length() > 0) {
            return url(base, o.optString("ParentBackdropItemId"), "Backdrop");
        }
        if (tags != null && tags.has("Primary")) return url(base, id, "Primary");
        return null;
    }

    private static String url(String base, String id, String type) {
        return base + "/Items/" + id + "/Images/" + type + "?maxHeight=360";
    }

    /** Jellyfin's 32-hex ids as the dashed UUID the Jellyfin TV app parses; anything else unchanged. */
    static String uuid(String id) {
        if (id.length() != 32) return id;
        for (int i = 0; i < 32; i++) if (Character.digit(id.charAt(i), 16) < 0) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20);
    }

    private static String get(String url, String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", authorization(apiKey));
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
