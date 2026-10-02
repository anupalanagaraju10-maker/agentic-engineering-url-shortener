package com.agentic.shortener.link;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Link API (contracts/openapi.yaml {@code /api/links}; FR-URL-001, 010, 011). */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private final LinkService links;

    public LinkController(LinkService links) {
        this.links = links;
    }

    public record CreateLinkRequest(String url) {
    }

    public record LinkResponse(String code, String shortUrl, String originalUrl, Instant createdAt, long redirectCount,
            Instant lastRedirectAt) {
    }

    /** 201 for a new link; 200 for an idempotent replay (same key, same request). */
    @PostMapping
    public ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        LinkService.CreateResult result = links.create(request.url(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view(result.link()));
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return view(links.get(code));
    }

    private static LinkResponse view(Link link) {
        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/r/{code}")
                .buildAndExpand(link.getCode()).toUriString();
        return new LinkResponse(link.getCode(), shortUrl, link.getOriginalUrl(), link.getCreatedAt(),
                link.getRedirectCount(), link.getLastRedirectAt());
    }
}
