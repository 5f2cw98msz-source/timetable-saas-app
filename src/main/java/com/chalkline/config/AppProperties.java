package com.chalkline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every application setting, bound from the "app.*" keys in application.yml --
 * which in turn read environment variables.
 *
 * Nothing here is baked into the build: behaviour changes by setting
 * environment variables on the hosting platform, not by editing Java.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        /** Product name, shown throughout the interface. */
        @DefaultValue("Chalkline") String productName,

        /** Public base URL, used to build share links and Stripe return URLs. */
        @DefaultValue("http://localhost:8080") String publicUrl,

        @DefaultValue("support@example.com") String supportEmail,

        @DefaultValue Security security,
        @DefaultValue Billing billing,
        @DefaultValue Demo demo
) {

    public record Security(
            /** Whether new institutions may sign themselves up. */
            @DefaultValue("true") boolean allowSignUp,
            @DefaultValue("8") int minPasswordLength
    ) {}

    public record Billing(
            /**
             * Stripe secret key (sk_test_... or sk_live_...). Leave blank and
             * billing is switched off: everyone stays on the free plan and the
             * upgrade buttons explain that payments are not configured yet.
             */
            @DefaultValue("") String secretKey,

            /** Publishable key. Safe to expose; not currently rendered. */
            @DefaultValue("") String publishableKey,

            /** The Stripe Price to subscribe people to (price_...). */
            @DefaultValue("") String priceId,

            /**
             * Signing secret for the webhook endpoint (whsec_...). Without it
             * incoming webhooks are rejected, because an unverified webhook is
             * an open invitation to upgrade yourself for free.
             */
            @DefaultValue("") String webhookSecret,

            /**
             * Lets an administrator switch their own organisation to Premium
             * from the billing page, with no payment.
             *
             * For local development and demos only. It is refused outright
             * when Stripe IS configured, so it can never become a way around
             * paying on a real deployment.
             */
            @DefaultValue("false") boolean allowManualUpgrade
    ) {

        public boolean isConfigured() {
            return secretKey != null && !secretKey.isBlank()
                    && priceId != null && !priceId.isBlank();
        }

        public boolean isWebhookConfigured() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }
    }

    public record Demo(
            /** Fills a brand-new organisation with sample rooms and courses. */
            @DefaultValue("true") boolean seedStarterCatalogue
    ) {}
}
