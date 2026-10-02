package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** T050: destination address checks (FR-URL-002/003/016, PVT-007, SC-008, NFR-001, research R15). */
class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/docs",
            "http://example.com",
            "HTTPS://Example.com/a?b=c#d",
            "https://sub.example.co.uk:8443/path",
            "https://8.8.8.8/",
            "https://172.32.0.1/",
            "https://[2001:db8::1]/",
            "https://localhost.example.com/" })
    void acceptsAbsoluteHttpAndHttpsWithAPublicHost(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file",
            "mailto:someone@example.com",
            "ws://example.com/socket" })
    void rejectsOtherSchemesWithTheReason(String url) {
        assertRejected(url, "scheme");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "example.com/path", "/relative/path", "https://", "https:///path", "http:example.com",
            "https://exa mple.com/" })
    void rejectsMissingRelativeOrMalformedAddresses(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCategory()).isEqualTo(ErrorCategory.VALIDATION));
    }

    @Test
    void acceptsExactly2048CharactersAndRejectsLonger() {
        String prefix = "https://example.com/";
        String atLimit = prefix + "a".repeat(2048 - prefix.length());
        assertThat(atLimit).hasSize(2048);
        assertThatCode(() -> validator.validate(atLimit)).doesNotThrowAnyException();
        assertRejected(atLimit + "a", "2048");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/",
            "http://LOCALHOST:8080/admin",
            "http://api.localhost/",
            "http://localhost./",
            "http://0.0.0.0/",
            "http://0.1.2.3/",
            "http://127.0.0.1/",
            "http://127.255.255.254/",
            "http://10.0.0.1/",
            "http://10.255.255.255/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://[::]/",
            "http://[::1]/",
            "http://[0:0:0:0:0:0:0:1]/",
            "http://[fc00::1]/",
            "http://[fd12:3456::1]/",
            "http://[fe80::1]/",
            "http://[febf::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:7f00:1]/",
            "http://[::ffff:10.0.0.1]/",
            "http://[::ffff:192.168.0.1]/" })
    void rejectsLocalhostLoopbackAndPrivateLiterals(String url) {
        assertRejected(url, "host");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://2130706433/",
            "http://0x7f000001/",
            "http://0x7f.0.0.1/",
            "http://0177.0.0.1/",
            "http://127.1/",
            "http://127.0.1/",
            "http://010.0.0.1/" })
    void rejectsNonCanonicalNumericHosts(String url) {
        assertRejected(url, "host");
    }

    @Test
    void neverResolvesHostNames() {
        // A name that cannot resolve is still accepted: validation is syntactic only (FR-URL-016, AMB-003).
        assertThatCode(() -> validator.validate("https://no-such-host.invalid/path")).doesNotThrowAnyException();
    }

    private void assertRejected(String url, String reasonFragment) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ApiException.class)
                .hasMessageContaining(reasonFragment)
                .satisfies(e -> assertThat(((ApiException) e).getCategory()).isEqualTo(ErrorCategory.VALIDATION));
    }
}
