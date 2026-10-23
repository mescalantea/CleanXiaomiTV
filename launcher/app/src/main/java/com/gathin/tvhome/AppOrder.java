package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The owner's order of the apps grid, saved on the TV. Until one is saved it starts with dock_apps
 * (config.xml or a theme), then an earlier launcher's dock and app order, so an update keeps the apps
 * where they were; newly installed apps join at the end.
 */
final class AppOrder {
    private static final String PREFS = "dock_preferences";
    private static final String KEY = "app_grid_order_v2";
    /** Earlier launchers kept the dock and the apps under it apart. */
    private static final String OLD_DOCK = "user_dock_v1", OLD_ORDER = "user_app_order_v1";

    private AppOrder() {
    }

    static List<String> load(Context ctx, List<String> seed) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> saved = prefs.contains(KEY) ? split(prefs.getString(KEY, ""))
                : merge(prefs.contains(OLD_DOCK) ? split(prefs.getString(OLD_DOCK, "")) : seed, split(prefs.getString(OLD_ORDER, "")));
        List<String> installed = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null && !installed.contains(ri.activityInfo.packageName)) installed.add(ri.activityInfo.packageName);
        }
        return order(saved, installed);
    }

    static void save(Context ctx, List<String> order) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, TextUtils.join("\n", order)).apply();
    }

    /** The saved packages still installed, in their order, then the other installed ones as listed. */
    static List<String> order(List<String> saved, List<String> installed) {
        List<String> out = new ArrayList<>();
        for (String pkg : saved) if (installed.contains(pkg) && !out.contains(pkg)) out.add(pkg);
        for (String pkg : installed) if (!out.contains(pkg)) out.add(pkg);
        return out;
    }

    static List<String> merge(List<String> first, List<String> then) {
        List<String> out = new ArrayList<>(first);
        for (String pkg : then) if (!out.contains(pkg)) out.add(pkg);
        return out;
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String pkg : Arrays.asList(raw.split("\n"))) if (!pkg.trim().isEmpty()) out.add(pkg.trim());
        return out;
    }
}
