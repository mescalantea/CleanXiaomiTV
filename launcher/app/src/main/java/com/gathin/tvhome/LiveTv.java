package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The shelf's "En directo" cards: favourite live channels with what is on now, read from the Channels app's
 * guide (its GuideProvider), so the launcher never downloads the programme guide itself.
 */
final class LiveTv {
    static final String PACKAGE = "com.majnioui.tv.channels";
    private static final String NOW = "content://com.majnioui.tv.channels.guide/now";
    /** Extra the Channels app reads to start on a channel. */
    static final String EXTRA_TVG_ID = "tvg_id";
    private static final String TAG = "TvHome.Live";

    private LiveTv() {
    }

    /** The channels in the order asked, or null when the Channels app can't be asked. Blocks. */
    static List<ShelfItem> query(Context ctx, List<String> ids) {
        List<ShelfItem> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(NOW), null, null, ids.toArray(new String[0]), null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                out.add(item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), now));
            }
        } catch (Exception e) {
            Log.w(TAG, "guide: " + e);
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

    /** Opens the Channels app on this channel, or nothing when it is missing. */
    static Intent watch(Context ctx, String id) {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(PACKAGE);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (i == null) return null;
        return i.putExtra(EXTRA_TVG_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
