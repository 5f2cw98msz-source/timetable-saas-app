package com.chalkline.repo;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    List<ShareLink> findByOrganisationOrderByCreatedAtDesc(Organisation organisation);

    Optional<ShareLink> findByToken(String token);

    Optional<ShareLink> findByIdAndOrganisation(Long id, Organisation organisation);

    long countByOrganisation(Organisation organisation);
}
