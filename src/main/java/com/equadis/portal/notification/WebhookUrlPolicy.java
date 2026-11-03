package com.equadis.portal.notification;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether we may call a retailer-provided webhook URL.
 *
 * <p>Protection against SSRF (Server-Side Request Forgery): without it, a retailer could
 * register {@code http://169.254.169.254/...} (cloud metadata) or {@code http://localhost:5432}
 * and make our servers call internal systems on its behalf. Rules:
 * <ul>
 *     <li>HTTPS only (HTTP allowed only in development/tests),</li>
 *     <li>no user name or password in the URL,</li>
 *     <li>no target address that is not on the public internet.</li>
 * </ul>
 * Limitation (documented): DNS can change between this check and the call ("DNS rebinding");
 * production would pin the resolved address or go through an egress proxy.
 */
final class WebhookUrlPolicy {

    private final boolean allowInsecureHttp;
    private final boolean allowPrivateAddresses;

    WebhookUrlPolicy(WebhookProperties properties) {
        this.allowInsecureHttp = properties.allowInsecureHttp();
        this.allowPrivateAddresses = properties.allowPrivateAddresses();
    }

    /** @throws PermanentDeliveryFailure when the URL must not be called */
    URI validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException malformed) {
            throw new PermanentDeliveryFailure("Webhook URL is malformed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = scheme.equals("https") || (allowInsecureHttp && scheme.equals("http"));
        if (!schemeAllowed) {
            throw new PermanentDeliveryFailure("Webhook URL must use HTTPS");
        }
        if (uri.getHost() == null) {
            throw new PermanentDeliveryFailure("Webhook URL has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new PermanentDeliveryFailure("Webhook URL must not contain a user name or password");
        }
        if (!allowPrivateAddresses) {
            rejectInternalTargets(uri.getHost());
        }
        return uri;
    }

    private static void rejectInternalTargets(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknownHost) {
            // Could be a temporary DNS problem: let the normal retry policy handle it.
            throw new IllegalStateException("Cannot resolve webhook host " + host, unknownHost);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new PermanentDeliveryFailure("Webhook URL points to a non-public address");
            }
        }
    }

    /** All the addresses must be public: one internal address among them is enough to refuse. */
    private static boolean isNonPublic(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()
                || isUniqueLocalIpv6(address) || isSharedOrReservedIpv4(address);
    }

    /** fc00::/7, the IPv6 private range. Java's {@code isSiteLocalAddress} does not cover it. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    /** 100.64.0.0/10 (carrier-grade NAT), 198.18.0.0/15 (benchmarking), 240.0.0.0/4 (reserved). */
    private static boolean isSharedOrReservedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 240;
    }
}
