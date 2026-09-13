package com.chalkline.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** Turns an institution's name into something safe to put in a URL. */
public final class Slugs {

    private Slugs() {
    }

    public static String from(String input) {
        if (input == null || input.isBlank()) {
            return "org";
        }
        String slug = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")             // strip accents
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")        // anything else becomes a dash
                .replaceAll("(^-+)|(-+$)", "");       // no leading or trailing dashes

        if (slug.isBlank()) {
            slug = "org";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    /** Appends -2, -3 ... until the slug is not already taken. */
    public static String unique(String base, Predicate<String> taken) {
        String slug = from(base);
        if (!taken.test(slug)) {
            return slug;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = slug + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free slug for " + base);
    }
}
