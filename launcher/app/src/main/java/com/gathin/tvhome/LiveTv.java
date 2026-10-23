package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The shelf's "En directo" cards: favourite live channels with what is on now, read from the Channels app's
 * guide (its GuideProvider), so the launcher never downloads the programme guide itself.
 */
final class LiveTv {
    static final String PACKAGE = "com.majnioui.tv.channels";
    private static final String AUTHORITY = "com.majnioui.tv.channels.guide";
    private static final String NOW = "content://" + AUTHORITY + "/now";
    private static final String SPORTS = "content://" + AUTHORITY + "/sports";
    /** Extra the Channels app reads to start on a channel. */
    static final String EXTRA_TVG_ID = "tvg_id";
    private static final String TAG = "TvHome.Live";

    private LiveTv() {
    }

    /** The channels in the order asked, or null when the Channels app can't be asked. Blocks. */
    static List<ShelfItem> query(Context ctx, List<String> ids) {
        return query(ctx, NOW, ids.toArray(new String[0]), false);
    }

    /** The coming big sports events on free channels ("Deportes en abierto"), or null as above. Blocks. */
    static List<ShelfItem> sports(Context ctx) {
        return query(ctx, SPORTS, null, true);
    }

    private static List<ShelfItem> query(Context ctx, String uri, String[] ids, boolean sports) {
        List<ShelfItem> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        // Only the Channels app's own guide: another app could claim the authority first.
        android.content.pm.ProviderInfo provider = ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0);
        if (provider == null || !PACKAGE.equals(provider.packageName)) return null;
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(uri), null, null, ids, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                out.add(sports ? sport(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                        now, Locale.getDefault(), TimeZone.getDefault())
                        : item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), now));
            }
        } catch (Exception e) {
            Log.w(TAG, "guide: " + e.getClass().getSimpleName());
            return null;
        }
        return out;
    }

    /** A card: the channel's name and logo, and the programme on air with how far it has got. */
    static ShelfItem item(String id, String name, String logo, String title, long start, long stop, long now) {
        int progress = start > 0 && stop > start && now >= start && now < stop ? (int) ((now - start) * 100 / (stop - start)) : -1;
        return new ShelfItem(ShelfItem.Source.LIVE, id, name, title == null ? "" : title,
                logo == null || logo.isEmpty() ? null : logo, progress, 0);
    }

    /**
     * A sports event: its title, then "Sáb 21:00 · La 1" (day and time in this locale and zone) and the channel's
     * logo; while it is on, its progress, which also marks it as live.
     */
    static ShelfItem sport(String channel, String name, String logo, String title, long start, long stop, long now,
                           Locale locale, TimeZone zone) {
        java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("EEE HH:mm", locale);
        when.setTimeZone(zone);
        String day = when.format(new java.util.Date(start)).replace(".", "");
        day = day.isEmpty() ? day : day.substring(0, 1).toUpperCase(locale) + day.substring(1);
        int progress = now >= start && now < stop ? (int) ((now - start) * 100 / (stop - start)) : -1;
        return new ShelfItem(ShelfItem.Source.SPORT, channel + "@" + start, title == null ? "" : title, day + " · " + name,
                logo == null || logo.isEmpty() ? null : logo, progress, start, channel);
    }

    /** Opens the Channels app on this channel, or nothing when it is missing. */
    static Intent watch(Context ctx, String id) {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(PACKAGE);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (i == null) return null;
        return i.putExtra(EXTRA_TVG_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
