package com.agentic.shortener.link;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 7-character Base62 codes from {@link SecureRandom} (FR-URL-004, PVT-006): not enumerable. */
@Component
public class SecureRandomShortCodeGenerator implements ShortCodeGenerator {

    static final int LENGTH = 7;
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
