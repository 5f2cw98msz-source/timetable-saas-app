package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One institution, department or school. The tenant boundary: every account,
 * course, room and scheduled class belongs to exactly one organisation, and
 * nothing is ever visible across that line.
 *
 * Signing up creates an organisation and makes the person who signed up its
 * first administrator.
 */
@Entity
@Table(
        name = "organisations",
        uniqueConstraints = @UniqueConstraint(name = "uk_org_slug", columnNames = "slug")
)
public class Organisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    /** URL-safe identifier, used in public share links. */
    @Column(nullable = false, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan = Plan.FREE;

    // ---- Billing. Set by Stripe webhooks, never edited by hand. ----

    @Column(name = "stripe_customer_id", length = 120)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", length = 120)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 20)
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    /** When the current paid period runs out. Null on the free plan. */
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    // ---- Settings, previously global configuration ----

    @Column(name = "accent_colour", nullable = false, length = 16)
    private String accentColour = "#6C4BF4";

    @Column(name = "allow_self_registration", nullable = false)
    private boolean allowSelfRegistration = true;

    @Column(name = "prevent_room_clashes", nullable = false)
    private boolean preventRoomClashes = true;

    /** Comma-separated, because the list is short and never queried on. */
    @Column(name = "schedule_days", nullable = false, length = 500)
    private String scheduleDays = "Monday,Tuesday,Wednesday,Thursday,Friday";

    @Column(name = "schedule_slots", nullable = false, length = 2000)
    private String scheduleSlots =
            "08:00 - 09:00,09:00 - 10:00,10:00 - 11:00,11:00 - 12:00,12:00 - 13:00,"
                    + "13:00 - 14:00,14:00 - 15:00,15:00 - 16:00,16:00 - 17:00,17:00 - 18:00";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Organisation() {
        // required by JPA
    }

    public Organisation(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public void setStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public String getStripeSubscriptionId() {
        return stripeSubscriptionId;
    }

    public void setStripeSubscriptionId(String stripeSubscriptionId) {
        this.stripeSubscriptionId = stripeSubscriptionId;
    }

    public SubscriptionStatus getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(SubscriptionStatus subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(Instant currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public String getAccentColour() {
        return accentColour;
    }

    public void setAccentColour(String accentColour) {
        this.accentColour = accentColour;
    }

    public boolean isAllowSelfRegistration() {
        return allowSelfRegistration;
    }

    public void setAllowSelfRegistration(boolean allowSelfRegistration) {
        this.allowSelfRegistration = allowSelfRegistration;
    }

    public boolean isPreventRoomClashes() {
        return preventRoomClashes;
    }

    public void setPreventRoomClashes(boolean preventRoomClashes) {
        this.preventRoomClashes = preventRoomClashes;
    }

    public String getScheduleDays() {
        return scheduleDays;
    }

    public void setScheduleDays(String scheduleDays) {
        this.scheduleDays = scheduleDays;
    }

    public String getScheduleSlots() {
        return scheduleSlots;
    }

    public void setScheduleSlots(String scheduleSlots) {
        this.scheduleSlots = scheduleSlots;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<String> getDayList() {
        return split(scheduleDays);
    }

    public List<String> getSlotList() {
        return split(scheduleSlots);
    }

    /**
     * Whether paid features are currently switched on.
     *
     * Deliberately checks the subscription status as well as the plan: a
     * cancelled or unpaid subscription leaves the plan as PREMIUM until the
     * period ends, and this is what stops it granting features afterwards.
     */
    public boolean hasFeature(Feature feature) {
        if (!plan.includes(feature)) {
            return false;
        }
        return plan == Plan.FREE || subscriptionStatus.entitlesToPaidFeatures();
    }

    /** The plan actually in force right now, once billing state is considered. */
    public Plan getEffectivePlan() {
        if (plan == Plan.PREMIUM && !subscriptionStatus.entitlesToPaidFeatures()) {
            return Plan.FREE;
        }
        return plan;
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Organisation other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name;
    }
}
