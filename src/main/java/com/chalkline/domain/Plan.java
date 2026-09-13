package com.chalkline.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a subscription includes.
 *
 * Prices here are for display only. The amount actually charged is whatever
 * the Stripe Price given by STRIPE_PRICE_ID says, so that changing a price
 * never means redeploying -- and so the two can never silently disagree about
 * what a customer owes.
 */
public enum Plan {

    FREE("Free",
            0,
            5,
            Collections.emptySet(),
            "Everything one department needs to run a timetable."),

    PREMIUM("Premium",
            2900,
            Integer.MAX_VALUE,
            EnumSet.allOf(Feature.class),
            "For institutions sharing timetables beyond their own staff.");

    private final String label;
    private final int monthlyPencePerMonth;
    private final int staffLimit;
    private final Set<Feature> features;
    private final String tagline;

    Plan(String label, int monthlyPencePerMonth, int staffLimit, Set<Feature> features, String tagline) {
        this.label = label;
        this.monthlyPencePerMonth = monthlyPencePerMonth;
        this.staffLimit = staffLimit;
        this.features = features;
        this.tagline = tagline;
    }

    public String getLabel() {
        return label;
    }

    public String getTagline() {
        return tagline;
    }

    public int getStaffLimit() {
        return staffLimit;
    }

    public boolean isUnlimitedStaff() {
        return staffLimit == Integer.MAX_VALUE;
    }

    public Set<Feature> getFeatures() {
        return features;
    }

    public boolean includes(Feature feature) {
        return features.contains(feature);
    }

    /** e.g. "29" for display next to a currency symbol. */
    public String getMonthlyPriceMajor() {
        return String.valueOf(monthlyPencePerMonth / 100);
    }

    public boolean isPaid() {
        return monthlyPencePerMonth > 0;
    }
}
