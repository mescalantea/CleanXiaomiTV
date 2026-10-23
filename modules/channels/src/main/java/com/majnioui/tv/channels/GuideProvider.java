package com.majnioui.tv.channels;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only "what is on now" for other apps, such as the launcher's shelf, so they don't download the guide
 * themselves: query content://com.majnioui.tv.channels.guide/now with the wanted tvg-ids as selectionArgs. One
 * row per known channel, in the order asked: tvg_id, name, logo, title, start, stop (title empty and times 0
 * when the guide has nothing on air). .../sports instead lists the coming big sports events on free public
 * channels (Sports), on air or later, one row each with the same columns. Only public TV listings are served. Stale caches are refreshed in the
 * background; the answer is always what is saved now.
 */
public class GuideProvider extends ContentProvider {
    static final String[] COLUMNS = {"tvg_id", "name", "logo", "title", "start", "stop"};
    private static final AtomicBoolean refreshing = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (getContext() == null) return out;
        boolean sports = "sports".equals(uri.getLastPathSegment());
        if (!sports && (selectionArgs == null || selectionArgs.length == 0)) return out;
        List<ChannelList.Channel> channels = ChannelList.load(getContext());
        Map<String, List<Epg.Programme>> guide = Epg.load(getContext());
        refreshIfStale(channels);
        Map<String, ChannelList.Channel> byId = new HashMap<>();
        for (ChannelList.Channel c : channels) byId.put(c.id, c);
        long now = System.currentTimeMillis();
        if (sports) {
            // Only events on a channel this app can play.
            for (Sports.Event e : Sports.upcoming(Sports.load(getContext()), now, 20)) {
                ChannelList.Channel c = byId.get(e.channel);
                if (c != null && out.getCount() < 8) out.addRow(new Object[] {c.id, c.name, c.logo, e.title, e.start, e.stop});
            }
            return out;
        }
        for (String id : selectionArgs) {
            ChannelList.Channel c = byId.get(id);
            if (c != null) out.addRow(row(c, guide.get(id), now));
        }
        return out;
    }

    /** One cursor row (see COLUMNS) for a channel and its programmes. */
    static Object[] row(ChannelList.Channel c, List<Epg.Programme> today, long now) {
        int on = Epg.onAir(today, now);
        Epg.Programme p = on >= 0 ? today.get(on) : null;
        return new Object[] {c.id, c.name, c.logo, p != null ? p.title : "", p != null ? p.start : 0L, p != null ? p.stop : 0L};
    }

    private void refreshIfStale(List<ChannelList.Channel> channels) {
        final android.content.Context ctx = getContext();
        boolean list = ChannelList.refreshDue(ctx), epg = Epg.refreshDue(ctx);
        if ((!list && !epg) || !refreshing.compareAndSet(false, true)) return;
        final Set<String> ids = new HashSet<>();
        for (ChannelList.Channel c : channels) if (!c.id.isEmpty()) ids.add(c.id);
        new Thread(() -> {
            try {
                if (list) ChannelList.refresh(ctx);
                if (epg) Epg.refresh(ctx, ids);
            } finally {
                refreshing.set(false);
            }
        }).start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
