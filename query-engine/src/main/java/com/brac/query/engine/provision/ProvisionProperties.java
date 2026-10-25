package com.brac.query.engine.provision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * {@code query-engine.provision.*}: the few settings the provisioning run needs beyond what the
 * service already configures. The logins and passwords it creates come from the settings the
 * service uses anyway ({@code spring.r2dbc.*}, {@code query-engine.store.*},
 * {@code query-engine.projection.*}), so they are written down once.
 *
 * @param admin             the administrator login that creates roles and grants. Not part of the running
 *                          service's configuration: supply it only to the provisioning run, for example as
 *                          the environment variables {@code QUERYENGINE_PROVISION_ADMIN_USERNAME} and
 *                          {@code QUERYENGINE_PROVISION_ADMIN_PASSWORD}
 * @param database          the database to set up; defaults to the one in {@code spring.r2dbc.url}
 * @param projectionSchemas the schemas that hold the domain's projection tables; the reader finds
 *                          unqualified names there and the service login may write them
 * @param dryRun            print the SQL it would run, with passwords hidden, and connect to nothing
 */
@ConfigurationProperties(prefix = "query-engine.provision")
public record ProvisionProperties(
        @DefaultValue Admin admin,
        String database,
        @DefaultValue("public") List<String> projectionSchemas,
        @DefaultValue("false") boolean dryRun
) {

    /** @param url defaults to {@code spring.r2dbc.url} */
    public record Admin(String url, String username, String password) {
    }
}
