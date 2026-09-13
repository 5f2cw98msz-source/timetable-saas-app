package com.chalkline.service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random secrets for share links, calendar feeds, API keys and webhook
 * signing.
 *
 * SecureRandom, not Random: these values are the only thing protecting the
 * resource behind them, so they have to be unguessable rather than merely
 * varied.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** URL-safe token. 32 bytes gives 256 bits of entropy. */
    public static String urlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return ENCODER.encodeToString(buffer);
    }

    public static String shareToken() {
        return urlSafe(24);
    }

    public static String calendarToken() {
        return urlSafe(24);
    }

    public static String webhookSecret() {
        return "whsec_" + urlSafe(24);
    }

    /** API keys carry a visible prefix so they are recognisable in a list. */
    public static String apiKey() {
        return "ck_" + urlSafe(24);
    }

    public static String prefixOf(String apiKey) {
        return apiKey.length() <= 11 ? apiKey : apiKey.substring(0, 11);
    }
}
