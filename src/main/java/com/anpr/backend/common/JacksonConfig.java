package com.anpr.backend.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * The perception team adds fields to the event payload without warning; an unknown
     * field must never cost us an event. Timestamps stay ISO-8601 strings in both
     * directions rather than becoming epoch numbers.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer anprJacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
