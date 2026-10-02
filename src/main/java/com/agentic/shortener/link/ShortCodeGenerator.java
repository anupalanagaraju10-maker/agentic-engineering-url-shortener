package com.agentic.shortener.link;

/** Source of candidate short codes; an interface so collisions are testable (research R14). */
public interface ShortCodeGenerator {

    String next();
}
