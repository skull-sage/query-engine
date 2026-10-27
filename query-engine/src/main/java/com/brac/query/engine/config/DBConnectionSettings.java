package com.brac.query.engine.config;

import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.core.env.Environment;

/**
 * Where the engine's own connection pool for author SQL connects: the service's datasource,
 * {@code spring.r2dbc.url}, {@code spring.r2dbc.username} and {@code spring.r2dbc.password}. The engine has no
 * database settings of its own; it uses the datasource the service already has.
 */
public final class DBConnectionSettings {

    private DBConnectionSettings() {
    }

    public static ConnectionFactoryOptions.Builder fromService(Environment env) {
        String url = env.getProperty("spring.r2dbc.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("query-engine needs the service's datasource: set spring.r2dbc.url");
        }
        ConnectionFactoryOptions.Builder options = ConnectionFactoryOptions.parse(url).mutate();
        String username = env.getProperty("spring.r2dbc.username");
        String password = env.getProperty("spring.r2dbc.password");
        if (username != null && !username.isBlank()) {
            options.option(ConnectionFactoryOptions.USER, username);
        }
        if (password != null && !password.isBlank()) {
            options.option(ConnectionFactoryOptions.PASSWORD, password);
        }
        return options;
    }
}
