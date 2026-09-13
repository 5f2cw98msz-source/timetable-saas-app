package com.chalkline.repo;

import com.chalkline.domain.AuditEvent;
import com.chalkline.domain.Organisation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findByOrganisationOrderByAtDesc(Organisation organisation, Pageable pageable);
}
