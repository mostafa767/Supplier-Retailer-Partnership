package com.equadis.portal.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param connectTimeout        short: a slow retailer must not hold our worker
 * @param readTimeout           idem
 * @param allowInsecureHttp     only for local development and tests; production requires HTTPS
 * @param allowPrivateAddresses only for local development and tests; production blocks
 *                              loopback/private/link-local targets (SSRF protection)
 */
@ConfigurationProperties("portal.webhooks")
public record WebhookProperties(@DefaultValue("PT2S") Duration connectTimeout,
                                @DefaultValue("PT5S") Duration readTimeout,
                                @DefaultValue("false") boolean allowInsecureHttp,
                                @DefaultValue("false") boolean allowPrivateAddresses) {
}
