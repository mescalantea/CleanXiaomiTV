package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.function.Predicate;

/**
 * Asks for a PIN or an authenticator code, Google TV style: dots for the digits typed and a number pad for the
 * remote's arrows (its number keys work too). A wrong answer shakes and clears; too many make it wait.
 */
final class LockDialog {
    private LockDialog() {
    }

    /**
     * @param qrText    an otpauth:// link to show as a QR code above the pad (setting up), or null
     * @param check     whether the digits are right; asked once all length of them are typed
     * @param done      runs after a right answer, with it
     */
    static void ask(Activity activity, String title, String qrText, String note, int length, ProfileLock.Attempts attempts,
                    Predicate<String> check, java.util.function.Consumer<String> done) {
        float d = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        // Setting up an authenticator: the QR code and its text on the left, the pad on the right, so it all fits.
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(qrText != null ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = Math.round(28 * d);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundResource(R.drawable.glass_panel);
        LinearLayout info = new LinearLayout(activity), entry = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(info, new LinearLayout.LayoutParams(Math.round((qrText != null ? 300 : 320) * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (qrText != null) elp.leftMargin = Math.round(28 * d);
        panel.addView(entry, elp);

        info.addView(text(activity, title, 20, 600, 0xFFFFFFFF));
        if (qrText != null) {
            ImageView qr = new ImageView(activity);
            qr.setImageBitmap(qr(qrText, Math.round(170 * d)));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(Math.round(170 * d), Math.round(170 * d));
            qlp.topMargin = Math.round(16 * d);
            info.addView(qr, qlp);
        }
        if (note != null) {
            TextView n = text(activity, note, 13, 500, 0xB3FFFFFF);
            n.setPadding(0, Math.round(10 * d), 0, 0);
            info.addView(n);
        }

        LinearLayout dots = new LinearLayout(activity);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Math.round(qrText != null ? 0 : 18 * d);
        entry.addView(dots, dlp);
        View[] dot = new View[length];
        for (int i = 0; i < length; i++) {
            dot[i] = new View(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(14 * d), Math.round(14 * d));
            lp.setMargins(Math.round(7 * d), 0, Math.round(7 * d), 0);
            dots.addView(dot[i], lp);
        }
        TextView message = text(activity, "", 13, 500, 0xFFFF8A80);
        message.setPadding(0, Math.round(10 * d), 0, 0);
        entry.addView(message);

        StringBuilder typed = new StringBuilder();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable showDots = () -> {
            for (int i = 0; i < length; i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                if (i < typed.length()) g.setColor(0xFFFFFFFF);
                else g.setStroke(Math.round(2 * d), 0x99FFFFFF);
                dot[i].setBackground(g);
            }
        };
        showDots.run();

        // One digit typed (on the pad or with a number key), or -1 to delete the last one.
        // Set while a code is checked and while a wrong one clears: keys then do nothing.
        boolean[] busy = {false};
        java.util.function.IntConsumer press = digit -> {
            if (busy[0]) return;
            long wait = attempts.waitLeft(System.currentTimeMillis());
            if (wait > 0) {
                message.setText(activity.getString(R.string.lock_wait, (wait + 999) / 1000));
                return;
            }
            if (digit < 0) {
                if (typed.length() > 0) typed.setLength(typed.length() - 1);
            } else if (typed.length() < length) {
                typed.append(digit);
            }
            message.setText("");
            showDots.run();
            if (typed.length() < length) return;
            String answer = typed.toString();
            busy[0] = true;
            // The PIN's hash is slow on purpose; it is worked out off the main thread.
            new Thread(() -> {
                boolean right = check.test(answer);
                handler.post(() -> {
                    if (!dialog.isShowing()) return;
                    if (right) {
                        attempts.passed();
                        dialog.dismiss();
                        done.accept(answer);
                        return;
                    }
                    attempts.failed(System.currentTimeMillis());
                    long left = attempts.waitLeft(System.currentTimeMillis());
                    message.setText(left > 0 ? activity.getString(R.string.lock_wait, (left + 999) / 1000) : activity.getString(R.string.lock_wrong));
                    dots.animate().translationX(12 * d).setDuration(50).withEndAction(() -> dots.animate().translationX(-12 * d)
                            .setDuration(80).withEndAction(() -> dots.animate().translationX(0).setDuration(60).start()).start()).start();
                    handler.postDelayed(() -> {
                        typed.setLength(0);
                        showDots.run();
                        busy[0] = false;
                    }, 250);
                });
            }).start();
        };

        GridLayout keys = new GridLayout(activity);
        keys.setColumnCount(3);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = Math.round(16 * d);
        entry.addView(keys, klp);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        View first = null;
        for (String label : labels) {
            TextView key = text(activity, label, 22, 500, 0);
            key.setTextColor(new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                    new int[] {0xFF202124, 0xFFFFFFFF}));
            key.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = Math.round(64 * d);
            glp.height = Math.round(52 * d);
            glp.setMargins(Math.round(4 * d), Math.round(4 * d), Math.round(4 * d), Math.round(4 * d));
            if (label.isEmpty()) {
                keys.addView(new View(activity), glp);
                continue;
            }
            key.setFocusable(true);
            key.setClickable(true);
            key.setBackgroundResource(R.drawable.pill_bg);
            key.setOnClickListener(v -> press.accept(label.equals("⌫") ? -1 : Integer.parseInt(label)));
            keys.addView(key, glp);
            if (first == null) first = key;
        }

        dialog.setContentView(panel);
        dialog.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                press.accept(keyCode - KeyEvent.KEYCODE_0);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                press.accept(-1);
                return true;
            }
            return false;
        });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        if (first != null) first.requestFocus();
    }

    private static TextView text(Activity activity, String s, float sp, int weight, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(activity.getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
