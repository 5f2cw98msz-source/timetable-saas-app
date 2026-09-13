package com.chalkline.service;

import com.chalkline.domain.*;
import com.chalkline.repo.ApiKeyRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Keys for the public API.
 *
 * The plain key is returned exactly once, when it is created. After that only
 * a bcrypt hash of it exists, so a copy of the database is not a set of
 * working credentials.
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final PasswordEncoder passwordEncoder;
    private final PlanPolicy planPolicy;
    private final AuditService audit;

    public ApiKeyService(ApiKeyRepository keys,
                         PasswordEncoder passwordEncoder,
                         PlanPolicy planPolicy,
                         AuditService audit) {
        this.keys = keys;
        this.passwordEncoder = passwordEncoder;
        this.planPolicy = planPolicy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findAll(Organisation organisation) {
        return keys.findByOrganisationOrderByCreatedAtDesc(organisation);
    }

    /** @return the plain key. It cannot be retrieved again. */
    @Transactional
    public String create(Organisation organisation, String name, User actor) {
        planPolicy.require(organisation, Feature.API_ACCESS);

        String cleanName = name == null || name.isBlank() ? "API key" : name.trim();
        String plain = Tokens.apiKey();

        keys.save(new ApiKey(organisation, cleanName,
                passwordEncoder.encode(plain), Tokens.prefixOf(plain)));

        audit.record(organisation, actor, AuditService.API_KEY_CREATED, cleanName);
        return plain;
    }

    @Transactional
    public void revoke(Long id, Organisation organisation, User actor) {
        ApiKey key = keys.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That key is not part of your organisation."));
        key.setRevoked(true);
        keys.save(key);
        audit.record(organisation, actor, AuditService.API_KEY_REVOKED, key.getName());
    }

    /**
     * Authenticates an incoming key.
     *
     * The prefix narrows the candidates to a handful; the bcrypt comparison
     * then decides. Matching on the prefix alone would authenticate anyone who
     * guessed eleven characters.
     */
    @Transactional
    public Optional<Organisation> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        String prefix = Tokens.prefixOf(presentedKey.trim());

        for (ApiKey candidate : keys.findByKeyPrefixAndRevokedFalse(prefix)) {
            if (passwordEncoder.matches(presentedKey.trim(), candidate.getKeyHash())) {
                if (!candidate.getOrganisation().hasFeature(Feature.API_ACCESS)) {
                    return Optional.empty();
                }
                candidate.setLastUsedAt(Instant.now());
                keys.save(candidate);
                return Optional.of(candidate.getOrganisation());
            }
        }
        return Optional.empty();
    }
}
