package com.agentic.shortener.workflow.engine;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link WorkflowProperties} from the {@code workflow.*} configuration. */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfiguration {
}
