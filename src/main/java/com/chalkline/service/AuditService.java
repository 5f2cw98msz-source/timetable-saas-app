package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records who changed what.
 *
 * Events are written for every organisation regardless of plan -- only
 * READING the log is a paid feature. Recording it only for paying customers
 * would mean an upgrade produced an empty history, which is when people
 * actually need it.
 */
@Service
public class AuditService {

    public static final String SCHEDULED = "Class scheduled";
    public static final String CLEARED = "Class cleared";
    public static final String MEMBER_ADDED = "Member added";
    public static final String MEMBER_REMOVED = "Member removed";
    public static final String MEMBER_SUSPENDED = "Member suspended";
    public static final String MEMBER_RESTORED = "Member reactivated";
    public static final String SETTINGS_CHANGED = "Settings changed";
    public static final String PLAN_CHANGED = "Plan changed";
    public static final String SHARE_CREATED = "Share link created";
    public static final String SHARE_REVOKED = "Share link revoked";
    public static final String API_KEY_CREATED = "API key created";
    public static final String API_KEY_REVOKED = "API key revoked";
    public static final String CATALOG_CHANGED = "Rooms or courses changed";

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional
    public void record(Organisation organisation, User actor, String action, String detail) {
        events.save(new AuditEvent(organisation, actor, action, detail));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> recent(Organisation organisation, int page, int size) {
        return events.findByOrganisationOrderByAtDesc(organisation, PageRequest.of(page, size));
    }
}
