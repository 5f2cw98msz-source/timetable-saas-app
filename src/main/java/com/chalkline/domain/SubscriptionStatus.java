package com.chalkline.domain;

/**
 * Mirrors the status Stripe reports for a subscription. Kept as our own enum
 * so the rest of the app never has to care where the value came from -- and so
 * an unfamiliar status from Stripe becomes UNKNOWN rather than an exception.
 */
public enum SubscriptionStatus {

    NONE,
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELED,
    UNPAID,
    INCOMPLETE,
    UNKNOWN;

    /** True when the customer should currently get paid features. */
    public boolean entitlesToPaidFeatures() {
        return this == ACTIVE || this == TRIALING;
    }

    public static SubscriptionStatus fromStripe(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public String getLabel() {
        return switch (this) {
            case NONE -> "No subscription";
            case TRIALING -> "Trial";
            case ACTIVE -> "Active";
            case PAST_DUE -> "Payment overdue";
            case CANCELED -> "Cancelled";
            case UNPAID -> "Unpaid";
            case INCOMPLETE -> "Incomplete";
            case UNKNOWN -> "Unknown";
        };
    }
}
