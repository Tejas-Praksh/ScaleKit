package com.scalekit.urlshortener.service.impl;

import com.scalekit.common.util.IpUtil;
import com.scalekit.urlshortener.dto.ThreatType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Checker component to verify if a URL host is a raw IP address.
 */
@Component
public class IpAddressChecker {

    /**
     * Checks if the given URL host is a raw IPv4 or IPv6 address.
     */
    public Optional<ThreatType> check(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String lowerUrl = url.toLowerCase().trim();
        String host = null;
        try {
            URI uri;
            if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
                uri = new URI(url);
            } else {
                uri = new URI("http://" + url);
            }
            host = uri.getHost();
        } catch (Exception e) {
            int protoIndex = lowerUrl.indexOf("://");
            int hostStart = (protoIndex != -1) ? protoIndex + 3 : 0;
            int slashIndex = lowerUrl.indexOf("/", hostStart);
            if (slashIndex != -1) {
                host = lowerUrl.substring(hostStart, slashIndex);
            } else {
                host = lowerUrl.substring(hostStart);
            }
        }

        if (host != null) {
            String cleanHost = host;
            if (host.startsWith("[") && host.endsWith("]")) {
                cleanHost = host.substring(1, host.length() - 1);
            }
            if (cleanHost.contains(":") || IpUtil.isValidIp(cleanHost)) {
                return Optional.of(ThreatType.IP_ADDRESS_URL);
            }
        }
        return Optional.empty();
    }
}
