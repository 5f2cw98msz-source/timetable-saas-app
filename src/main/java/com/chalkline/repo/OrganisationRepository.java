package com.chalkline.repo;

import com.chalkline.domain.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    Optional<Organisation> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Organisation> findByStripeCustomerId(String stripeCustomerId);

    Optional<Organisation> findByStripeSubscriptionId(String stripeSubscriptionId);
}
