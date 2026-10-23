package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The YouTube part of the shelf as it was last fetched, one file per profile, so Home shows it at once
 * (also after a reboot) and only reads the feeds again once it is MAX_AGE_MS old.
 */
final class YouTubeCache {
    static final long MAX_AGE_MS = 30 * 60_000L;
    private static final String TAG = "TvHome.YouTube";

    private YouTubeCache() {
    }

    static File file(Context ctx, String profile) {
        return new File(ctx.getFilesDir(), "youtube-" + Integer.toHexString(profile.hashCode()) + ".txt");
    }

    static boolean fresh(long savedAt, long now) {
        return savedAt > 0 && now - savedAt >= 0 && now - savedAt < MAX_AGE_MS;
    }

    /** The saved items, or an empty list. */
    static List<ShelfItem> load(File file) {
        if (!file.isFile()) return Collections.emptyList();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return read(in);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static void save(File file, List<ShelfItem> items) {
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            write(items, out);
        } catch (Exception e) {
            Log.w(TAG, "cache: " + e);
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "could not save the cache");
    }

    /** One line per video: id, published time, picture, title, channel name, channel id and description, by tabs. */
    static void write(List<ShelfItem> items, Writer out) throws Exception {
        for (ShelfItem i : items) {
            out.write(i.id + '\t' + i.published + '\t' + (i.image == null ? "" : i.image) + '\t'
                    + flat(i.title) + '\t' + flat(i.subtitle) + '\t' + i.channel + '\t' + flat(i.description) + '\n');
        }
    }

    static List<ShelfItem> read(BufferedReader in) throws Exception {
        List<ShelfItem> out = new ArrayList<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] f = line.split("\t", 7);
            if (f.length < 5 || f[0].isEmpty()) continue;
            try {
                out.add(new ShelfItem(ShelfItem.Source.YOUTUBE, f[0], f[3], f[4], f[2].isEmpty() ? null : f[2], -1,
                        Long.parseLong(f[1]), f.length > 5 ? f[5] : "", f.length > 6 ? f[6] : "", YouTubeFeed.backdrop(f[0]), false));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String flat(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
