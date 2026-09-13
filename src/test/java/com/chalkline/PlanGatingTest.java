package com.chalkline;

import com.chalkline.domain.*;
import com.chalkline.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each plan may do.
 *
 * The point of these is that a paid feature is refused by the SERVICE, not
 * merely hidden in a template. A hidden button is a courtesy; this is the
 * control.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:plans;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@Import(TestFixtures.class)
@Transactional
class PlanGatingTest {

    @Autowired TestFixtures fixtures;
    @Autowired PlanPolicy planPolicy;
    @Autowired ShareLinkService shareLinkService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired UserService userService;

    TestFixtures.Fixture uni;

    @BeforeEach
    void setUp() {
        uni = fixtures.institution("Plan University", "plan.edu");
    }

    @Test
    @DisplayName("the free plan includes no paid features")
    void freePlanHasNoPaidFeatures() {
        for (Feature feature : Feature.values()) {
            assertThat(uni.organisation().hasFeature(feature))
                    .as("free plan should not include " + feature)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("premium unlocks every feature")
    void premiumUnlocksEverything() {
        fixtures.makePremium(uni.organisation());

        for (Feature feature : Feature.values()) {
            assertThat(uni.organisation().hasFeature(feature))
                    .as("premium should include " + feature)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a paid feature is refused on the free plan by the service, not the page")
    void paidFeaturesAreRefusedInTheService() {
        assertThatThrownBy(() -> shareLinkService.create(uni.organisation(), "Students",
                ShareLink.Scope.WHOLE_ORGANISATION, null, uni.admin()))
                .isInstanceOf(UpgradeRequiredException.class);

        assertThatThrownBy(() -> apiKeyService.create(uni.organisation(), "Portal", uni.admin()))
                .isInstanceOf(UpgradeRequiredException.class);

        assertThatThrownBy(() -> planPolicy.require(uni.organisation(), Feature.CSV_IMPORT))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    @DisplayName("the free plan caps staff accounts, premium does not")
    void staffLimitIsEnforced() {
        // setUp already created 3 of the 5 free accounts.
        userService.addMember(uni.organisation(), "d@plan.edu", "Fourth", "password123",
                Role.LECTURER, uni.admin());
        userService.addMember(uni.organisation(), "e@plan.edu", "Fifth", "password123",
                Role.LECTURER, uni.admin());

        assertThat(planPolicy.staffLimitReached(uni.organisation())).isTrue();
        assertThatThrownBy(() -> userService.addMember(uni.organisation(), "f@plan.edu",
                "Sixth", "password123", Role.LECTURER, uni.admin()))
                .isInstanceOf(UpgradeRequiredException.class)
                .hasMessageContaining("up to 5 staff accounts");

        fixtures.makePremium(uni.organisation());
        assertThat(planPolicy.staffLimitReached(uni.organisation())).isFalse();
        assertThat(userService.addMember(uni.organisation(), "f@plan.edu", "Sixth",
                "password123", Role.LECTURER, uni.admin())).isNotNull();
    }

    @Test
    @DisplayName("a lapsed subscription stops paid features without losing any data")
    void lapsedSubscriptionRevokesFeatures() {
        fixtures.makePremium(uni.organisation());
        ShareLink link = shareLinkService.create(uni.organisation(), "Students",
                ShareLink.Scope.WHOLE_ORGANISATION, null, uni.admin());

        assertThat(shareLinkService.resolveForPublicView(link.getToken())).isPresent();

        // The plan stays PREMIUM while the payment fails, but the status is
        // what decides entitlement, so public pages must go dark.
        uni.organisation().setSubscriptionStatus(SubscriptionStatus.PAST_DUE);

        assertThat(uni.organisation().getEffectivePlan()).isEqualTo(Plan.FREE);
        assertThat(uni.organisation().hasFeature(Feature.PUBLIC_SHARING)).isFalse();
        assertThat(shareLinkService.resolveForPublicView(link.getToken())).isEmpty();

        // The link itself still exists, so paying again restores it untouched.
        assertThat(shareLinkService.findAll(uni.organisation())).hasSize(1);
    }

    @Test
    @DisplayName("an API key only authenticates while the plan includes the API")
    void apiKeysStopWorkingWhenThePlanLapses() {
        fixtures.makePremium(uni.organisation());
        String key = apiKeyService.create(uni.organisation(), "Portal", uni.admin());

        assertThat(apiKeyService.authenticate(key)).isPresent();

        uni.organisation().setSubscriptionStatus(SubscriptionStatus.CANCELED);
        assertThat(apiKeyService.authenticate(key)).isEmpty();
    }

    @Test
    @DisplayName("a revoked key stops working immediately")
    void revokedKeysStopWorking() {
        fixtures.makePremium(uni.organisation());
        String key = apiKeyService.create(uni.organisation(), "Portal", uni.admin());
        Long id = apiKeyService.findAll(uni.organisation()).get(0).getId();

        apiKeyService.revoke(id, uni.organisation(), uni.admin());
        assertThat(apiKeyService.authenticate(key)).isEmpty();
    }

    @Test
    @DisplayName("a made-up key never authenticates, even with a real prefix")
    void forgedKeysAreRejected() {
        fixtures.makePremium(uni.organisation());
        String real = apiKeyService.create(uni.organisation(), "Portal", uni.admin());
        String forged = real.substring(0, 11) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertThat(apiKeyService.authenticate(forged)).isEmpty();
        assertThat(apiKeyService.authenticate(null)).isEmpty();
        assertThat(apiKeyService.authenticate("")).isEmpty();
    }
}
