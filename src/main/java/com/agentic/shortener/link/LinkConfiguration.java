package com.agentic.shortener.link;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The time source of the link plane; injectable so expiration is testable (T111). */
@Configuration
public class LinkConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
