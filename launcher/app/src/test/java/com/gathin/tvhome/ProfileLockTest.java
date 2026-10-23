package com.gathin.tvhome;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileLockTest {

    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void codesMatchTheRfc6238Sha1Vectors() {
        assertEquals("94287082", ProfileLock.code(RFC_KEY, 59 / 30, 8));
        assertEquals("07081804", ProfileLock.code(RFC_KEY, 1111111109L / 30, 8));
        assertEquals("14050471", ProfileLock.code(RFC_KEY, 1111111111L / 30, 8));
        assertEquals("89005924", ProfileLock.code(RFC_KEY, 1234567890L / 30, 8));
        assertEquals("69279037", ProfileLock.code(RFC_KEY, 2000000000L / 30, 8));
        assertEquals("65353130", ProfileLock.code(RFC_KEY, 20000000000L / 30, 8));
    }

    @Test
    public void aCodeIsAcceptedOneStepEitherSideOnly() {
        String secret = ProfileLock.base32(RFC_KEY);
        long now = 1111111111_000L;
        String current = ProfileLock.code(RFC_KEY, now / 30_000L, 6);
        String previous = ProfileLock.code(RFC_KEY, now / 30_000L - 1, 6);
        String old = ProfileLock.code(RFC_KEY, now / 30_000L - 3, 6);
        assertTrue(ProfileLock.checkCode(secret, current, now));
        assertTrue(ProfileLock.checkCode(secret, previous, now));
        assertFalse(old.equals(current) || ProfileLock.checkCode(secret, old, now));
        assertFalse(ProfileLock.checkCode(secret, "12345", now));
        assertFalse(ProfileLock.checkCode("", current, now));
    }

    @Test
    public void base32RoundTrips() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", ProfileLock.base32(RFC_KEY));
        assertArrayEquals(RFC_KEY, ProfileLock.base32("gezdgnbvgy3tqojqgezdgnbvgy3tqojq"));
        assertEquals(32, ProfileLock.newSecret().length());
    }

    @Test
    public void pinHashVerifiesOnlyTheSamePin() {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] hash = ProfileLock.hash("4821", salt);
        assertTrue(ProfileLock.checkPin("4821", salt, hash));
        assertFalse(ProfileLock.checkPin("4822", salt, hash));
        assertFalse(ProfileLock.checkPin("4821", "fedcba9876543210".getBytes(StandardCharsets.US_ASCII), hash));
        assertFalse(ProfileLock.checkPin("4821", salt, new byte[0]));
    }

    @Test
    public void fiveWrongAnswersMakeItWaitThirtySeconds() {
        ProfileLock.Attempts a = new ProfileLock.Attempts();
        for (int i = 0; i < 4; i++) a.failed(1000);
        assertEquals(0, a.waitLeft(1000));
        a.failed(1000);
        assertEquals(30_000, a.waitLeft(1000));
        assertEquals(10_000, a.waitLeft(21_000));
        assertEquals(0, a.waitLeft(31_000));
        a.failed(40_000);
        a.passed();
        for (int i = 0; i < 4; i++) a.failed(50_000);
        assertEquals(0, a.waitLeft(50_000));
    }
}
