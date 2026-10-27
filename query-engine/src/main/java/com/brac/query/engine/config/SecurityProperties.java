package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the startup check ({@code SecurityStartupCheck}) does when the service's database login is
 * more powerful than a query engine should run with (a superuser, or one that ignores row-level security).
 *
 * <p>The check that author SQL really is read-only (a write attempted through the same path fails) is
 * not controlled by this setting: if it fails the service does not start, in every mode.
 *
 * @param mode {@code enforce} refuses to start with such a login; {@code warn} logs and starts anyway, for a
 *             development machine whose database was set up with the default superuser; {@code off} skips
 *             the login check
 */
@ConfigurationProperties(prefix = "query-engine.security")
public record SecurityProperties(@DefaultValue("enforce") Mode mode) {

    public enum Mode {
        ENFORCE, WARN, OFF
    }
}
