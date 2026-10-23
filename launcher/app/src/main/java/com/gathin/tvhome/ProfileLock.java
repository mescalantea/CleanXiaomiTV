package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A profile's lock, set on the TV: none, a 4-digit PIN (kept as a salted PBKDF2 hash) or a code from an
 * authenticator app (TOTP, RFC 6238: SHA-1, 6 digits, 30 s). Kept in the launcher's private storage, never
 * logged. It is a household convenience, not security: clearing the launcher's data removes every lock.
 */
final class ProfileLock {
    static final String NONE = "none", PIN = "pin", TOTP = "totp";
    static final int PIN_LENGTH = 4, CODE_LENGTH = 6;
    /** Wrong answers in a row before a wait, and how long. */
    static final int MAX_FAILURES = 5;
    static final long WAIT_MS = 30_000L;
    private static final int ITERATIONS = 20_000;
    private static final String PREFS = "profile_locks";

    private ProfileLock() {
    }

    // ---- stored per profile ---------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String mode(Context ctx, String profile) {
        return prefs(ctx).getString(profile + ".mode", NONE);
    }

    static void clear(Context ctx, String profile) {
        prefs(ctx).edit().remove(profile + ".mode").remove(profile + ".salt").remove(profile + ".hash")
                .remove(profile + ".secret").apply();
    }

    static void setPin(Context ctx, String profile, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(ctx).edit().putString(profile + ".mode", PIN).putString(profile + ".salt", b64(salt))
                .putString(profile + ".hash", b64(hash(pin, salt))).remove(profile + ".secret").apply();
    }

    static void setTotp(Context ctx, String profile, String secret) {
        prefs(ctx).edit().putString(profile + ".mode", TOTP).putString(profile + ".secret", secret)
                .remove(profile + ".salt").remove(profile + ".hash").apply();
    }

    /** Whether the answer opens this profile: its PIN, or a current authenticator code. */
    static boolean check(Context ctx, String profile, String answer) {
        SharedPreferences p = prefs(ctx);
        switch (mode(ctx, profile)) {
            case PIN:
                return checkPin(answer, Base64.decode(p.getString(profile + ".salt", ""), Base64.NO_WRAP),
                        Base64.decode(p.getString(profile + ".hash", ""), Base64.NO_WRAP));
            case TOTP:
                return checkCode(p.getString(profile + ".secret", ""), answer, System.currentTimeMillis());
            default:
                return true;
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // ---- PIN ------------------------------------------------------------------------------------

    static byte[] hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares in constant time. */
    static boolean checkPin(String pin, byte[] salt, byte[] expected) {
        return expected.length > 0 && MessageDigest.isEqual(hash(pin, salt), expected);
    }

    // ---- authenticator codes --------------------------------------------------------------------

    /** A new random secret, base32 as authenticator apps expect it. */
    static String newSecret() {
        byte[] key = new byte[20];
        new SecureRandom().nextBytes(key);
        return base32(key);
    }

    /** What the authenticator app scans to add this profile. */
    static String otpauth(String secret, String account) {
        String label = android.net.Uri.encode("CleanXiaomiTV:" + account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=CleanXiaomiTV&digits=6&period=30";
    }

    /** The code for one 30-second step (RFC 6238 with SHA-1), digits long. */
    static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0F;
            int binary = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16) | ((h[offset + 2] & 0xFF) << 8)
                    | (h[offset + 3] & 0xFF);
            int mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            String s = Integer.toString(binary % mod);
            while (s.length() < digits) s = "0" + s;
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A code of this step or the one before or after, to allow for clocks a little apart. */
    static boolean checkCode(String secret, String answer, long nowMs) {
        if (secret.isEmpty() || answer.length() != CODE_LENGTH) return false;
        byte[] key = base32(secret);
        long step = nowMs / 30_000L;
        boolean ok = false;
        for (long s = step - 1; s <= step + 1; s++) {
            ok |= MessageDigest.isEqual(code(key, s, CODE_LENGTH).getBytes(), answer.getBytes());
        }
        return ok;
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] base32(String text) {
        ByteBuffer out = ByteBuffer.allocate(text.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.position()];
        out.flip();
        out.get(bytes);
        return bytes;
    }

    // ---- wrong answers ---------------------------------------------------------------------------

    /** Counts wrong answers; after MAX_FAILURES in a row, nothing is accepted for WAIT_MS. */
    static final class Attempts {
        private int failures;
        private long lockedUntil;

        /** Milliseconds left to wait, or 0. */
        long waitLeft(long now) {
            return Math.max(0, lockedUntil - now);
        }

        void failed(long now) {
            if (++failures >= MAX_FAILURES) {
                failures = 0;
                lockedUntil = now + WAIT_MS;
            }
        }

        void passed() {
            failures = 0;
            lockedUntil = 0;
        }
    }
}
