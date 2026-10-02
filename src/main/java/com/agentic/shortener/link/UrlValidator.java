package com.agentic.shortener.link;

import com.agentic.shortener.common.ApiException;
import com.agentic.shortener.common.ErrorCategory;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Destination address checks (FR-URL-002/003/016, PVT-007, research R15). Purely syntactic: host names are
 * never resolved, so a name that resolves to a private address is not blocked (documented limitation,
 * EXC-009). Only IP literals are inspected.
 */
@Component
public class UrlValidator {

    public static final int MAX_LENGTH = 2048;

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern NUMERIC_HOST = Pattern.compile(
            "^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANONICAL_OCTET = Pattern.compile("^(0|[1-9][0-9]{0,2})$");

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("url is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw invalid("url exceeds the maximum length of " + MAX_LENGTH + " characters");
        }
        Matcher scheme = SCHEME.matcher(url);
        if (!scheme.find()) {
            throw invalid("url must be an absolute http or https address");
        }
        String name = scheme.group(1).toLowerCase(Locale.ROOT);
        if (!name.equals("http") && !name.equals("https")) {
            throw invalid("scheme '" + name + "' is not allowed; only http and https are accepted");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("url is not a valid absolute address");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("url must have a host");
        }
        checkHost(host);
    }

    private static void checkHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw refusedHost(rawHost);
        }
        if (host.startsWith("[")) {
            checkIpv6Literal(rawHost, host.substring(1, host.length() - 1));
            return;
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            checkIpv4Literal(rawHost, host);
        }
    }

    private static void checkIpv4Literal(String rawHost, String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw nonCanonical(rawHost);
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!CANONICAL_OCTET.matcher(parts[i]).matches() || Integer.parseInt(parts[i]) > 255) {
                throw nonCanonical(rawHost);
            }
            octets[i] = Integer.parseInt(parts[i]);
        }
        if (isPrivateIpv4(octets)) {
            throw refusedHost(rawHost);
        }
    }

    private static void checkIpv6Literal(String rawHost, String literal) {
        InetAddress address;
        try {
            // A literal containing ':' is parsed, never looked up.
            address = literal.contains(":") ? InetAddress.getByName(literal) : null;
        } catch (UnknownHostException e) {
            address = null;
        }
        if (address == null) {
            throw invalid("host '" + rawHost + "' is not a valid IPv6 literal");
        }
        if (address instanceof Inet4Address v4) { // IPv4-mapped forms are returned as IPv4
            if (isPrivateIpv4(unsigned(v4.getAddress()))) {
                throw refusedHost(rawHost);
            }
            return;
        }
        byte[] b = address.getAddress();
        boolean embeddedV4 = address instanceof Inet6Address v6 && v6.isIPv4CompatibleAddress()
                && isPrivateIpv4(new int[] { b[12] & 0xff, b[13] & 0xff, b[14] & 0xff, b[15] & 0xff });
        boolean uniqueLocal = (b[0] & 0xfe) == 0xfc;                        // fc00::/7
        boolean linkLocal = (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80; // fe80::/10
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || uniqueLocal || linkLocal || embeddedV4) {
            throw refusedHost(rawHost);
        }
    }

    private static boolean isPrivateIpv4(int[] o) {
        return o[0] == 0 || o[0] == 127 || o[0] == 10
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
    }

    private static int[] unsigned(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[i] & 0xff;
        }
        return out;
    }

    private static ApiException refusedHost(String host) {
        return invalid("host '" + host + "' is not allowed: localhost, loopback and private network addresses are refused");
    }

    private static ApiException nonCanonical(String host) {
        return invalid("host '" + host + "' is a non-canonical numeric address and is refused");
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCategory.VALIDATION, HttpStatus.BAD_REQUEST, reason);
    }
}
