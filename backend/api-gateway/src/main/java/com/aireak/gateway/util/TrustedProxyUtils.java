package com.aireak.gateway.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

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
                if (!trimmedIp.isEmpty() && !isTrustedProxy(trimmedIp, trustedProxies)) {
                    return trimmedIp;
                }
            }
            String firstIp = ips[0].trim();
            if (!firstIp.isEmpty()) {
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
                    if (!clientIp.isEmpty() && !isTrustedProxy(clientIp, trustedProxies)) {
                        return clientIp;
                    }
                }
            }
        }

        return directIp;
    }

    public static boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (ip == null || ip.isBlank() || trustedProxies == null || trustedProxies.isEmpty()) {
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
            InetAddress address = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0]);
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
