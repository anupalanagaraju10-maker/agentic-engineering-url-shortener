package com.agentic.shortener.link;

import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /r/{code}} ⇒ 302 with {@code Cache-Control: no-store}, so every visit reaches the server and is
 * counted (FR-URL-006/007, research R13). Unknown ⇒ 404; storage unavailable ⇒ 503.
 */
@RestController
public class RedirectController {

    private final LinkService links;

    public RedirectController(LinkService links) {
        this.links = links;
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String target = links.redirect(code);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).cacheControl(CacheControl.noStore())
                .build();
    }
}
