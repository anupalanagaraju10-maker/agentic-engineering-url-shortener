package com.agentic.shortener.link;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Create, resolve and measure short links (FR-URL-001..013, 017; research R13/R14). Each create attempt is
 * its own short transaction holding the link and, if present, its idempotency key, so a unique-constraint
 * violation never poisons a surrounding transaction.
 */
@Service
public class LinkService {

    /** PVT-006: at most 5 generation attempts on collision. */
    public static final int MAX_CODE_ATTEMPTS = 5;
    public static final int MAX_IDEMPOTENCY_KEY = 100;

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository links;
    private final IdempotencyRepository keys;
    private final UrlValidator validator;
    private final ShortCodeGenerator generator;
    private final TransactionTemplate tx;

    public LinkService(LinkRepository links, IdempotencyRepository keys, UrlValidator validator,
            ShortCodeGenerator generator, PlatformTransactionManager transactionManager) {
        this.links = links;
        this.keys = keys;
        this.validator = validator;
        this.generator = generator;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** {@code replayed} is true when an earlier request with the same key and content is returned. */
    public record CreateResult(Link link, boolean replayed) {
    }

    public CreateResult create(String url, String idempotencyKey) {
        return create(url, idempotencyKey, null);
    }

    private CreateResult create(String url, String idempotencyKey, UUID probeRunId) {
        validator.validate(url);
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY)) {
            throw new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be non-blank and at most " + MAX_IDEMPOTENCY_KEY + " characters");
        }
        String fingerprint = idempotencyKey == null ? null : fingerprint(url);
        if (idempotencyKey != null) {
            Optional<CreateResult> earlier = replay(idempotencyKey, fingerprint);
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = generator.next();
            if (links.existsByCode(code)) {
                log.info("Short code collision on attempt {} of {}", attempt, MAX_CODE_ATTEMPTS);
                continue;
            }
            try {
                Link link = tx.execute(status -> insert(code, url, probeRunId, idempotencyKey, fingerprint));
                return new CreateResult(link, false);
            } catch (DataIntegrityViolationException e) {
                if (idempotencyKey != null) { // a concurrent request may have taken the key: re-read
                    Optional<CreateResult> earlier = replay(idempotencyKey, fingerprint);
                    if (earlier.isPresent()) {
                        return earlier.get();
                    }
                }
                log.info("Short code collision on insert, attempt {} of {}", attempt, MAX_CODE_ATTEMPTS);
            }
        }
        throw new ApiException(ErrorCategory.CODE_SPACE_EXHAUSTED, HttpStatus.SERVICE_UNAVAILABLE,
                "No unique short code could be generated in " + MAX_CODE_ATTEMPTS + " attempts; no link was created");
    }

    public Link get(String code) {
        return links.findByCode(code).orElseThrow(() -> new ApiException(ErrorCategory.NOT_FOUND,
                HttpStatus.NOT_FOUND, "No link exists for code " + code));
    }

    /**
     * Resolves an active link and counts the redirect. A lookup failure propagates (503, never 404); an
     * analytics failure is logged and the redirect still proceeds (FR-URL-013, FR-URL-017).
     */
    public String redirect(String code) {
        Link link = get(code);
        try {
            links.recordRedirect(link.getId(), Instant.now());
        } catch (DataAccessException | TransactionException e) {
            log.warn("Analytics recording failed for code {}; redirecting anyway: {}", code, e.getMessage());
        }
        return link.getOriginalUrl();
    }

    /** Transient link for the workflow TEST stage, tagged with its run id for cleanup or compensation. */
    public Link createProbeLink(String url, UUID runId) {
        return createProbeLink(url, runId, null).link();
    }

    /** Probe variant of {@link #create}: same validation, codes and idempotency, tagged with the run id. */
    public CreateResult createProbeLink(String url, UUID runId, String idempotencyKey) {
        if (runId == null) {
            throw new IllegalArgumentException("a probe link needs its run id");
        }
        return create(url, idempotencyKey, runId);
    }

    public long countProbeLinks(UUID runId) {
        return links.countByProbeRunId(runId);
    }

    /**
     * Removes only the probe links of the given run, with their idempotency keys, in one transaction; client
     * links are never touched. Idempotent: a second call deletes nothing.
     */
    public int deleteProbeLinks(UUID runId) {
        Integer deleted = tx.execute(status -> {
            keys.deleteForProbeRun(runId);
            return links.deleteByProbeRunId(runId);
        });
        return deleted == null ? 0 : deleted;
    }

    private Link insert(String code, String url, UUID probeRunId, String key, String fingerprint) {
        Instant now = Instant.now();
        Link link = links.saveAndFlush(new Link(code, url, now, probeRunId));
        if (key != null) {
            keys.saveAndFlush(new IdempotencyRecord(key, fingerprint, link.getId(), now));
        }
        return link;
    }

    private Optional<CreateResult> replay(String key, String fingerprint) {
        return keys.findById(key).map(record -> {
            if (!record.getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCategory.CONFLICT, HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with a different request");
            }
            Link link = links.findById(record.getLinkId()).orElseThrow(
                    () -> new IllegalStateException("idempotency record points to a missing link"));
            return new CreateResult(link, true);
        });
    }

    /** SHA-256 of the canonical request; today the request is only the url (research R14). */
    static String fingerprint(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
