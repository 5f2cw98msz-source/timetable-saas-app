package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.ShareLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read-only public pages, so students can see a timetable without an account.
 *
 * This is the feature that takes the tool from "staff admin" to something a
 * whole department sees, which is usually what makes it worth paying for.
 */
@Service
public class ShareLinkService {

    private final ShareLinkRepository links;
    private final PlanPolicy planPolicy;
    private final AuditService audit;

    public ShareLinkService(ShareLinkRepository links, PlanPolicy planPolicy, AuditService audit) {
        this.links = links;
        this.planPolicy = planPolicy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ShareLink> findAll(Organisation organisation) {
        return links.findByOrganisationOrderByCreatedAtDesc(organisation);
    }

    @Transactional
    public ShareLink create(Organisation organisation, String label, ShareLink.Scope scope,
                            User lecturer, User actor) {

        planPolicy.require(organisation, Feature.PUBLIC_SHARING);

        String cleanLabel = label == null || label.isBlank() ? "Public timetable" : label.trim();
        if (scope == ShareLink.Scope.SINGLE_LECTURER && lecturer == null) {
            throw new ValidationException("Choose which lecturer's timetable to share.");
        }

        ShareLink link = links.save(new ShareLink(
                organisation, Tokens.shareToken(), cleanLabel, scope, lecturer));

        audit.record(organisation, actor, AuditService.SHARE_CREATED, cleanLabel);
        return link;
    }

    /**
     * Looks up a link for a public visitor.
     *
     * Returns empty when the link is disabled, or when the organisation no
     * longer has the feature -- so a subscription lapsing takes the public
     * pages down rather than leaving a paid feature running for free.
     */
    @Transactional
    public Optional<ShareLink> resolveForPublicView(String token) {
        return links.findByToken(token)
                .filter(ShareLink::isEnabled)
                .filter(link -> link.getOrganisation().hasFeature(Feature.PUBLIC_SHARING))
                .map(link -> {
                    link.recordView();
                    return links.save(link);
                });
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, Organisation organisation, User actor) {
        ShareLink link = links.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That link is not part of your organisation."));
        link.setEnabled(enabled);
        links.save(link);

        audit.record(organisation, actor,
                enabled ? AuditService.SHARE_CREATED : AuditService.SHARE_REVOKED, link.getLabel());
    }

    @Transactional
    public void delete(Long id, Organisation organisation, User actor) {
        ShareLink link = links.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That link is not part of your organisation."));
        links.delete(link);
        audit.record(organisation, actor, AuditService.SHARE_REVOKED, link.getLabel());
    }
}
