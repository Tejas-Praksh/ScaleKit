package com.scalekit.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * IP address extraction and validation utilities.
 *
 * <p>Handles proxy headers ({@code X-Forwarded-For}, {@code X-Real-IP})
 * to extract the true client IP behind load balancers.
 */
public final class IpUtil {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|" +
            "^::$|" +
            "^([0-9a-fA-F]{1,4}:){1,7}:$|" +
            "^::[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){0,5}$");

    // RFC 1918 private ranges
    private static final String[] PRIVATE_PREFIXES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127."
    };

    private IpUtil() {
        throw new AssertionError("Cannot instantiate utility class");
    }

    /**
     * Extracts the client IP from the request, respecting proxy headers.
     *
     * <p>Priority: X-Forwarded-For → X-Real-IP → remoteAddr.
     * For X-Forwarded-For with multiple IPs, takes the first (original client).
     *
     * @param request the HTTP request
     * @return client IP address
     */
    public static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (StringUtils.isNotBlank(forwarded)) {
            // X-Forwarded-For: client, proxy1, proxy2
            String firstIp = forwarded.split(",")[0].trim();
            if (isValidIp(firstIp)) {
                return firstIp;
            }
        }

        String realIp = request.getHeader(X_REAL_IP);
        if (StringUtils.isNotBlank(realIp) && isValidIp(realIp.trim())) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    /**
     * Checks whether the given IP belongs to a private (RFC 1918) range.
     */
    public static boolean isPrivateIp(String ip) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        for (String prefix : PRIVATE_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    /**
     * Validates that a string is a well-formed IPv4 or IPv6 address.
     */
    public static boolean isValidIp(String ip) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches();
    }
}
