package com.chalkline.service;

import com.chalkline.config.AppProperties;
import com.chalkline.domain.*;
import com.chalkline.repo.OrganisationRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.billingportal.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Subscriptions, via Stripe Checkout.
 *
 * Card details never touch this application: the customer is sent to a page
 * hosted by Stripe, pays there, and comes back. That keeps this code out of
 * PCI scope entirely, which is the only sensible position for a small team.
 *
 * What a customer is entitled to is decided by webhooks from Stripe, never by
 * the browser coming back from checkout. A success URL is just a URL, and
 * anyone can visit it.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** Links a Stripe session back to the organisation that started it. */
    public static final String ORG_METADATA_KEY = "chalkline_organisation_id";

    private final AppProperties properties;
    private final OrganisationRepository organisations;
    private final AuditService audit;

    public BillingService(AppProperties properties,
                          OrganisationRepository organisations,
                          AuditService audit) {
        this.properties = properties;
        this.organisations = organisations;
        this.audit = audit;
    }

    public boolean isConfigured() {
        return properties.billing().isConfigured();
    }

    public boolean isManualUpgradeAvailable() {
        // Never both. If real payments work, the free switch is gone.
        return !isConfigured() && properties.billing().allowManualUpgrade();
    }

    /**
     * Starts a subscription. Returns the Stripe-hosted URL to send the
     * administrator to.
     */
    public String createCheckoutUrl(Organisation organisation, User actor) {
        if (!isConfigured()) {
            throw new ValidationException(
                    "Payments are not set up on this deployment yet. See DEPLOYMENT.md, \"Turning on billing\".");
        }
        Stripe.apiKey = properties.billing().secretKey();

        try {
            com.stripe.param.checkout.SessionCreateParams.Builder params =
                    com.stripe.param.checkout.SessionCreateParams.builder()
                            .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                            .setSuccessUrl(url("/settings/billing?upgraded=1"))
                            .setCancelUrl(url("/settings/billing?cancelled=1"))
                            .setClientReferenceId(String.valueOf(organisation.getId()))
                            .putMetadata(ORG_METADATA_KEY, String.valueOf(organisation.getId()))
                            .addLineItem(com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                                    .setPrice(properties.billing().priceId())
                                    .setQuantity(1L)
                                    .build());

            // Reuse the Stripe customer if this organisation has subscribed
            // before, so their billing history stays in one place.
            if (organisation.getStripeCustomerId() != null) {
                params.setCustomer(organisation.getStripeCustomerId());
            } else {
                params.setCustomerEmail(actor.getEmail());
            }

            Session session = Session.create(params.build());
            return session.getUrl();

        } catch (StripeException e) {
            log.error("Could not create a Stripe checkout session for organisation {}",
                    organisation.getId(), e);
            throw new ValidationException(
                    "Could not reach the payment provider. Try again in a moment.");
        }
    }

    /** Stripe's own page for changing card details or cancelling. */
    public String createPortalUrl(Organisation organisation) {
        if (!isConfigured() || organisation.getStripeCustomerId() == null) {
            throw new ValidationException("There is no active subscription to manage.");
        }
        Stripe.apiKey = properties.billing().secretKey();

        try {
            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(SessionCreateParams.builder()
                            .setCustomer(organisation.getStripeCustomerId())
                            .setReturnUrl(url("/settings/billing"))
                            .build());
            return session.getUrl();

        } catch (StripeException e) {
            log.error("Could not open the Stripe billing portal for organisation {}",
                    organisation.getId(), e);
            throw new ValidationException("Could not reach the payment provider. Try again in a moment.");
        }
    }

    /**
     * Handles a webhook from Stripe.
     *
     * The signature is verified first and the request is rejected outright if
     * it does not check out. Without that, anyone who found this URL could
     * post themselves a subscription.
     */
    @Transactional
    public void handleWebhook(String payload, String signatureHeader) {
        if (!properties.billing().isWebhookConfigured()) {
            throw new ValidationException("Webhooks are not configured on this deployment.");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, properties.billing().webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Rejected a Stripe webhook with a bad signature");
            throw new ValidationException("Invalid signature.");
        }

        Stripe.apiKey = properties.billing().secretKey();
        log.info("Stripe webhook received: {}", event.getType());

        switch (event.getType()) {
            case "checkout.session.completed" -> onCheckoutCompleted(event);
            case "customer.subscription.created",
                 "customer.subscription.updated",
                 "customer.subscription.deleted" -> onSubscriptionChanged(event);
            default -> log.debug("Ignoring Stripe event type {}", event.getType());
        }
    }

    private void onCheckoutCompleted(Event event) {
        Optional<Session> session = event.getDataObjectDeserializer()
                .getObject()
                .filter(Session.class::isInstance)
                .map(Session.class::cast);

        if (session.isEmpty()) {
            log.warn("checkout.session.completed arrived without a readable session object");
            return;
        }
        Session checkout = session.get();
        Organisation organisation = resolveOrganisation(
                checkout.getMetadata() == null ? null : checkout.getMetadata().get(ORG_METADATA_KEY),
                checkout.getClientReferenceId());

        if (organisation == null) {
            log.warn("checkout.session.completed could not be matched to an organisation");
            return;
        }

        organisation.setStripeCustomerId(checkout.getCustomer());
        organisation.setStripeSubscriptionId(checkout.getSubscription());
        organisation.setPlan(Plan.PREMIUM);
        organisation.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        organisations.save(organisation);

        audit.record(organisation, null, AuditService.PLAN_CHANGED, "Upgraded to Premium");
        log.info("Organisation {} upgraded to Premium", organisation.getId());

        // The subscription object carries the authoritative status and period
        // end; checkout only tells us the payment went through.
        if (checkout.getSubscription() != null) {
            try {
                applySubscription(organisation, Subscription.retrieve(checkout.getSubscription()));
            } catch (StripeException e) {
                log.warn("Could not read subscription {} straight after checkout",
                        checkout.getSubscription(), e);
            }
        }
    }

    private void onSubscriptionChanged(Event event) {
        Optional<Subscription> subscription = event.getDataObjectDeserializer()
                .getObject()
                .filter(Subscription.class::isInstance)
                .map(Subscription.class::cast);

        if (subscription.isEmpty()) {
            log.warn("{} arrived without a readable subscription object", event.getType());
            return;
        }
        Subscription sub = subscription.get();

        Organisation organisation = organisations.findByStripeSubscriptionId(sub.getId())
                .or(() -> sub.getCustomer() == null
                        ? Optional.empty()
                        : organisations.findByStripeCustomerId(sub.getCustomer()))
                .orElse(null);

        if (organisation == null) {
            log.warn("Subscription {} does not belong to any known organisation", sub.getId());
            return;
        }
        applySubscription(organisation, sub);
    }

    private void applySubscription(Organisation organisation, Subscription sub) {
        SubscriptionStatus status = SubscriptionStatus.fromStripe(sub.getStatus());

        organisation.setStripeSubscriptionId(sub.getId());
        if (sub.getCustomer() != null) {
            organisation.setStripeCustomerId(sub.getCustomer());
        }
        organisation.setSubscriptionStatus(status);

        // The plan stays PREMIUM while a cancelled subscription runs out its
        // paid period. Organisation.getEffectivePlan() is what decides whether
        // features are actually on.
        organisation.setPlan(status == SubscriptionStatus.CANCELED && !status.entitlesToPaidFeatures()
                ? Plan.FREE
                : Plan.PREMIUM);

        organisations.save(organisation);
        audit.record(organisation, null, AuditService.PLAN_CHANGED,
                "Subscription is now " + status.getLabel());
    }

    private Organisation resolveOrganisation(String metadataId, String clientReferenceId) {
        String id = metadataId != null ? metadataId : clientReferenceId;
        if (id == null) {
            return null;
        }
        try {
            return organisations.findById(Long.parseLong(id)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Development and demo only: switch to Premium without paying. Refused
     * whenever real payments are configured.
     */
    @Transactional
    public void manualUpgrade(Organisation organisation, User actor, boolean premium) {
        if (!isManualUpgradeAvailable()) {
            throw new ValidationException("Plan changes go through the payment provider on this deployment.");
        }
        organisation.setPlan(premium ? Plan.PREMIUM : Plan.FREE);
        organisation.setSubscriptionStatus(premium ? SubscriptionStatus.ACTIVE : SubscriptionStatus.NONE);
        organisation.setCurrentPeriodEnd(premium ? Instant.now().plusSeconds(30L * 24 * 3600) : null);
        organisations.save(organisation);

        audit.record(organisation, actor, AuditService.PLAN_CHANGED,
                (premium ? "Switched to Premium" : "Switched to Free") + " (manual, billing not configured)");
    }

    private String url(String path) {
        String base = properties.publicUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }
}
