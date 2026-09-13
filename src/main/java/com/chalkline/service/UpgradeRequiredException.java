package com.chalkline.service;

import com.chalkline.domain.Feature;

/**
 * The organisation asked for something its plan does not include. Handled as
 * a friendly prompt with a link to billing, never as an error page.
 */
public class UpgradeRequiredException extends RuntimeException {

    private final Feature feature;

    public UpgradeRequiredException(Feature feature) {
        super(feature.getLabel() + " is a Premium feature. " + feature.getDescription());
        this.feature = feature;
    }

    public UpgradeRequiredException(String message) {
        super(message);
        this.feature = null;
    }

    public Feature getFeature() {
        return feature;
    }
}
