package com.chalkline.repo;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findByOrganisationOrderByCreatedAtDesc(Organisation organisation);

    List<WebhookEndpoint> findByOrganisationAndEnabledTrue(Organisation organisation);

    Optional<WebhookEndpoint> findByIdAndOrganisation(Long id, Organisation organisation);
}
