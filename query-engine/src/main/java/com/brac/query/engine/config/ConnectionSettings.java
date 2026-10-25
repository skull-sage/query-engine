package com.brac.query.engine.config;

import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.core.env.Environment;

/**
 * Works out where one of the engine's connection pools connects: the pool's own
 * {@code url}, {@code username} and {@code password} when set, otherwise the service's
 * {@code spring.r2dbc.*} values.
 */
public final class ConnectionSettings {

    private ConnectionSettings() {
    }

    /**
     * @param what    which pool this is, for the error when no URL can be found ("projection reads")
     * @param ownName the property that sets the URL for this pool, for the same error
     */
    public static ConnectionFactoryOptions.Builder resolve(Environment env, String url, String username, String password,
                                                            String what, String ownName) {
        String resolvedUrl = firstNonBlank(url, env.getProperty("spring.r2dbc.url"));
        if (resolvedUrl == null) {
            throw new IllegalStateException(
                    "query-engine needs spring.r2dbc.url (or " + ownName + ") for " + what);
        }
        ConnectionFactoryOptions.Builder options = ConnectionFactoryOptions.parse(resolvedUrl).mutate();
        String resolvedUser = firstNonBlank(username, env.getProperty("spring.r2dbc.username"));
        String resolvedPassword = firstNonBlank(password, env.getProperty("spring.r2dbc.password"));
        if (resolvedUser != null) {
            options.option(ConnectionFactoryOptions.USER, resolvedUser);
        }
        if (resolvedPassword != null) {
            options.option(ConnectionFactoryOptions.PASSWORD, resolvedPassword);
        }
        return options;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
