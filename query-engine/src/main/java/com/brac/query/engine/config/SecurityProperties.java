package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the startup check ({@code SecurityStartupCheck}) does when the database logins are not set
 * up as docs/security_protocol.md requires.
 *
 * @param mode {@code enforce} refuses to start; {@code warn} logs and starts anyway, for a dev
 *             machine that has not been provisioned yet; {@code off} skips the login checks
 */
@ConfigurationProperties(prefix = "query-engine.security")
public record SecurityProperties(@DefaultValue("enforce") Mode mode) {

    public enum Mode {
        ENFORCE, WARN, OFF
    }
}
