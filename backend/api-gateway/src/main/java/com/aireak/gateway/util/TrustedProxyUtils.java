package com.aireak.gateway.util;

import com.google.common.net.InetAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Resolves the real client IP behind a trusted proxy chain, for per-IP rate limiting.
 *
 * <p><strong>Never resolves DNS.</strong> Every candidate string here can originate from a
 * client-supplied {@code X-Forwarded-For}/{@code Forwarded} header, and these methods run on a
 * Netty event-loop thread. {@link InetAddresses#forString} parses literals only;
 * {@code InetAddress.getByName} — which blocks on a resolver for anything else — must not appear
 * in this class.
 */
public class TrustedProxyUtils {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyUtils.class);

    private TrustedProxyUtils() {
    }

    public static String extractClientIp(ServerWebExchange exchange, List<String> trustedProxies) {
        ServerHttpRequest request = exchange.getRequest();
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        String directIp = remoteAddress.getAddress().getHostAddress();
        if (!isTrustedProxy(directIp, trustedProxies)) {
            return directIp;
        }

        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] ips = xff.split(",");
            for (String ip : ips) {
                String trimmedIp = ip.trim();
                if (isUntrustedClientIp(trimmedIp, trustedProxies)) {
                    return trimmedIp;
                }
            }
            String firstIp = ips[0].trim();
            if (InetAddresses.isInetAddress(firstIp)) {
                return firstIp;
            }
        }

        String forwarded = request.getHeaders().getFirst("Forwarded");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String part : forwarded.split(";")) {
                part = part.trim();
                if (part.toLowerCase().startsWith("for=")) {
                    String clientIp = part.substring(4).trim().replaceAll("^\"|\"$", "");
                    if (clientIp.startsWith("[")) {
                        int closingBracket = clientIp.indexOf(']');
                        if (closingBracket > 1) {
                            clientIp = clientIp.substring(1, closingBracket);
                        }
                    }
                    if (isUntrustedClientIp(clientIp, trustedProxies)) {
                        return clientIp;
                    }
                }
            }
        }

        return directIp;
    }

    /**
     * A forwarded-header entry usable as the real client IP: a well-formed literal that isn't
     * itself one of our proxies.
     *
     * <p>The literal check is what stops the returned value from becoming an attacker-chosen
     * rate-limit bucket key. Anything non-empty used to be accepted here, so a client could send
     * a fresh junk {@code X-Forwarded-For} per request, land in a brand-new token bucket every
     * time, and never trip the per-IP flood guard at all. Falling through to {@code directIp}
     * instead means a spoofed header degrades to limiting by the proxy's own address rather than
     * to no limiting.
     */
    private static boolean isUntrustedClientIp(String candidate, List<String> trustedProxies) {
        return !candidate.isEmpty()
                && InetAddresses.isInetAddress(candidate)
                && !isTrustedProxy(candidate, trustedProxies);
    }

    public static boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (ip == null || ip.isBlank() || trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        // Reject non-literals up front rather than inside matchCidr: a header value like
        // "evil.example.com" is not an IP and therefore cannot be a trusted proxy, and bailing
        // here keeps a stream of junk header values from flooding matchCidr's warn log — that
        // log is meant to surface a broken trusted-proxies CONFIG entry, not attacker input.
        if (!InetAddresses.isInetAddress(ip)) {
            return false;
        }
        for (String trusted : trustedProxies) {
            if (matchesIpOrCidr(ip, trusted.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIpOrCidr(String ip, String trustedPattern) {
        if (trustedPattern.equals(ip)) {
            return true;
        }
        if (trustedPattern.contains("/")) {
            return matchCidr(ip, trustedPattern);
        }
        return false;
    }

    private static boolean matchCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            // InetAddresses.forString, never InetAddress.getByName: `ip` here comes from a
            // client-supplied X-Forwarded-For / Forwarded header, and getByName performs a
            // BLOCKING DNS lookup for anything that isn't already an IP literal. This runs on a
            // Netty event-loop thread (PreAuthRateLimitingWebFilter/RateLimitingWebFilter), so a
            // header naming a deliberately-slow host could stall an event loop — a handful of
            // such requests is enough to wedge the whole gateway — and doubles as an out-of-band
            // DNS channel for probing internal names. This parser only ever parses; it throws
            // IllegalArgumentException on a non-literal and never touches a resolver.
            InetAddress address = InetAddresses.forString(ip);
            InetAddress network = InetAddresses.forString(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] addressBytes = address.getAddress();
            byte[] networkBytes = network.getAddress();
            if (addressBytes.length != networkBytes.length) {
                return false;
            }

            int bitsToMatch = prefixLength;
            for (int i = 0; i < addressBytes.length; i++) {
                if (bitsToMatch >= 8) {
                    if (addressBytes[i] != networkBytes[i]) {
                        return false;
                    }
                    bitsToMatch -= 8;
                } else if (bitsToMatch > 0) {
                    int mask = (0xFF << (8 - bitsToMatch)) & 0xFF;
                    if ((addressBytes[i] & mask) != (networkBytes[i] & mask)) {
                        return false;
                    }
                    bitsToMatch = 0;
                } else {
                    break;
                }
            }
            return true;
        } catch (Exception e) {
            // Most likely a malformed entry in gateway.trusted-proxies (bad CIDR syntax or an
            // unresolvable host) — surfaced here rather than left silent, since a broken entry
            // otherwise just looks like "never trusted" with no clue as to why.
            log.warn("Failed to match IP {} against trusted-proxy entry '{}': {}", ip, cidr, e.getMessage());
            return false;
        }
    }
}
