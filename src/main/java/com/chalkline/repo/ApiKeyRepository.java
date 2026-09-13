package com.chalkline.repo;

import com.chalkline.domain.ApiKey;
import com.chalkline.domain.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    List<ApiKey> findByOrganisationOrderByCreatedAtDesc(Organisation organisation);

    Optional<ApiKey> findByIdAndOrganisation(Long id, Organisation organisation);

    /**
     * Candidates for an incoming key, narrowed by its public prefix. The hash
     * is then compared properly; the prefix alone never authenticates anything.
     */
    List<ApiKey> findByKeyPrefixAndRevokedFalse(String keyPrefix);
}
