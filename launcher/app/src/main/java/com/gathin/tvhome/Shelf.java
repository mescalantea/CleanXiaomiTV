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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Home's content rows: YouTube (the latest video of each subscription), live TV (favourite channels, then
 * sports on free TV) and Jellyfin (continue watching, next up, new episodes). Each row is its own horizontal
 * list; all network work runs on the shelf's own threads, so a slow or unreachable source never holds Home up.
 */
final class Shelf {
    static final int ROW_YOUTUBE = 0, ROW_TV = 1, ROW_JELLYFIN = 2;
    private static final int[][] ROW_SOURCES = {
            {ShelfSections.YOUTUBE},
            {ShelfSections.LIVE, ShelfSections.SPORTS},
            {ShelfSections.RESUME, ShelfSections.NEXT_UP, ShelfSections.NEW}};

    interface Exits {
        void up(int row);

        void down(int row);

        /** A row's cards changed, or its first ones arrived. */
        void changed(int row);

        /** A card got focus (for the hero). */
        void focused(int row, ShelfItem item);

        /** OK was held on a card of this row. */
        void longPressed(int row);

        /** OK on a card; true when Home used it (moving a section) and the card should not open. */
        boolean clicked(int row);
    }

    private static final String TAG = "TvHome.Shelf";
    private static final long REFRESH_MS = 5 * 60_000L;
    /** One video per subscribed channel; a safety cap for very long subscription lists. */
    private static final int YOUTUBE_MAX = 300;

    private final Activity activity;
    private final Row[] rows;
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
    /** The hero's large pictures, apart from the posters so they don't push them out. */
    private final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final ShelfSections sections = new ShelfSections();
    private long refreshedAt = -REFRESH_MS;
    private boolean loading;
    /** When the YouTube items on screen were fetched (wall clock), 0 for never. */
    private long youtubeAt;
    /** One soft pulse shared by every skeleton on screen; runs only while one shows. */
    private final ValueAnimator shimmer = ValueAnimator.ofFloat(0.45f, 1f);

    /** @param lists one horizontal list per row, in ROW_ order */
    Shelf(Activity activity, RecyclerView[] lists, Config cfg, Config.Profile profile, Exits exits) {
        this.activity = activity;
        this.exits = exits;
        jellyfinUrl = cfg.jellyfinUrl;
        jellyfinKey = cfg.jellyfinApiKey;
        youtubeKey = cfg.youtubeApiKey;
        rows = new Row[lists.length];
        for (int i = 0; i < lists.length; i++) rows[i] = new Row(i, lists[i]);
        shimmer.setDuration(900);
        shimmer.setRepeatMode(ValueAnimator.REVERSE);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.addUpdateListener(a -> {
            boolean any = false;
            for (Row row : rows) {
                for (int i = 0; i < row.list.getChildCount(); i++) {
                    View sk = row.list.getChildAt(i).findViewById(R.id.shelfSkeleton);
                    if (sk != null && sk.getVisibility() == View.VISIBLE) {
                        sk.setAlpha((float) a.getAnimatedValue());
                        any = true;
                    }
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

    /** Something to show in this row, placeholders included. */
    boolean hasItems(int row) {
        return !rows[row].items.isEmpty();
    }

    /** Switches to another person's items, dropping the current ones and fetching at once. */
    void use(Config.Profile profile) {
        generation++;
        loading = false;
        refreshedAt = -REFRESH_MS;
        for (Row row : rows) row.focusedIndex = 0;
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
        boolean sports = installed(LiveTv.PACKAGE);
        if (!sports) sections.set(ShelfSections.SPORTS, Collections.emptyList());
        if (!jellyfin) {
            sections.set(ShelfSections.RESUME, Collections.emptyList());
            sections.set(ShelfSections.NEXT_UP, Collections.emptyList());
            sections.set(ShelfSections.NEW, Collections.emptyList());
        }
        if (!youtube) sections.set(ShelfSections.YOUTUBE, Collections.emptyList());
        long now = SystemClock.elapsedRealtime();
        if ((!jellyfin && !youtube && !live && !sports) || loading || now - refreshedAt < REFRESH_MS) {
            show();
            return;
        }
        refreshedAt = now;
        boolean feeds = youtube && !YouTubeCache.fresh(youtubeAt, System.currentTimeMillis());
        if (jellyfin) {
            sections.start(ShelfSections.RESUME);
            sections.start(ShelfSections.NEXT_UP);
            sections.start(ShelfSections.NEW);
        }
        if (feeds) sections.start(ShelfSections.YOUTUBE);
        if (live) sections.start(ShelfSections.LIVE);
        if (sports) sections.start(ShelfSections.SPORTS);
        show();
        loading = true;
        final List<String> channelsOnTv = tvChannels;
        final String episode = activity.getString(R.string.shelf_episode);
        final String user = jellyfinUser;
        final List<String> fixed = youtubeChannels;
        final String person = youtubeChannelId;
        final File cache = YouTubeCache.file(activity, profileName);
        final int gen = generation;
        // One task per source, so a server that does not answer only holds up its own sections.
        List<Runnable> tasks = new ArrayList<>();
        if (live || sports) tasks.add(() -> {
            if (live) post(gen, ShelfSections.LIVE, LiveTv.query(activity, channelsOnTv));
            if (sports) post(gen, ShelfSections.SPORTS, LiveTv.sports(activity));
        });
        if (jellyfin) tasks.add(() -> {
            List<ShelfItem> resume = jellyfin(() -> Jellyfin.resume(jellyfinUrl, jellyfinKey, user, episode));
            post(gen, ShelfSections.RESUME, resume);
            List<ShelfItem> next = jellyfin(() -> Jellyfin.nextUp(jellyfinUrl, jellyfinKey, user, episode));
            post(gen, ShelfSections.NEXT_UP, next);
            Set<String> started = new HashSet<>(), shown = new HashSet<>();
            for (List<ShelfItem> list : Arrays.asList(resume, next)) {
                if (list == null) continue;
                for (ShelfItem i : list) {
                    shown.add(i.id);
                    if (!i.channel.isEmpty()) started.add(i.channel);
                }
            }
            post(gen, ShelfSections.NEW, jellyfin(() -> Jellyfin.newEpisodes(jellyfinUrl, jellyfinKey, user, episode, started, shown)));
        });
        if (feeds) tasks.add(() -> {
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
        });
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(tasks.size());
        if (tasks.isEmpty()) loading = false;
        for (Runnable task : tasks) run(() -> {
            try {
                task.run();
            } finally {
                if (left.decrementAndGet() == 0) handler.post(() -> {
                    if (gen == generation) loading = false;
                });
            }
        });
    }

    /** Runs on the background threads, unless the shelf has been stopped. */
    private void run(Runnable task) {
        if (io.isShutdown()) return;
        try {
            io.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Stopped between the check and the call.
        }
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
        if (!installed(LiveTv.PACKAGE)) return;
        final List<String> ids = tvChannels;
        final int gen = generation;
        run(() -> {
            if (!ids.isEmpty()) {
                List<ShelfItem> now = LiveTv.query(activity, ids);
                if (now != null) post(gen, ShelfSections.LIVE, now);
            }
            List<ShelfItem> events = LiveTv.sports(activity);
            if (events != null) post(gen, ShelfSections.SPORTS, events);
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

    /** Applies every row's cards with as few changes as possible, keeping focus on the card the user is on. */
    private void show() {
        for (Row row : rows) row.show(sections.rows(ROW_SOURCES[row.id]));
    }

    /** Every row back to its first card, as after Home. */
    void toStart() {
        for (Row row : rows) {
            row.focusedIndex = 0;
            row.list.scrollToPosition(0);
        }
    }

    /** Puts focus on the row's card focused last, or its first one. */
    void focus(int row) {
        rows[row].focus();
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
        if (item.source == ShelfItem.Source.SPORT && item.progress < 0) {
            // Not on yet: say when and where.
            android.widget.Toast.makeText(activity, item.subtitle, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (item.source == ShelfItem.Source.LIVE || item.source == ShelfItem.Source.SPORT) {
            Intent watch = LiveTv.watch(activity, item.source == ShelfItem.Source.LIVE ? item.id : item.channel);
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
        run(() -> {
            Bitmap bmp = download(url, key, size);
            if (bmp != null) handler.post(() -> {
                posters.put(url, bmp);
                done.accept(bmp);
            });
        });
    }

    /** A card's large picture for the hero, about width wide; calls back on the main thread, with null on failure. */
    void picture(ShelfItem item, int width, Consumer<Bitmap> done) {
        final String url = item.backdrop;
        Bitmap cached = pictures.get(url);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        final String key = item.source == ShelfItem.Source.JELLYFIN ? jellyfinKey : null;
        // Not every YouTube video has a full-size still; the smaller one always exists.
        final String fallback = item.source == ShelfItem.Source.YOUTUBE ? "https://i.ytimg.com/vi/" + item.id + "/hqdefault.jpg" : null;
        run(() -> {
            Bitmap bmp = download(url, key, width);
            if (bmp == null && fallback != null) bmp = download(fallback, null, width);
            final Bitmap got = bmp;
            handler.post(() -> {
                if (got != null) pictures.put(url, got);
                done.accept(got);
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
        final int width = Math.round(200 * activity.getResources().getDisplayMetrics().density);
        run(() -> {
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

    /** One horizontal row of cards, with its own focus memory. */
    private final class Row {
        final int id;
        final RecyclerView list;
        final Adapter adapter = new Adapter(this);
        List<ShelfItem> items = Collections.emptyList();
        int focusedIndex;

        Row(int id, RecyclerView list) {
            this.id = id;
            this.list = list;
            list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
            list.setItemAnimator(null);
            list.setAdapter(adapter);
        }

        void show(List<ShelfItem> fresh) {
            List<ShelfItem> old = items;
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
                            && a.progress == b.progress && a.fresh == b.fresh && java.util.Objects.equals(a.image, b.image);
                }
            }, false).dispatchUpdatesTo(adapter);
            focusedIndex = Math.min(focusedIndex, Math.max(0, items.size() - 1));
            if (focusedAt != RecyclerView.NO_POSITION && gone(old.get(focusedAt), focusedAt, fresh)) {
                // If the focused card went away, take focus back to the same place once the row is laid out.
                list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (list.hasFocus() || items.isEmpty() || !list.isShown()) return;
                        focusedIndex = Math.min(focusedAt, items.size() - 1);
                        focus();
                    }
                });
            }
            exits.changed(id);
        }

        void focus() {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(focusedIndex);
            if (vh != null) {
                vh.itemView.findViewById(R.id.shelfCard).requestFocus();
                return;
            }
            // Not laid out (scrolled away): bring it into view, then focus it.
            final int at = focusedIndex;
            list.scrollToPosition(at);
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(at);
                if (h == null) h = list.findViewHolderForAdapterPosition(0);
                if (h != null) h.itemView.findViewById(R.id.shelfCard).requestFocus();
            });
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final Row row;

        Adapter(Row row) {
            this.row = row;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shelf, parent, false), row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ShelfItem item = row.items.get(position);
            boolean placeholder = item.source == ShelfItem.Source.PLACEHOLDER;
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
            boolean logo = item.source == ShelfItem.Source.LIVE || item.source == ShelfItem.Source.SPORT;
            boolean live = item.source == ShelfItem.Source.SPORT && item.progress >= 0;
            h.badge.setVisibility(live || item.fresh ? View.VISIBLE : View.GONE);
            h.badge.setText(live ? R.string.shelf_live : R.string.shelf_new);
            h.card.setContentDescription(item.source == ShelfItem.Source.SPORT
                    ? activity.getString(R.string.shelf_sports) + ": " + item.title + ", " + item.subtitle : null);
            float d = activity.getResources().getDisplayMetrics().density;
            int pad = logo ? Math.round(24 * d) : 0;
            h.image.setScaleType(logo ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            h.image.setPadding(pad * 2, pad, pad * 2, pad);
            if (item.image != null) loadPoster(item, h.image, h.skeleton);
        }

        @Override
        public int getItemCount() {
            return row.items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final View card, progress, skeleton;
        final TextView badge;
        final ImageView image;
        final TextView title, subtitle;

        Holder(View v, Row row) {
            super(v);
            card = v.findViewById(R.id.shelfCard);
            image = v.findViewById(R.id.shelfImage);
            title = v.findViewById(R.id.shelfTitle);
            subtitle = v.findViewById(R.id.shelfSubtitle);
            progress = v.findViewById(R.id.shelfProgress);
            skeleton = v.findViewById(R.id.shelfSkeleton);
            badge = v.findViewById(R.id.shelfBadge);
            card.setClipToOutline(true);
            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && !exits.clicked(row.id)) launch(row.items.get(pos));
            });
            card.setOnLongClickListener(view -> {
                exits.longPressed(row.id);
                return true;
            });
            card.setOnFocusChangeListener((view, focused) -> {
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) {
                    row.focusedIndex = pos;
                    exits.focused(row.id, row.items.get(pos));
                }
                view.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(160).start();
                title.animate().alpha(focused ? 1f : 0.7f).setDuration(160).start();
            });
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        exits.up(row.id);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        exits.down(row.id);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == row.items.size() - 1;
                    default:
                        return false;
                }
            });
        }
    }
}
