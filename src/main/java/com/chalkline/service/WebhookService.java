package com.chalkline.service;

import com.chalkline.domain.Organisation;
import com.chalkline.domain.WebhookEndpoint;
import com.chalkline.repo.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Calls the URLs an organisation has registered whenever a timetable changes.
 *
 * Two things matter here. Delivery runs off the request thread, so a slow or
 * dead endpoint cannot make saving a class hang. And every call is signed with
 * the endpoint's own secret, so the receiver can tell a real notification from
 * anyone who guessed the URL.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookEndpointRepository endpoints;
    private final RestClient restClient;

    public WebhookService(WebhookEndpointRepository endpoints, RestClient.Builder builder) {
        this.endpoints = endpoints;

        // Hard timeouts. Without them a customer endpoint that accepts the
        // connection and then never answers would hold an async thread
        // indefinitely, and enough of those would stop deliveries entirely.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.requestFactory(factory).build();
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> findAll(Organisation organisation) {
        return endpoints.findByOrganisationOrderByCreatedAtDesc(organisation);
    }

    @Transactional
    public WebhookEndpoint add(Organisation organisation, String url) {
        String trimmed = url == null ? "" : url.trim();

        // Only https, and no bare hostnames: a webhook carries timetable data
        // off this server, so it must not go out in the clear.
        if (!trimmed.startsWith("https://")) {
            throw new ValidationException("The URL must start with https://");
        }
        if (trimmed.length() > 500) {
            throw new ValidationException("That URL is too long.");
        }
        return endpoints.save(new WebhookEndpoint(organisation, trimmed, Tokens.webhookSecret()));
    }

    @Transactional
    public void delete(Long id, Organisation organisation) {
        WebhookEndpoint endpoint = endpoints.findByIdAndOrganisation(id, organisation)
                .orElseThrow(() -> new ValidationException("That webhook is not part of your organisation."));
        endpoints.delete(endpoint);
    }

    /** Fire-and-forget. Failures are recorded against the endpoint, not raised. */
    @Transactional(readOnly = true)
    public void notifyTimetableChanged(Organisation organisation, String event, String detail) {
        List<WebhookEndpoint> targets = endpoints.findByOrganisationAndEnabledTrue(organisation);
        if (targets.isEmpty()) {
            return;
        }
        String payload = """
                {"event":"%s","organisation":"%s","detail":"%s","at":"%s"}"""
                .formatted(escape(event), escape(organisation.getSlug()), escape(detail), Instant.now());

        for (WebhookEndpoint endpoint : targets) {
            deliver(endpoint.getId(), endpoint.getUrl(), endpoint.getSecret(), payload);
        }
    }

    @Async
    public void deliver(Long endpointId, String url, String secret, String payload) {
        String status;
        try {
            restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("X-Chalkline-Signature", "sha256=" + sign(payload, secret))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            status = "Delivered";
        } catch (Exception e) {
            // A customer's broken endpoint is their problem to see, not an
            // error in this application. Record it and carry on.
            status = "Failed: " + e.getClass().getSimpleName();
            log.warn("Webhook delivery to {} failed: {}", url, e.toString());
        }
        recordAttempt(endpointId, status);
    }

    @Transactional
    public void recordAttempt(Long endpointId, String status) {
        endpoints.findById(endpointId).ifPresent(endpoint -> {
            endpoint.setLastStatus(status);
            endpoint.setLastAttemptAt(Instant.now());
            endpoints.save(endpoint);
        });
    }

    /** HMAC-SHA256, the same scheme Stripe and GitHub use for their webhooks. */
    public static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign webhook payload", e);
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

}
