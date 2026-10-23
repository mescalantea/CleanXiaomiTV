package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The apps grid: every TV app in the owner's saved order (AppOrder), hidden_apps left out. */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called when the user holds OK on a tile. */
        void onTileLongPressed(int position);

        /** Called by a normal OK click; return true when the launcher consumed it. */
        boolean onTileClicked(int position);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Fill behind a square banner shown whole; 0 until sampled. */
        int squareFill;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.08f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    /** Tiles per row (config.xml grid_columns, or the Settings menu). */
    private final int gridColumns;
    /** The owner's order of the apps (AppOrder). */
    private List<String> appOrder;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, Config cfg, List<String> appOrder) {
        this.ctx = ctx;
        this.listener = listener;
        this.gridColumns = cfg.gridColumns;
        this.appOrder = new ArrayList<>(appOrder);
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /** Rebuilds the tiles. */
    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, ResolveInfo> byPackage = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            if (!byPackage.containsKey(pkg)) byPackage.put(pkg, ri);
        }

        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !byPackage.get(pkg).equals(ri)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Drawable banner = loadBanner(pm, ri);
            // The icon is only drawn when there is no banner, so a tile with a banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            fresh.add(new Entry(String.valueOf(ri.loadLabel(pm)), component, banner, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int oa = appOrder.indexOf(a.pkg), ob = appOrder.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                return Integer.compare(oa, ob);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings)));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    void setAppOrder(List<String> appOrder) {
        this.appOrder = new ArrayList<>(appOrder);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        // Every tile fits `grid_columns` per row.
        int w = tileWidthPx(inner, gridColumns, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? focusScale() : 1f);
        h.card.setScaleY(focused ? focusScale() : 1f);
        h.card.setTranslationZ(focused ? SHADOW_DP * density() : 0f);
        h.card.setForeground(focused ? focusEdge(h.card, density()) : null);
        if (e.banner != null) {
            // Some apps reuse their square phone icon as the TV banner. Cropping that to 16:9 zooms in
            // and blurs it, so show it whole on its own edge colour instead.
            if (isSquare(e.banner)) {
                if (e.squareFill == 0) e.squareFill = edgeColor(e.banner);
                h.banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.banner.setBackgroundColor(e.squareFill);
            } else {
                h.banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.banner.setBackground(null);
            }
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && listener.onTileClicked(pos)) return;
            launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener == null || pos == RecyclerView.NO_POSITION) return false;
            listener.onTileLongPressed(pos);
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** True for an installed application, which can be moved. */
    boolean isReorderableAppAt(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind == Entry.APP;
    }

    List<String> appOrder() {
        return new ArrayList<>(appOrder);
    }

    /**
     * Moves one visible tile in place, keeping the adapter list and its persisted-order list in
     * sync. The caller can use the returned position to keep focus on the moving app.
     */
    int moveForReorder(int position, int keyCode) {
        if (position < 0 || position >= items.size()) return -1;
        int target;
        if (isReorderableAppAt(position)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                target = position + direction;
                if (target < 0 || target / gridColumns != position / gridColumns) return -1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                target = position + direction * gridColumns;
            } else {
                return -1;
            }
            if (target < 0 || target >= items.size() || !isReorderableAppAt(target)) return -1;
            String movedPkg = items.get(position).pkg;
            String targetPkg = items.get(target).pkg;
            int fromOrder = appOrder.indexOf(movedPkg);
            int targetOrder = appOrder.indexOf(targetPkg);
            if (fromOrder < 0 || targetOrder < 0) return -1;
            appOrder.remove(fromOrder);
            targetOrder = appOrder.indexOf(targetPkg);
            appOrder.add(targetOrder + (target > position ? 1 : 0), movedPkg);
        } else {
            return -1;
        }

        Entry moved = items.remove(position);
        items.add(target, moved);
        notifyItemMoved(position, target);
        return target;
    }

    /** A banner closer to square than 4:3, i.e. an icon standing in for one. */
    private static boolean isSquare(Drawable d) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        return w > 0 && h > 0 && w < h * 4 / 3;
    }

    /** The colour at the top-left edge of the drawable, opaque; the background layer of an adaptive icon. */
    private static int edgeColor(Drawable live) {
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF1C1C1E);   // under a transparent corner: the dark tile colour
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int p = bmp.getPixel(1, 1);
        bmp.recycle();
        return p | 0xFF000000;
    }

    /** The focused tile's soft shadow (dp of elevation). */
    static final float SHADOW_DP = 10f;

    float focusScale() {
        return FOCUS_SCALE;
    }

    /** The focused tile's thin white outline, inside its rounded corners. */
    static Drawable focusEdge(View card, float density) {
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(cornerRadius(card.getWidth(), density));
        edge.setStroke(Math.round(2.5f * density), 0xE6FFFFFF);
        return edge;
    }

    private float density() {
        return ctx.getResources().getDisplayMetrics().density;
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                view.animate().scaleX(s).scaleY(s)
                        .translationZ(focused ? SHADOW_DP * density : 0f)
                        .setDuration(focused ? 200 : 150)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                view.setForeground(focused ? focusEdge(view, density) : null);
                name.animate().cancel();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
