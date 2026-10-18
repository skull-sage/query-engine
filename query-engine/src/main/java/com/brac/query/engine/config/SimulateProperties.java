package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code query-engine.simulate.*}: limits for the authoring-time simulate call. */
@ConfigurationProperties(prefix = "query-engine.simulate")
public record SimulateProperties(
        /* most rows a simulate returns; reading stops one row past it */
        @DefaultValue("200") int maxRows
) {
}
