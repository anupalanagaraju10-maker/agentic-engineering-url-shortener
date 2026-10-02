package com.agentic.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** T051: 7-character Base62 short codes from SecureRandom (FR-URL-004, PVT-006). */
class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new SecureRandomShortCodeGenerator();

    @Test
    void generatesSevenBase62Characters() {
        IntStream.range(0, 1000).forEach(i -> assertThat(generator.next()).matches("^[0-9A-Za-z]{7}$"));
    }

    @Test
    void codesAreNotRepeatedInASmallSample() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> codes.add(generator.next()));
        assertThat(codes).hasSize(10_000); // 62^7 ≈ 3.5e12: a repeat here would indicate a broken source
    }

    @Test
    void usesTheWholeAlphabet() {
        Set<Character> seen = new HashSet<>();
        IntStream.range(0, 2000).forEach(i -> generator.next().chars().forEach(c -> seen.add((char) c)));
        assertThat(seen).hasSize(62);
    }
}
