package com.equadis.portal.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the application's auto-configured connection pool in a {@link TenantAwareDataSource}.
 *
 * <p>Flyway is configured with its own connection (the owner role, see application.yml), so
 * migrations are not affected - only the application's {@code portal_app} connections are.
 */
@Configuration(proxyBeanMethods = false)
public class TenantDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
