package com.gathin.tvhome;

import android.animation.ValueAnimator;
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
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    /** One video per subscribed channel; a safety cap for very long subscription lists. */
    private static final int YOUTUBE_MAX = 300;

    private final Activity activity;
    private final RecyclerView list;
    private final Exits exits;
    private final String jellyfinUrl, jellyfinKey, youtubeKey;
    private String profileName, jellyfinUser, youtubeChannelId;
    private List<String> youtubeChannels, tvChannels;
    /** Bumped on a profile switch, so a fetch still running for the previous profile is dropped. */
    private int generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Adapter adapter = new Adapter();
    private final ShelfSections sections = new ShelfSections();
    private List<ShelfItem> items = Collections.emptyList();
    private long refreshedAt = -REFRESH_MS;
    private boolean loading;
    private int focusedIndex;
    /** When the YouTube items on screen were fetched (wall clock), 0 for never. */
    private long youtubeAt;
    /** One soft pulse shared by every skeleton on screen; runs only while one shows. */
    private final ValueAnimator shimmer = ValueAnimator.ofFloat(0.45f, 1f);

    Shelf(Activity activity, RecyclerView list, Config cfg, Config.Profile profile, Exits exits) {
        this.activity = activity;
        this.list = list;
        this.exits = exits;
        jellyfinUrl = cfg.jellyfinUrl;
        jellyfinKey = cfg.jellyfinApiKey;
        youtubeKey = cfg.youtubeApiKey;
        list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        shimmer.setDuration(900);
        shimmer.setRepeatMode(ValueAnimator.REVERSE);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.addUpdateListener(a -> {
            boolean any = false;
            for (int i = 0; i < list.getChildCount(); i++) {
                View sk = list.getChildAt(i).findViewById(R.id.shelfSkeleton);
                if (sk != null && sk.getVisibility() == View.VISIBLE) {
                    sk.setAlpha((float) a.getAnimatedValue());
                    any = true;
                }
            }
            if (!any) shimmer.cancel();
        });
        load(profile);
    }

    /** The person's values, and their YouTube items as last saved. */
    private void load(Config.Profile profile) {
        profileName = profile.name;
        jellyfinUser = profile.jellyfinUserId;
        youtubeChannelId = profile.youtubeChannelId;
        youtubeChannels = profile.youtubeChannels;
        tvChannels = profile.tvChannels;
        File cache = YouTubeCache.file(activity, profileName);
        sections.set(ShelfSections.YOUTUBE, YouTubeCache.load(cache));
        youtubeAt = cache.isFile() ? cache.lastModified() : 0;
    }

    /** Something to show, placeholders included. */
    boolean hasItems() {
        return !items.isEmpty();
    }

    /** Switches to another person's items, dropping the current ones and fetching at once. */
    void use(Config.Profile profile) {
        generation++;
        loading = false;
        refreshedAt = -REFRESH_MS;
        focusedIndex = 0;
        sections.clear();
        load(profile);
        refresh();
    }

    /**
     * Fetches again when the last fetch is older than a few minutes; the YouTube feeds only when the saved
     * ones are older than half an hour. Each source replaces its own placeholders as soon as it arrives.
     */
    void refresh() {
        boolean jellyfin = !jellyfinUrl.isEmpty() && !jellyfinKey.isEmpty() && !jellyfinUser.isEmpty() && installed(Jellyfin.PACKAGE);
        boolean subscriptions = !youtubeKey.isEmpty() && !youtubeChannelId.isEmpty();
        boolean youtube = (subscriptions || !youtubeChannels.isEmpty()) && installed(YouTubeFeed.PACKAGE);
        boolean live = liveOn();
        if (!live) sections.set(ShelfSections.LIVE, Collections.emptyList());
        if (!jellyfin) {
            sections.set(ShelfSections.RESUME, Collections.emptyList());
            sections.set(ShelfSections.NEXT_UP, Collections.emptyList());
        }
        if (!youtube) sections.set(ShelfSections.YOUTUBE, Collections.emptyList());
        long now = SystemClock.elapsedRealtime();
        if ((!jellyfin && !youtube && !live) || loading || now - refreshedAt < REFRESH_MS) {
            show();
            return;
        }
        refreshedAt = now;
        boolean feeds = youtube && !YouTubeCache.fresh(youtubeAt, System.currentTimeMillis());
        if (jellyfin) {
            sections.start(ShelfSections.RESUME);
            sections.start(ShelfSections.NEXT_UP);
        }
        if (feeds) sections.start(ShelfSections.YOUTUBE);
        if (live) sections.start(ShelfSections.LIVE);
        show();
        loading = true;
        final List<String> channelsOnTv = tvChannels;
        final String episode = activity.getString(R.string.shelf_episode);
        final String user = jellyfinUser;
        final List<String> fixed = youtubeChannels;
        final String person = youtubeChannelId;
        final File cache = YouTubeCache.file(activity, profileName);
        final int gen = generation;
        io.execute(() -> {
            if (live) post(gen, ShelfSections.LIVE, LiveTv.query(activity, channelsOnTv));
            if (jellyfin) {
                post(gen, ShelfSections.RESUME, jellyfin(() -> Jellyfin.resume(jellyfinUrl, jellyfinKey, user, episode)));
                post(gen, ShelfSections.NEXT_UP, jellyfin(() -> Jellyfin.nextUp(jellyfinUrl, jellyfinKey, user, episode)));
            }
            if (feeds) {
                List<String> channels = subscriptions
                        ? YouTubeSubscriptions.merge(fixed, YouTubeSubscriptions.channels(activity, youtubeKey, person)) : fixed;
                // Videos only ever join at the end, so nothing moves under the user while feeds arrive.
                List<ShelfItem> videos = YouTubeFeed.fetch(channels, batch -> handler.post(() -> {
                    if (gen != generation) return;
                    sections.append(ShelfSections.YOUTUBE, batch, YOUTUBE_MAX);
                    show();
                }));
                long at = System.currentTimeMillis();
                // Newest first for the next start; this session keeps its order.
                if (!videos.isEmpty()) YouTubeCache.save(cache, YouTubeFeed.append(Collections.emptyList(), videos, YOUTUBE_MAX));
                handler.post(() -> {
                    if (gen != generation) return;
                    if (!videos.isEmpty()) youtubeAt = at;
                    sections.finish(ShelfSections.YOUTUBE);
                    show();
                });
            }
            handler.post(() -> {
                if (gen == generation) loading = false;
            });
        });
    }

    /** Whether the card at this place has no counterpart in the new rows (see areItemsTheSame). */
    private static boolean gone(ShelfItem item, int at, List<ShelfItem> fresh) {
        if (item.source == ShelfItem.Source.PLACEHOLDER) return at >= fresh.size();
        String key = ShelfSections.key(item);
        for (ShelfItem i : fresh) if (ShelfSections.key(i).equals(key)) return false;
        return true;
    }

    private boolean liveOn() {
        return !tvChannels.isEmpty() && installed(LiveTv.PACKAGE);
    }

    /** What is on the live channels now, again: called every minute so programmes and progress keep up. */
    void tick() {
        if (!liveOn()) return;
        final List<String> ids = tvChannels;
        final int gen = generation;
        io.execute(() -> {
            List<ShelfItem> now = LiveTv.query(activity, ids);
            if (now != null) post(gen, ShelfSections.LIVE, now);
        });
    }

    private interface Fetch {
        List<ShelfItem> run() throws Exception;
    }

    /** The items, or null when the server could not be reached. */
    private static List<ShelfItem> jellyfin(Fetch fetch) {
        try {
            return fetch.run();
        } catch (Exception e) {
            // The cause chain's classes say why (for example CertPathValidatorException under SSLHandshakeException)
            // without logging the server's address, which messages can carry.
            StringBuilder why = new StringBuilder(e.getClass().getSimpleName());
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) why.append(" < ").append(t.getClass().getSimpleName());
            Log.w(TAG, "jellyfin: " + why);
            return null;
        }
    }

    private void post(int gen, int section, List<ShelfItem> fresh) {
        handler.post(() -> {
            if (gen != generation) return;
            sections.set(section, fresh);
            show();
        });
    }

    /** Applies the sections' rows with as few changes as possible, keeping focus on the item the user is on. */
    private void show() {
        List<ShelfItem> old = items;
        List<ShelfItem> fresh = sections.rows();
        if (old.isEmpty() && fresh.isEmpty()) return;
        View focusedChild = list.getFocusedChild();
        int focusedAt = focusedChild != null ? list.getChildAdapterPosition(focusedChild) : RecyclerView.NO_POSITION;
        items = fresh;
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return fresh.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                // A placeholder turns into whatever lands at its place, so a focused one keeps its view.
                if (old.get(o).source == ShelfItem.Source.PLACEHOLDER) return o == n;
                return ShelfSections.key(old.get(o)).equals(ShelfSections.key(fresh.get(n)));
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                ShelfItem a = old.get(o), b = fresh.get(n);
                return a.source == b.source && a.id.equals(b.id) && a.title.equals(b.title) && a.subtitle.equals(b.subtitle)
                        && a.progress == b.progress && java.util.Objects.equals(a.image, b.image);
            }
        }, false).dispatchUpdatesTo(adapter);
        focusedIndex = Math.min(focusedIndex, Math.max(0, items.size() - 1));
        if (focusedAt != RecyclerView.NO_POSITION && gone(old.get(focusedAt), focusedAt, fresh)) {
            // If the focused card went away, take focus back to the same place once the row is laid out.
            list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (list.hasFocus() || items.isEmpty() || list.getVisibility() != View.VISIBLE) return;
                    focusedIndex = Math.min(focusedAt, items.size() - 1);
                    focus();
                }
            });
        }
        exits.changed();
    }

    /** Back to the first card, as after Home. */
    void toStart() {
        focusedIndex = 0;
        list.scrollToPosition(0);
    }

    /** Puts focus on the item focused last, or the first one. */
    void focus() {
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(focusedIndex);
        if (vh == null) vh = list.findViewHolderForAdapterPosition(0);
        if (vh != null) vh.itemView.findViewById(R.id.shelfCard).requestFocus();
    }

    void stop() {
        shimmer.cancel();
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean installed(String pkg) {
        return activity.getPackageManager().getLaunchIntentForPackage(pkg) != null
                || activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    private void launch(ShelfItem item) {
        if (item.source == ShelfItem.Source.PLACEHOLDER) return;
        if (item.source == ShelfItem.Source.LIVE) {
            Intent watch = LiveTv.watch(activity, item.id);
            if (watch == null) return;
            try {
                activity.startActivity(watch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
            return;
        }
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

    /**
     * A picture for the profile button: photo when given (an https URL), else the Jellyfin user's avatar (a
     * server without one answers 404). Calls back on the main thread, only when there is a picture.
     */
    void avatar(Config.Profile profile, String photo, int size, Consumer<Bitmap> done) {
        boolean jellyfin = !jellyfinUrl.isEmpty() && !jellyfinKey.isEmpty() && !profile.jellyfinUserId.isEmpty();
        if (photo.isEmpty() && !jellyfin) return;
        final String url = !photo.isEmpty() ? photo
                : jellyfinUrl.replaceAll("/+$", "") + "/Users/" + Uri.encode(profile.jellyfinUserId) + "/Images/Primary?maxHeight=" + size;
        final String key = photo.isEmpty() ? jellyfinKey : null;
        Bitmap cached = posters.get(url);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        io.execute(() -> {
            Bitmap bmp = download(url, key, size);
            if (bmp != null) handler.post(() -> {
                posters.put(url, bmp);
                done.accept(bmp);
            });
        });
    }

    private void loadPoster(ShelfItem item, ImageView view, View skeleton) {
        Bitmap cached = posters.get(item.image);
        if (cached != null) {
            view.setImageBitmap(cached);
            skeleton.setVisibility(View.GONE);
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
                if (!url.equals(view.getTag())) return;
                view.setImageBitmap(bmp);
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(180).withEndAction(() -> {
                    if (url.equals(view.getTag())) skeleton.setVisibility(View.GONE);
                }).start();
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
            boolean placeholder = item.source == ShelfItem.Source.PLACEHOLDER;
            h.scrim.setVisibility(placeholder ? View.GONE : View.VISIBLE);
            h.skeleton.setVisibility(placeholder || item.image != null ? View.VISIBLE : View.GONE);
            if (h.skeleton.getVisibility() == View.VISIBLE && !shimmer.isStarted()) shimmer.start();
            h.title.setText(item.title);
            h.subtitle.setText(item.subtitle);
            h.subtitle.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams lp = h.progress.getLayoutParams();
            lp.width = item.progress > 0 ? h.card.getLayoutParams().width * Math.min(100, item.progress) / 100 : 0;
            h.progress.setLayoutParams(lp);
            h.image.animate().cancel();
            h.image.setAlpha(1f);
            h.image.setImageDrawable(null);
            h.image.setTag(item.image);
            // Channel logos are shown whole, small, above the names; posters fill the card.
            boolean logo = item.source == ShelfItem.Source.LIVE;
            float d = activity.getResources().getDisplayMetrics().density;
            h.image.setScaleType(logo ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            h.image.setPadding(logo ? Math.round(36 * d) : 0, logo ? Math.round(10 * d) : 0,
                    logo ? Math.round(36 * d) : 0, logo ? Math.round(36 * d) : 0);
            if (item.image != null) loadPoster(item, h.image, h.skeleton);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final View card, progress, skeleton, scrim;
        final ImageView image;
        final TextView title, subtitle;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.shelfCard);
            image = v.findViewById(R.id.shelfImage);
            title = v.findViewById(R.id.shelfTitle);
            subtitle = v.findViewById(R.id.shelfSubtitle);
            progress = v.findViewById(R.id.shelfProgress);
            skeleton = v.findViewById(R.id.shelfSkeleton);
            scrim = v.findViewById(R.id.shelfScrim);
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
