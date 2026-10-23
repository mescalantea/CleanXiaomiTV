package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "For you" row above the dock: Jellyfin's continue watching and next up, then the latest videos of
 * the configured YouTube channels. Hidden while it has nothing to show; all network work runs on its own
 * threads, so a slow or unreachable server never holds Home up.
 */
final class Shelf {
    interface Exits {
        void up();

        void down();

        /** The items changed, or the first ones arrived. */
        void changed();
    }

    private static final String TAG = "TvHome.Shelf";
    private static final long REFRESH_MS = 5 * 60_000L;
    private static final int YOUTUBE_MAX = 10;

    private final Activity activity;
    private final RecyclerView list;
    private final Exits exits;
    private final String jellyfinUrl, jellyfinKey, jellyfinUser;
    private final List<String> youtubeChannels;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Adapter adapter = new Adapter();
    private List<ShelfItem> items = Collections.emptyList();
    private long refreshedAt = -REFRESH_MS;
    private boolean loading;
    private int focusedIndex;

    Shelf(Activity activity, RecyclerView list, Config cfg, Exits exits) {
        this.activity = activity;
        this.list = list;
        this.exits = exits;
        jellyfinUrl = cfg.jellyfinUrl;
        jellyfinKey = cfg.jellyfinApiKey;
        jellyfinUser = cfg.jellyfinUserId;
        youtubeChannels = cfg.youtubeChannels;
        list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    /** Fetches again when the last fetch is older than a few minutes. */
    void refresh() {
        boolean jellyfin = !jellyfinUrl.isEmpty() && !jellyfinKey.isEmpty() && !jellyfinUser.isEmpty() && installed(Jellyfin.PACKAGE);
        boolean youtube = !youtubeChannels.isEmpty() && installed(YouTubeFeed.PACKAGE);
        if (!jellyfin && !youtube) {
            show(Collections.emptyList());
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (loading || now - refreshedAt < REFRESH_MS) return;
        refreshedAt = now;
        loading = true;
        final String episode = activity.getString(R.string.shelf_episode);
        io.execute(() -> {
            List<ShelfItem> fresh = new ArrayList<>();
            if (jellyfin) {
                try {
                    fresh.addAll(Jellyfin.fetch(jellyfinUrl, jellyfinKey, jellyfinUser, episode));
                } catch (Exception e) {
                    Log.w(TAG, "jellyfin: " + e.getClass().getSimpleName());
                }
            }
            if (youtube) fresh.addAll(YouTubeFeed.fetch(youtubeChannels, YOUTUBE_MAX));
            handler.post(() -> {
                loading = false;
                show(fresh);
            });
        });
    }

    private void show(List<ShelfItem> fresh) {
        if (fresh.isEmpty() && items.isEmpty()) return;
        items = fresh;
        focusedIndex = Math.min(focusedIndex, Math.max(0, items.size() - 1));
        adapter.notifyDataSetChanged();
        exits.changed();
    }

    /** Puts focus on the item focused last, or the first one. */
    void focus() {
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(focusedIndex);
        if (vh == null) vh = list.findViewHolderForAdapterPosition(0);
        if (vh != null) vh.itemView.findViewById(R.id.shelfCard).requestFocus();
    }

    void stop() {
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean installed(String pkg) {
        return activity.getPackageManager().getLaunchIntentForPackage(pkg) != null
                || activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    private void launch(ShelfItem item) {
        Intent direct, fallback;
        if (item.source == ShelfItem.Source.JELLYFIN) {
            direct = new Intent(Intent.ACTION_VIEW, Uri.parse(Jellyfin.uuid(item.id))).setClassName(Jellyfin.PACKAGE, Jellyfin.STARTUP);
            fallback = activity.getPackageManager().getLeanbackLaunchIntentForPackage(Jellyfin.PACKAGE);
        } else {
            Uri watch = Uri.parse(YouTubeFeed.watchUrl(item.id));
            direct = new Intent(Intent.ACTION_VIEW, watch).setPackage(YouTubeFeed.PACKAGE);
            fallback = new Intent(Intent.ACTION_VIEW, watch);
        }
        try {
            activity.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            if (fallback == null) return;
            try {
                activity.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    private void loadPoster(ShelfItem item, ImageView view) {
        Bitmap cached = posters.get(item.image);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        final String url = item.image;
        final boolean jellyfin = item.source == ShelfItem.Source.JELLYFIN;
        final int width = Math.round(160 * activity.getResources().getDisplayMetrics().density);
        io.execute(() -> {
            Bitmap bmp = download(url, jellyfin ? jellyfinKey : null, width);
            if (bmp == null) return;
            handler.post(() -> {
                posters.put(url, bmp);
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    /** The picture decoded at about the card's width; null on any failure. */
    private static Bitmap download(String url, String apiKey, int width) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (apiKey != null) c.setRequestProperty("Authorization", Jellyfin.authorization(apiKey));
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                data = out.toByteArray();
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shelf, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ShelfItem item = items.get(position);
            h.title.setText(item.title);
            h.subtitle.setText(item.subtitle);
            h.subtitle.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams lp = h.progress.getLayoutParams();
            lp.width = item.progress > 0 ? h.card.getLayoutParams().width * Math.min(100, item.progress) / 100 : 0;
            h.progress.setLayoutParams(lp);
            h.image.setImageDrawable(null);
            h.image.setTag(item.image);
            if (item.image != null) loadPoster(item, h.image);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final View card, progress;
        final ImageView image;
        final TextView title, subtitle;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.shelfCard);
            image = v.findViewById(R.id.shelfImage);
            title = v.findViewById(R.id.shelfTitle);
            subtitle = v.findViewById(R.id.shelfSubtitle);
            progress = v.findViewById(R.id.shelfProgress);
            card.setClipToOutline(true);
            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) launch(items.get(pos));
            });
            card.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) focusedIndex = pos;
                }
                view.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(160).start();
            });
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        exits.up();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        exits.down();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == items.size() - 1;
                    default:
                        return false;
                }
            });
        }
    }
}
