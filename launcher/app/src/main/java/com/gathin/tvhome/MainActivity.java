package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Home, Google TV style: a hero for the focused card over a list of titled sections (YouTube, live TV, Jellyfin,
 * the apps grid) that the owner can reorder; the focused card zooms a little with a white outline.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row of the apps grid (res/values/config.xml, grid_columns, or the Settings menu). Set in onCreate. */
    static int GRID_COLUMNS = 5;
    /** Section keys, saved in this order (Config.sectionOrder). */
    static final String SECTION_YOUTUBE = "youtube", SECTION_TV = "tv", SECTION_JELLYFIN = "jellyfin", SECTION_APPS = "apps";
    /** Where the focused section's top sits, as a share of the screen's height. */
    private static final float ANCHOR = 0.56f;
    private static final long MINUTE_MS = 60_000L;
    private static final long WEATHER_MS = 30 * MINUTE_MS;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private TextView greeting;
    private TextView reorderHint;
    private View btnInputs;
    /** The profile now chosen (its private.json name, the stable key). */
    private String currentProfile = "";
    /** Each YouTube channel's owner name and picture, by channel id; empty until read. */
    private java.util.Map<String, YouTubeIdentity> identities = Collections.emptyMap();
    /** The top button focused last, where Up from the shelf lands. */
    private View lastPill;
    /** Null when now_playing is off. */
    private NowPlayingCard nowCard;
    private Shelf shelf;
    /** Home's sections, in the owner's order. */
    private final List<Section> sections = new ArrayList<>();
    private LinearLayout sectionList;
    /** Home was pressed in another app: the first card gets focus when Home shows. */
    private boolean homePressed;
    /** The section being moved (hold OK on one of its cards), or null. */
    private Section moving;
    private View orderPanel;
    private final java.util.Map<Section, View> orderRows = new java.util.HashMap<>();
    private View hero, heroImage, heroScrim;
    private TextView heroTitle, heroSource, heroDescription;
    /** The card the hero shows, so a picture that arrives late for another card is dropped. */
    private ShelfItem heroItem;
    private boolean homePhotoOn;
    /** The three-hour slot (yyyy-MM-dd/h) the home photo is for; null until one is shown. */
    private String photoSlot;
    /** Hours a home photo stays before the next one. */
    private static final int PHOTO_HOURS = 3;
    /** Null when now_playing is off. */
    private NowPlaying nowPlaying;
    private NowPlaying.Track track;
    private TextView btnWifi;
    private TextView weather;
    private String weatherUrl;
    private String weatherPlace;
    /** When the weather was last fetched (elapsedRealtime), successfully or not. */
    private long weatherAt = -WEATHER_MS;
    private boolean weatherLoading;
    private String vpnApp;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** The owner's app order (AppOrder); dock_apps lead it until one is saved. */
    private List<String> appOrder = Collections.emptyList();
    /** Non-modal, on-home-screen reorder mode entered by holding OK on a tile. */
    private boolean reorderMode;
    private int reorderPosition = -1;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs dropdown or the Settings menu while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWeather();
            if (homePhotoOn) loadHomePhoto();   // a new photo when the next slot starts
            shelf.tick();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        GRID_COLUMNS = cfg.gridColumns;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        homePhotoOn = cfg.homePhoto && cfg.wallpaper == null;
        hero = findViewById(R.id.hero);
        heroImage = findViewById(R.id.heroImage);
        heroScrim = findViewById(R.id.heroScrim);
        heroTitle = findViewById(R.id.heroTitle);
        heroSource = findViewById(R.id.heroSource);
        heroDescription = findViewById(R.id.heroDescription);
        sectionList = findViewById(R.id.sections);
        reorderHint = findViewById(R.id.reorderHint);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        vpnApp = cfg.vpnApp;
        weather = findViewById(R.id.weather);
        weatherPlace = cfg.weatherPlace;
        if (!cfg.weatherLatitude.isEmpty() && !cfg.weatherLongitude.isEmpty()) {
            weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + cfg.weatherLatitude
                    + "&longitude=" + cfg.weatherLongitude + "&current=temperature_2m,weather_code,is_day&timezone=auto";
        }
        appOrder = AppOrder.load(this, cfg.dockApps);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        grid.setItemAnimator(null);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, cfg, appOrder);
        grid.setAdapter(adapter);

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        ImageView btnProfile = findViewById(R.id.btnProfile);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        if (cfg.nowPlaying) {
            nowCard = new NowPlayingCard(this, getResources().getFont(R.font.outfit),
                    new NowPlayingCard.Exits() {
                        @Override
                        public void up() {
                            btnSettings.requestFocus();
                        }

                        @Override
                        public void down() {
                            focusFirstSection();
                        }
                    });
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ((ViewGroup) findViewById(R.id.nowSlot)).addView(nowCard, nlp);
            nowPlaying = new NowPlaying(this, handler, this::showNowPlaying);
        }
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        Config.Profile profile = cfg.profile(Config.chosenProfile(this));
        RecyclerView[] rows = {row(), row(), row()};
        shelf = new Shelf(this, rows, cfg, profile, new Shelf.Exits() {
            @Override
            public void up(int row) {
                moveVertical(sectionOfRow(row), -1);
            }

            @Override
            public void down(int row) {
                moveVertical(sectionOfRow(row), 1);
            }

            @Override
            public void changed(int row) {
                updateSections();
            }

            @Override
            public void focused(int row, ShelfItem item) {
                showHero(item);
            }

            @Override
            public void longPressed(int row) {
                startSectionMove(sectionOfRow(row));
            }

            @Override
            public boolean clicked(int row) {
                if (moving == null) return false;
                endSectionMove();
                return true;
            }
        });
        sections.add(new Section(SECTION_YOUTUBE, R.string.section_youtube, rows[Shelf.ROW_YOUTUBE], Shelf.ROW_YOUTUBE));
        sections.add(new Section(SECTION_TV, R.string.section_tv, rows[Shelf.ROW_TV], Shelf.ROW_TV));
        sections.add(new Section(SECTION_JELLYFIN, R.string.section_jellyfin, rows[Shelf.ROW_JELLYFIN], Shelf.ROW_JELLYFIN));
        sections.add(new Section(SECTION_APPS, R.string.section_apps, grid, -1));
        orderSections(Config.sectionOrder(this));
        // Every focus change says which section the user is in: it slides into place and the hero follows.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            Section in = sectionOf(newF);
            if (in != null) scrollTo(in, newF);
            else if (newF != null && isInHeader(newF)) scrollTo(null, null);
            if (in == null || in.row < 0) showHero(null);
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // The now playing card when it shows, else the first section, not wherever the focus finder lands.
                if (cardShowing()) nowCard.defaultButton().requestFocus();
                else focusFirstSection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // The visible buttons are a closed row: Left and Right move only between them and stop at the ends,
                // so the focus finder never drops into a shelf card that happens to lie further that way.
                ViewGroup bar = findViewById(R.id.topButtons);
                int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                for (int i = bar.indexOfChild(v) + step; i >= 0 && i < bar.getChildCount(); i += step) {
                    View c = bar.getChildAt(i);
                    if (c.getVisibility() == View.VISIBLE && c.isFocusable()) {
                        c.requestFocus();
                        break;
                    }
                }
                return true;
            }
            return false;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnProfile.setOnKeyListener(pillKeys);
        for (View pill : new View[] {btnProfile, btnWifi, btnInputs, btnSettings}) {
            pill.setOnFocusChangeListener((view, focused) -> {
                if (focused) lastPill = view;
            });
        }
        if (cfg.profiles.size() >= 2) {
            showProfile(btnProfile, profile);
            btnProfile.setVisibility(View.VISIBLE);
            btnProfile.setOnClickListener(v -> showProfiles(btnProfile, cfg));
            // The Google account's name and photo, through each person's YouTube channel.
            List<String> channelIds = new ArrayList<>();
            for (Config.Profile listed : cfg.profiles) {
                String id = cfg.profile(listed.name).youtubeChannelId;
                if (!id.isEmpty() && !channelIds.contains(id)) channelIds.add(id);
            }
            if (!cfg.youtubeApiKey.isEmpty() && !channelIds.isEmpty()) {
                new Thread(() -> {
                    java.util.Map<String, YouTubeIdentity> found = YouTubeIdentity.load(getApplicationContext(), cfg.youtubeApiKey, channelIds);
                    handler.post(() -> {
                        identities = found;
                        showProfile(btnProfile, cfg.profile(currentProfile));
                    });
                }).start();
            }
        }

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        // Registered for the activity's whole life, not only while it is resumed: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed (on Home or in another app): back to the first card of the first section, everything at its
        // start. Coming back with Back keeps the card the user left from.
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        if (openPopup != null) openPopup.dismiss();
        finishReorder();
        endSectionMove();
        shelf.toStart();
        lastFocused = 0;
        lastFocusedKey = adapter.keyAt(0);
        if (hasWindowFocus()) focusFirstSection();
        else homePressed = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                reloadApps(true, false);
                break;
            default:
                // Back from a side panel (Wi-Fi, Inputs): Home never left the screen, nothing to redo.
                break;
        }
        if (homePressed) {
            homePressed = false;
            sectionList.post(this::focusFirstSection);
        }
        updateGreeting();
        updateWifiLabel();
        updateWeather();
        updateInputsButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (nowPlaying != null) nowPlaying.start();
        if (homePhotoOn) loadHomePhoto();
        shelf.refresh();
    }

    /** Rebuilds the tiles, then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /**
     * @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS),
     *                without the entrance animation: on a return to the same tiles it would mark a change that
     *                didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = grid.hasFocus();
        if (rebuild) {
            reloadTiles();
            if (animate) grid.scheduleLayoutAnimation();
        }

        if (getCurrentFocus() == null) {
            // Nothing focused yet (Home just started): the first card of the first section.
            sectionList.post(this::focusFirstSection);
        } else if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
        }
    }

    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
        updateSections();
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        endSectionMove();
        handler.removeCallbacks(minuteTick);
        if (nowPlaying != null) nowPlaying.stop();
        if (nowCard != null) nowCard.stop();
        // Nothing of ours stays on screen over another app.
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        tileRefresh.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shelf.stop();
        unregisterReceiver(packageReceiver);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // While a section moves, only Up, Down, OK and Back mean anything; the row underneath stays put.
        int key = event.getKeyCode();
        if (moving != null && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (reorderMode) {
            finishReorder();
            return;
        }
        if (moving != null) {
            endSectionMove();
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- sections ------------------------------------------------------------------------------

    /** One titled section: a row of cards (a Shelf row) or the apps grid (row -1). */
    private final class Section {
        final String key;
        final int title;
        final int row;
        final LinearLayout view;
        final RecyclerView list;

        Section(String key, int title, RecyclerView list, int row) {
            this.key = key;
            this.title = title;
            this.row = row;
            this.list = list;
            int margin = getResources().getDimensionPixelSize(R.dimen.home_margin);
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setClipChildren(false);
            view.setClipToPadding(false);
            view.setPadding(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.section_gap));
            TextView name = new TextView(MainActivity.this);
            name.setText(title);
            name.setTextColor(0xE6FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            name.setTypeface(getResources().getFont(R.font.outfit));
            name.setFontVariationSettings("'wght' 600");
            name.setIncludeFontPadding(false);
            name.setShadowLayer(6 * density, 0, density, 0x99000000);   // readable over a bright photo
            name.setPadding(margin, 0, margin, (int) (4 * density));
            view.addView(name);
            // The card gap after the last card is room at the end; the start margin matches the title's.
            list.setPadding(margin, 0, row >= 0 ? margin - getResources().getDimensionPixelSize(R.dimen.card_gap) : margin, 0);
            list.setClipToPadding(false);
            list.setClipChildren(false);
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            view.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        boolean shown() {
            return view.getVisibility() == View.VISIBLE;
        }

        boolean hasItems() {
            return row >= 0 ? shelf.hasItems(row) : adapter.getItemCount() > 0;
        }

        void focus() {
            if (row >= 0) shelf.focus(row);
            else focusTile(lastFocused);
        }
    }

    /** A horizontal list for a Shelf row. */
    private RecyclerView row() {
        RecyclerView list = new RecyclerView(this);
        list.setFocusable(false);
        return list;
    }

    private Section sectionOfRow(int row) {
        for (Section s : sections) if (s.row == row) return s;
        return null;
    }

    private Section sectionOf(View v) {
        for (View at = v; at != null && at.getParent() instanceof View; at = (View) at.getParent()) {
            for (Section s : sections) if (at == s.view) return s;
        }
        return null;
    }

    private boolean isInHeader(View v) {
        View header = findViewById(R.id.header);
        for (View at = v; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) if (at == header) return true;
        return false;
    }

    /** The sections in this order (keys), the ones it doesn't name after them. */
    private void orderSections(List<String> order) {
        List<Section> sorted = new ArrayList<>(sections);
        Collections.sort(sorted, (a, b) -> {
            int ia = order.indexOf(a.key), ib = order.indexOf(b.key);
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });
        sections.clear();
        sections.addAll(sorted);
        sectionList.removeAllViews();
        for (Section s : sections) sectionList.addView(s.view);
        updateSections();
    }

    /** A section shows while it has something, placeholders included. */
    private void updateSections() {
        if (shelf == null || adapter == null) return;
        for (Section s : sections) {
            boolean show = s.hasItems();
            if (show == s.shown()) continue;
            if (!show && s.view.hasFocus()) moveVertical(s, 1);
            s.view.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        View focused = getCurrentFocus();
        Section in = sectionOf(focused);
        afterLayout(() -> scrollTo(in, in != null ? focused : null));
    }

    private Section neighbour(Section from, int dir) {
        int i = sections.indexOf(from);
        for (int j = i + dir; j >= 0 && j < sections.size(); j += dir) if (sections.get(j).shown()) return sections.get(j);
        return null;
    }

    private void focusFirstSection() {
        for (Section s : sections) {
            if (s.shown()) {
                s.focus();
                return;
            }
        }
        btnWifi.requestFocus();
    }

    /** Up or Down out of a section: the next one that shows, or the top buttons above the first. */
    private void moveVertical(Section from, int dir) {
        if (moving != null && moving == from) {
            moveSection(from, dir);
            return;
        }
        Section next = neighbour(from, dir);
        if (next != null) {
            next.focus();
        } else if (dir < 0) {
            if (cardShowing()) nowCard.defaultButton().requestFocus();
            else (lastPill != null && lastPill.getVisibility() == View.VISIBLE ? lastPill : findViewById(R.id.btnSettings)).requestFocus();
        }
    }

    /**
     * Slides the sections so the focused one's top (in the apps grid, the focused tile's row) sits at the same
     * height under the hero, as Google TV does; the ones above it fade out. With in null (focus in the top
     * buttons), the first section takes that place.
     */
    private void scrollTo(Section in, View focused) {
        Section target = in;
        if (target == null) {
            for (Section s : sections) if (s.shown()) {
                target = s;
                break;
            }
        }
        if (target == null) return;
        int extra = 0;
        if (target.row < 0 && focused != null && focused.getParent() instanceof View) extra = ((View) focused.getParent()).getTop();
        float y = root.getHeight() * ANCHOR - target.view.getTop() - extra;
        sectionList.animate().translationY(y).setDuration(320).setInterpolator(GLIDE).start();
        boolean above = true;
        for (Section s : sections) {
            if (s == target) above = false;
            s.view.animate().alpha(above ? 0f : 1f).setDuration(200).start();
        }
    }

    /** An ease-out curve close to tvOS: quick to start, a long soft landing. */
    private static final android.view.animation.Interpolator GLIDE =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    // ---- moving a section ------------------------------------------------------------------------

    /** Hold OK on a card: Up and Down then move its whole section; OK or Back ends it. */
    private void startSectionMove(Section s) {
        if (s == null || reorderMode) return;
        moving = s;
        orderPanel = orderPanel(s);
        orderPanel.setAlpha(0f);
        root.addView(orderPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        orderPanel.animate().alpha(1f).setDuration(200).start();
        hero.animate().alpha(0f).setDuration(200).start();
    }

    private void endSectionMove() {
        if (moving == null) return;
        Section s = moving;
        moving = null;
        View panel = orderPanel;
        orderPanel = null;
        orderRows.clear();
        panel.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(panel)).start();
        if (heroItem != null) hero.animate().alpha(1f).setDuration(200).start();
        scrollTo(s, getCurrentFocus());
    }

    /** Move mode's list over the dimmed Home: one row per shown section, the moving one lifted. */
    private View orderPanel(Section lifted) {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xBF000000);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.addView(panelText(getString(R.string.section_order_title), 22, 600, 0xFFFFFFFF));
        TextView hint = panelText(getString(R.string.section_order_hint), 14, 400, 0x99FFFFFF);
        hint.setPadding(0, (int) (6 * density), 0, (int) (8 * density));
        list.addView(hint);
        for (Section each : sections) {
            if (!each.shown()) continue;
            View row = orderRow(each, each == lifted);
            orderRows.put(each, row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (88 * density));
            lp.topMargin = (int) (12 * density);
            list.addView(row, lp);
        }
        dim.addView(list, new FrameLayout.LayoutParams((int) (760 * density), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return dim;
    }

    private View orderRow(Section s, boolean lifted) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (20 * density), 0, (int) (14 * density), 0);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(16 * density);
        fill.setColor(lifted ? 0xFFFFFFFF : 0x1FFFFFFF);
        row.setBackground(fill);
        ImageView arrows = new ImageView(this);
        arrows.setImageResource(R.drawable.ic_reorder);
        arrows.setColorFilter(0xFF111111);
        arrows.setVisibility(lifted ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams arrowsLp = new LinearLayout.LayoutParams((int) (28 * density), (int) (28 * density));
        arrowsLp.setMarginEnd((int) (12 * density));
        row.addView(arrows, arrowsLp);
        row.addView(panelText(getString(s.title), 20, 600, lifted ? 0xFF111111 : 0xFFFFFFFF),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (int i = 0; i < Math.min(3, s.list.getChildCount()); i++) {
            ImageView thumb = new ImageView(this);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(8 * density);
            shape.setColor(0x33FFFFFF);
            thumb.setBackground(shape);
            thumb.setClipToOutline(true);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageBitmap(snapshot(s.list.getChildAt(i)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (114 * density), (int) (64 * density));
            lp.setMarginStart((int) (10 * density));
            row.addView(thumb, lp);
        }
        if (lifted) {
            row.setElevation(10 * density);
            row.setScaleX(1.03f);
            row.setScaleY(1.03f);
        }
        return row;
    }

    /** A picture of a card as it shows at rest, without its focus outline. */
    private Bitmap snapshot(View item) {
        View card = item.findViewById(R.id.shelfCard);
        if (card == null) card = item.findViewById(R.id.card);
        if (card == null) card = item;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()), Bitmap.Config.ARGB_8888);
        Drawable focus = card.getForeground();
        card.setForeground(null);
        card.draw(new Canvas(bmp));
        card.setForeground(focus);
        return bmp;
    }

    private TextView panelText(String text, float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static final long SECTION_MOVE_MS = 320L;

    /** The row slides from where it showed to its new place. */
    private void slideFrom(View row, int top) {
        row.setTranslationY(top - row.getTop());
        row.animate().translationY(0f).setDuration(SECTION_MOVE_MS).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /** One place up or down among the shown sections; the order is saved at once. */
    private void moveSection(Section s, int dir) {
        Section other = neighbour(s, dir);
        View row = orderRows.get(s), otherRow = orderRows.get(other);
        if (other == null) {
            // At the end: a small nudge says there is nowhere further to go.
            if (row != null) row.animate().translationY(dir * 10 * density).setDuration(90)
                    .withEndAction(() -> row.animate().translationY(0f).setDuration(160).start()).start();
            return;
        }
        int a = sections.indexOf(s), b = sections.indexOf(other);
        sections.set(a, other);
        sections.set(b, s);
        List<String> order = new ArrayList<>();
        for (Section each : sections) order.add(each.key);
        Config.saveSectionOrder(this, order);
        View focused = getCurrentFocus();
        // The other section's view moves, so the focused card's never leaves the window and keeps focus.
        sectionList.removeView(other.view);
        sectionList.addView(other.view, sectionList.indexOfChild(s.view) + (dir > 0 ? 0 : 1));
        if (row == null || otherRow == null) {
            afterLayout(() -> scrollTo(s, focused));
            return;
        }
        int rowTop = row.getTop(), otherTop = otherRow.getTop();
        ViewGroup rows = (ViewGroup) row.getParent();
        rows.removeView(otherRow);
        rows.addView(otherRow, rows.indexOfChild(row) + (dir > 0 ? 0 : 1));
        afterLayout(() -> {
            scrollTo(s, focused);
            slideFrom(row, rowTop);
            slideFrom(otherRow, otherTop);
        });
    }

    /** Runs once the sections are laid out again, when their tops are where scrollTo reads them. */
    private void afterLayout(Runnable then) {
        sectionList.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                sectionList.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                then.run();
            }
        });
        sectionList.requestLayout();
    }

    private void showHint(int text) {
        reorderHint.setText(text);
        reorderHint.setVisibility(View.VISIBLE);
        reorderHint.setAlpha(0f);
        reorderHint.setTranslationY(10 * density);
        reorderHint.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void hideHint() {
        reorderHint.animate().alpha(0f).translationY(10 * density).setDuration(150)
                .withEndAction(() -> reorderHint.setVisibility(View.GONE)).start();
    }

    // ---- hero ------------------------------------------------------------------------------------

    /** The focused card in the top area, with its picture behind; null clears it back to the photo. */
    private void showHero(ShelfItem item) {
        if (item != null && item.source == ShelfItem.Source.PLACEHOLDER) item = null;
        heroItem = item;
        handler.removeCallbacks(heroPicture);
        if (item == null) {
            hero.animate().alpha(0f).setDuration(200).start();
            heroImage.animate().alpha(0f).setDuration(400).start();
            heroScrim.animate().alpha(0f).setDuration(400).start();
            findViewById(R.id.photoCaption).animate().alpha(1f).setDuration(400).start();
            return;
        }
        heroTitle.setText(heroTitle(item));
        heroSource.setText(heroSource(item));
        heroDescription.setText(item.description);
        heroDescription.setVisibility(item.description.isEmpty() ? View.GONE : View.VISIBLE);
        hero.animate().alpha(1f).setDuration(200).start();
        heroScrim.animate().alpha(1f).setDuration(300).start();
        findViewById(R.id.photoCaption).animate().alpha(0f).setDuration(300).start();
        if (item.backdrop == null) heroImage.animate().alpha(0f).setDuration(400).start();
        // The picture only once focus rests on the card, not for every card scrolled past.
        else handler.postDelayed(heroPicture, 350);
    }

    private final Runnable heroPicture = () -> {
        final ShelfItem item = heroItem;
        if (item == null || item.backdrop == null) return;
        shelf.picture(item, getResources().getDisplayMetrics().widthPixels, bmp -> {
            if (item != heroItem) return;
            if (bmp == null) {
                heroImage.animate().alpha(0f).setDuration(400).start();
                return;
            }
            ((ImageView) heroImage).setImageBitmap(bmp);
            heroImage.setAlpha(0f);
            heroImage.animate().alpha(1f).setDuration(500).start();
        });
    };

    private String heroTitle(ShelfItem item) {
        // A live channel's card is named after the channel; the hero names what is on.
        return item.source == ShelfItem.Source.LIVE && !item.subtitle.isEmpty() ? item.subtitle : item.title;
    }

    private String heroSource(ShelfItem item) {
        switch (item.source) {
            case YOUTUBE:
                return "YouTube · " + item.subtitle;
            case JELLYFIN:
                return item.subtitle.isEmpty() ? "Jellyfin" : "Jellyfin · " + item.subtitle;
            case LIVE:
                return item.title + " · " + getString(R.string.hero_now);
            default:
                return item.subtitle;
        }
    }

    // ---- apps ------------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        Section apps = sectionOfRow(-1);
        if (reorderMode) {
            int current = reorderPosition >= 0 ? reorderPosition : position;
            int moved = adapter.moveForReorder(current, keyCode);
            if (moved < 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                // At the grid's top or bottom edge, Up and Down move the whole apps section.
                moveSection(apps, keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            if (moved >= 0 && moved != current) {
                reorderPosition = moved;
                lastFocused = moved;
                lastFocusedKey = adapter.keyAt(moved);
                // Persist each move immediately. OK and Back are deliberately identical: both
                // simply leave move mode, without a second confirm/cancel decision.
                appOrder = adapter.appOrder();
                AppOrder.save(this, appOrder);
                // Let RecyclerView's move animator place the tile, then put focus back on the
                // moving card. The delayed refocus is important: during notifyItemMoved the old
                // holder can still report itself as focused for one layout pass.
                grid.postDelayed(() -> {
                    if (reorderMode && reorderPosition == moved) focusTile(moved);
                }, 220L);
            }
            return true;
        }
        int count = adapter.getItemCount();
        int col = position % GRID_COLUMNS;
        // Swallow Left and Right at a row's ends, so focus never wraps or leaves the grid sideways.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return col == 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return col == GRID_COLUMNS - 1 || position == count - 1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < GRID_COLUMNS) moveVertical(apps, -1);
            else focusTile(position - GRID_COLUMNS);
        } else {
            if (position / GRID_COLUMNS == (count - 1) / GRID_COLUMNS) moveVertical(apps, 1);
            else focusTile(Math.min(position + GRID_COLUMNS, count - 1));
        }
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Settings stays a compact launcher menu so dock editing is available with only the remote. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_settings, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = menuPopup(panel, (int) (240 * density));

        addSettingsRow(rows, popup, R.drawable.ic_settings, R.string.system_settings, 2);
        // Layout (Config.setChoice): applied when the menu closes, by rebuilding Home.
        addSeparator(rows);
        Config cfg = Config.load(this);
        final int[] layout = {cfg.gridColumns};
        final int[] atOpen = layout.clone();
        addChoiceRow(rows, R.drawable.ic_home_settings, R.string.apps_per_row,
                () -> String.valueOf(layout[0]), () -> layout[0] = layout[0] >= 7 ? 4 : Math.max(4, layout[0] + 1));
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;   // replaces menuPopup's listener
            if (java.util.Arrays.equals(layout, atOpen)) return;
            Config.setChoice(this, Config.GRID_COLUMNS, layout[0]);
            recreate();
        });
        // Switches that stay on the TV (Config.setToggle); the screensaver reads them when it next starts.
        addSeparator(rows);
        addToggleRow(rows, R.drawable.ic_clock, R.string.toggle_dream_analog, Config.DREAM_ANALOG, cfg.dreamAnalog, null);

        for (int i = 0; i < rows.getChildCount(); i++) {
            final int index = i;
            View row = rows.getChildAt(i);
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int step = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = index + step;
                    while (next >= 0 && next < rows.getChildCount() && !rows.getChildAt(next).isFocusable()) next += step;
                    if (next >= 0 && next < rows.getChildCount()) rows.getChildAt(next).requestFocus();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
        }
        // About six rows show at once; the rest scroll into view as focus moves down.
        int visible = getResources().getDimensionPixelSize(R.dimen.setting_row_height) * 6 + (int) (12 * density);
        View scroll = panel.findViewById(R.id.scroll);
        rows.measure(View.MeasureSpec.makeMeasureSpec((int) (240 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        if (rows.getMeasuredHeight() > visible) scroll.getLayoutParams().height = visible;
        dropIn(popup, panel, anchor, Gravity.END);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void addSettingsRow(LinearLayout rows, PopupWindow popup, int icon, int title, int action) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            if (action == 2) open(new Intent(Settings.ACTION_SETTINGS));
        });
        rows.addView(row);
    }

    /** A row that steps through a few values in place (OK for the next one); the menu stays open. */
    private void addChoiceRow(LinearLayout rows, int icon, int title, java.util.function.Supplier<String> value,
                              Runnable next) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView status = row.findViewById(R.id.status);
        status.setText(value.get());
        row.setOnClickListener(v -> {
            next.run();
            status.setText(value.get());
        });
        rows.addView(row);
    }

    /** A row that switches a setting On and Off in place; the menu stays open. */
    private void addToggleRow(LinearLayout rows, int icon, int title, String key, boolean initial,
                              java.util.function.Consumer<Boolean> changed) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        final boolean[] on = {initial};
        Runnable show = () -> {
            status.setText(on[0] ? R.string.toggle_on : R.string.toggle_off);
            dot.setVisibility(on[0] ? View.VISIBLE : View.GONE);
        };
        show.run();
        row.setOnClickListener(v -> {
            on[0] = !on[0];
            Config.setToggle(this, key, on[0]);
            show.run();
            if (changed != null) changed.accept(on[0]);
        });
        rows.addView(row);
    }

    @Override
    public void onTileLongPressed(int position) {
        if (moving != null || reorderMode || !adapter.isReorderableAppAt(position)) return;
        reorderMode = true;
        reorderPosition = position;
        DefaultItemAnimator moveAnimator = new DefaultItemAnimator();
        moveAnimator.setMoveDuration(180L);
        grid.setItemAnimator(moveAnimator);
        showHint(R.string.reorder_hint);
        View card = tileCardAt(position);
        if (card != null) {
            card.animate().scaleX(adapter.focusScale() * 1.06f)
                    .scaleY(adapter.focusScale() * 1.06f)
                    .setDuration(180).start();
        }
    }

    @Override
    public boolean onTileClicked(int position) {
        if (moving != null) {
            endSectionMove();
            return true;
        }
        if (!reorderMode) return false;
        finishReorder();
        return true;
    }

    private View tileCardAt(int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView.findViewById(R.id.card);
    }

    private void finishReorder() {
        if (!reorderMode) return;
        View selectedCard = tileCardAt(reorderPosition);
        if (selectedCard != null) {
            selectedCard.animate().scaleX(adapter.focusScale())
                    .scaleY(adapter.focusScale())
                    .setDuration(160).start();
        }
        reorderMode = false;
        reorderPosition = -1;
        grid.setItemAnimator(null);
        hideHint();
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    // ---- weather --------------------------------------------------------------------------------

    /** Refreshes the weather line at most every 30 minutes, off the main thread. */
    private void updateWeather() {
        if (weatherUrl == null || weatherLoading) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - weatherAt < WEATHER_MS) return;
        weatherAt = now;
        weatherLoading = true;
        new Thread(() -> {
            String text = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(weatherUrl).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    long temp = Math.round(cur.getDouble("temperature_2m"));
                    String icon = weatherIcon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                    text = icon + "  " + temp + "°" + (weatherPlace.isEmpty() ? "" : "  ·  " + weatherPlace);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                // Offline or the service is down: keep the last reading, retry in 5 minutes.
                android.util.Log.w("tvhome", "weather: " + e);
            }
            final String result = text;
            handler.post(() -> {
                weatherLoading = false;
                if (result == null) {
                    weatherAt -= WEATHER_MS - 5 * MINUTE_MS;
                    return;
                }
                weather.setText(result);
                weather.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    /** WMO weather code (as used by Open-Meteo) to an emoji. Also used by ClockDream. */
    static String weatherIcon(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";                  // clear: sun / moon
        if (code <= 2) return day ? "\u26C5" : "\u2601\uFE0F";                          // partly cloudy
        if (code == 3) return "\u2601\uFE0F";                                           // overcast
        // Fog as a cloud: older Android emoji fonts draw the fog emoji as a plain grey box.
        if (code == 45 || code == 48) return "\u2601\uFE0F";                           // fog
        if (code >= 51 && code <= 57) return "\uD83C\uDF26\uFE0F";                      // drizzle
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "\u2744\uFE0F"; // snow
        if (code >= 95) return "\u26C8\uFE0F";                                          // thunderstorm
        return "\uD83C\uDF27\uFE0F";                                                    // rain and showers
    }

    // ---- now playing ----------------------------------------------------------------------------

    private void showNowPlaying(NowPlaying.Track t) {
        track = t;
        nowCard.bind(t);
        updateCard();
    }

    private boolean cardShowing() {
        return nowCard != null && nowCard.getVisibility() == View.VISIBLE;
    }

    /** The pill shows while something plays or is paused. */
    private void updateCard() {
        if (nowCard == null) return;
        boolean show = nowCard.hasTrack();
        if (show && nowCard.getVisibility() != View.VISIBLE) {
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).setDuration(300).start();
        } else if (!show && nowCard.getVisibility() == View.VISIBLE) {
            if (nowCard.hasFocus()) focusFirstSection();
            nowCard.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (!nowCard.hasTrack()) nowCard.setVisibility(View.GONE);
            }).start();
        }
    }

    // ---- photo of the day -----------------------------------------------------------------------

    /** Today's photo behind the home screen, once a day. */
    private void loadHomePhoto() {
        Calendar now = Calendar.getInstance();
        String slot = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(now.getTime())
                + '/' + now.get(Calendar.HOUR_OF_DAY) / PHOTO_HOURS;
        if (slot.equals(photoSlot)) return;
        photoSlot = slot;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = dm.widthPixels, h = dm.heightPixels;
        new Thread(() -> {
            java.util.List<DailyPhoto.Photo> list = DailyPhoto.list(this);
            // A random photo from the list for each slot, the same one all through the slot. If it can't
            // be had (offline and not cached), the next ones in the list, up to three.
            int start = list.isEmpty() ? 0 : new java.util.Random(slot.hashCode()).nextInt(list.size());
            DailyPhoto.Photo chosen = null;
            Bitmap loaded = null;
            for (int i = 0; i < Math.min(3, list.size()) && loaded == null; i++) {
                chosen = list.get((start + i) % list.size());
                loaded = DailyPhoto.load(this, chosen, w);
            }
            final Bitmap bmp = loaded;
            final DailyPhoto.Photo p = chosen;
            if (bmp != null) bakeScrim(bmp);
            handler.post(() -> {
                if (bmp == null) {
                    photoSlot = null;   // offline with nothing cached: try again in a minute
                    return;
                }
                ImageView photo = findViewById(R.id.homePhoto);
                Drawable old = photo.getDrawable();
                if (old != null && photo.getAlpha() > 0f) {
                    // Already showing one: cross-fade to the new photo, then drop the old bitmap.
                    android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                            new Drawable[] {old, new BitmapDrawable(getResources(), bmp)});
                    fade.setCrossFadeEnabled(true);
                    photo.setImageDrawable(fade);
                    fade.startTransition(1500);
                    photo.postDelayed(() -> {
                        if (photo.getDrawable() == fade) photo.setImageBitmap(bmp);
                    }, 1600);
                } else {
                    photo.setImageBitmap(bmp);
                }
                photo.animate().alpha(1f).setDuration(900).withEndAction(this::dropHiddenLayers).start();
                TextView caption = findViewById(R.id.photoCaption);
                caption.setText(p.credit.isEmpty() ? p.title : p.title + "  ·  " + p.credit);
                if (heroItem == null) caption.animate().alpha(1f).setDuration(900).start();
            });
        }).start();
    }

    /**
     * Darkens the top and bottom of the photo so the text reads, drawn into the bitmap once instead of
     * as a full-screen layer the TV's GPU would blend on every frame.
     */
    private static void bakeScrim(Bitmap bmp) {
        if (!bmp.isMutable()) return;
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, bmp.getHeight(),
                new int[] {0x99000000, 0x14000000, 0x29000000, 0xD9000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), p);
    }

    /**
     * Once the photo covers the screen, nothing under it is ever seen: drop the window and root
     * backgrounds, two full-screen layers the GPU would otherwise fill on every frame.
     */
    private void dropHiddenLayers() {
        getWindow().setBackgroundDrawable(null);
        root.setBackground(null);
    }

    private void showProfile(ImageView button, Config.Profile p) {
        currentProfile = p.name;
        button.setContentDescription(displayName(p));
        setAvatar(button, p, Math.round(36 * density));
    }

    /** The name on the person's Google account (their YouTube channel's), else the profile's own. */
    private String displayName(Config.Profile p) {
        YouTubeIdentity id = identities.get(p.youtubeChannelId);
        return id != null && !id.name.isEmpty() ? id.name : p.name;
    }

    /**
     * The profile's initial at once, then its picture in a circle once it has loaded, like Google TV's: its own
     * photo, else its YouTube (Google account) picture, else its Jellyfin avatar.
     */
    private void setAvatar(ImageView view, Config.Profile p, int size) {
        view.setTag(p.name);
        view.setImageBitmap(initial(displayName(p), size));
        YouTubeIdentity id = identities.get(p.youtubeChannelId);
        String photo = !p.photo.isEmpty() ? p.photo : id != null ? id.photo : "";
        // Asked for at about twice the size shown, so the circle stays sharp.
        shelf.avatar(p, photo, 2 * size, bmp -> {
            if (p.name.equals(view.getTag())) view.setImageBitmap(roundedArt(bmp, size, 0.5f));
        });
    }

    /**
     * The account switcher: every profile with its picture and name, the current one marked. OK on another
     * one switches to it, shows its name and reloads the shelf for it.
     */
    private void showProfiles(ImageView button, Config cfg) {
        View panel = getLayoutInflater().inflate(R.layout.popup_settings, root, false);
        ((TextView) ((ViewGroup) panel).getChildAt(0)).setText(R.string.profiles);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = menuPopup(panel, (int) (280 * density));
        String current = currentProfile;
        View currentRow = null;
        int size = Math.round(24 * density);
        for (Config.Profile listed : cfg.profiles) {
            Config.Profile p = cfg.profile(listed.name);
            View row = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
            row.getLayoutParams().height = Math.round(40 * density);
            // A plain ImageView instead of the row's tinted icon, so the picture keeps its own colours.
            ImageView tinted = row.findViewById(R.id.icon);
            ImageView icon = new ImageView(this);
            ((ViewGroup) row).addView(icon, ((ViewGroup) row).indexOfChild(tinted), new LinearLayout.LayoutParams(size, size));
            ((ViewGroup) row).removeView(tinted);
            setAvatar(icon, p, size);
            ((TextView) row.findViewById(R.id.title)).setText(displayName(p));
            row.findViewById(R.id.status).setVisibility(View.GONE);
            boolean chosen = p.name.equals(current);
            row.findViewById(R.id.dot).setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (chosen) return;
                // A protected profile asks for its PIN or code first.
                unlock(p, () -> {
                    Config.chooseProfile(this, p.name);
                    showProfile(button, p);
                    Toast.makeText(this, displayName(p), Toast.LENGTH_SHORT).show();
                    shelf.use(p);
                });
            });
            row.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
            rows.addView(row);
            if (chosen) currentRow = row;
        }
        addSeparator(rows);
        addSettingsRow(rows, popup, R.drawable.ic_lock, R.string.lock_settings, 0);
        rows.getChildAt(rows.getChildCount() - 1).setOnClickListener(v -> {
            popup.dismiss();
            Config.Profile me = cfg.profile(current);
            unlock(me, () -> showLockOptions(me));
        });
        dropIn(popup, panel, button, Gravity.START);
        (currentRow != null ? currentRow : rows.getChildAt(0)).requestFocus();
    }

    /** Wrong answers per profile; static so a rebuilt Home keeps counting. */
    private static final java.util.Map<String, ProfileLock.Attempts> attempts = new java.util.HashMap<>();

    private ProfileLock.Attempts attemptsFor(String profile) {
        ProfileLock.Attempts a = attempts.get(profile);
        if (a == null) attempts.put(profile, a = new ProfileLock.Attempts());
        return a;
    }

    /** Runs then once the profile's PIN or code is given, or at once when it has no lock. */
    private void unlock(Config.Profile p, Runnable then) {
        String mode = ProfileLock.mode(this, p.name);
        if (ProfileLock.NONE.equals(mode)) {
            then.run();
            return;
        }
        boolean pin = ProfileLock.PIN.equals(mode);
        LockDialog.ask(this, getString(pin ? R.string.lock_enter_pin : R.string.lock_enter_code, displayName(p)), null, null,
                pin ? ProfileLock.PIN_LENGTH : ProfileLock.CODE_LENGTH, attemptsFor(p.name),
                answer -> ProfileLock.check(this, p.name, answer), answer -> then.run());
    }

    /** The current profile's lock: none, a PIN, or a code from an authenticator app on the phone. */
    private void showLockOptions(Config.Profile p) {
        View panel = getLayoutInflater().inflate(R.layout.popup_settings, root, false);
        ((TextView) ((ViewGroup) panel).getChildAt(0)).setText(R.string.lock_settings);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = menuPopup(panel, (int) (240 * density));
        String mode = ProfileLock.mode(this, p.name);
        int[] labels = {R.string.lock_none, R.string.lock_pin, R.string.lock_totp};
        String[] modes = {ProfileLock.NONE, ProfileLock.PIN, ProfileLock.TOTP};
        View currentRow = null;
        for (int i = 0; i < labels.length; i++) {
            final String choice = modes[i];
            addSettingsRow(rows, popup, R.drawable.ic_lock, labels[i], 0);
            View row = rows.getChildAt(rows.getChildCount() - 1);
            row.findViewById(R.id.dot).setVisibility(choice.equals(mode) ? View.VISIBLE : View.INVISIBLE);
            if (choice.equals(mode)) currentRow = row;
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (ProfileLock.NONE.equals(choice)) {
                    ProfileLock.clear(this, p.name);
                    Toast.makeText(this, R.string.lock_saved, Toast.LENGTH_SHORT).show();
                } else if (ProfileLock.PIN.equals(choice)) {
                    LockDialog.ask(this, getString(R.string.lock_new_pin), null, null, ProfileLock.PIN_LENGTH,
                            new ProfileLock.Attempts(), any -> true, first -> LockDialog.ask(this, getString(R.string.lock_repeat_pin),
                                    null, null, ProfileLock.PIN_LENGTH, new ProfileLock.Attempts(), first::equals, pin -> {
                                        ProfileLock.setPin(this, p.name, pin);
                                        Toast.makeText(this, R.string.lock_saved, Toast.LENGTH_SHORT).show();
                                    }));
                } else {
                    String secret = ProfileLock.newSecret();
                    LockDialog.ask(this, getString(R.string.lock_scan), ProfileLock.otpauth(secret, displayName(p)),
                            getString(R.string.lock_secret, secret.replaceAll("(.{4})", "$1 ").trim()), ProfileLock.CODE_LENGTH,
                            new ProfileLock.Attempts(), code -> ProfileLock.checkCode(secret, code, System.currentTimeMillis()), code -> {
                                ProfileLock.setTotp(this, p.name, secret);
                                Toast.makeText(this, R.string.lock_saved, Toast.LENGTH_SHORT).show();
                            });
                }
            });
        }
        dropIn(popup, panel, findViewById(R.id.btnProfile), Gravity.START);
        (currentRow != null ? currentRow : rows.getChildAt(0)).requestFocus();
    }

    /** The name's first letter on a circle in a colour that comes from the name, so each person keeps theirs. */
    private Bitmap initial(String name, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.HSVToColor(new float[] {Math.floorMod(name.hashCode(), 360), 0.45f, 0.62f}));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size * 0.48f);
        Typeface font = getResources().getFont(R.font.outfit);
        p.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, 600, false) : font);
        String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
        return out;
    }

    /** Album art scaled to a square with rounded corners. Also used by ClockDream. */
    static Bitmap roundedArt(Bitmap art, int size) {
        return roundedArt(art, size, 0.18f);
    }

    /** The cover cropped square with corners of radius × size; 0.5 gives a circle. */
    static Bitmap roundedArt(Bitmap art, int size, float radius) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float r = size * radius;
        c.drawRoundRect(0, 0, size, size, r, r, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        int side = Math.min(art.getWidth(), art.getHeight());
        Rect src = new Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2,
                (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
        c.drawBitmap(art, src, new Rect(0, 0, size, size), p);
        return out;
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = menuPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow menuPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (5 * density), h = (int) (10 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
