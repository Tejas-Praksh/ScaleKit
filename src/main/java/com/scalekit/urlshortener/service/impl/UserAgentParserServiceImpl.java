package com.scalekit.urlshortener.service.impl;

import com.scalekit.urlshortener.dto.UserAgentDetails;
import com.scalekit.urlshortener.service.UserAgentParserService;
import org.springframework.stereotype.Service;

/**
 * Implementation of UserAgentParserService using simple pattern matching.
 */
@Service
public class UserAgentParserServiceImpl implements UserAgentParserService {

    @Override
    public UserAgentDetails parse(String userAgent) {
        if (userAgent == null || userAgent.trim().isEmpty()) {
            return UserAgentDetails.builder()
                    .os("Unknown")
                    .browser("Unknown")
                    .deviceType("Desktop")
                    .build();
        }

        String uaLower = userAgent.toLowerCase();

        // 1. Detect bots
        if (uaLower.contains("googlebot") || uaLower.contains("bot") || uaLower.contains("curl")) {
            return UserAgentDetails.builder()
                    .os("Unknown")
                    .browser("Bot")
                    .deviceType("Bot")
                    .build();
        }

        // 2. Detect OS
        String os = "Unknown";
        if (uaLower.contains("iphone") || uaLower.contains("ipad") || uaLower.contains("ipod")) {
            os = "iOS";
        } else if (uaLower.contains("android")) {
            os = "Android";
        } else if (uaLower.contains("windows")) {
            os = "Windows";
        } else if (uaLower.contains("macintosh") || uaLower.contains("mac os x") || uaLower.contains("mac os")) {
            os = "macOS";
        } else if (uaLower.contains("linux")) {
            os = "Linux";
        }

        // 3. Detect Device Type
        String deviceType = "Desktop";
        if (uaLower.contains("ipad")) {
            deviceType = "Tablet";
        } else if (uaLower.contains("iphone") || uaLower.contains("ipod") || uaLower.contains("mobi")) {
            deviceType = "Mobile";
        } else if (uaLower.contains("tablet")) {
            deviceType = "Tablet";
        }

        // 4. Detect Browser
        String browser = "Unknown";
        if (uaLower.contains("edg")) {
            browser = "Edge";
        } else if (uaLower.contains("firefox")) {
            browser = "Firefox";
        } else if (uaLower.contains("chrome") && !uaLower.contains("safari")) {
            browser = "Chrome";
        } else if (uaLower.contains("safari") && uaLower.contains("chrome")) {
            browser = "Chrome";
        } else if (uaLower.contains("safari")) {
            browser = "Safari";
        }

        return UserAgentDetails.builder()
                .os(os)
                .browser(browser)
                .deviceType(deviceType)
                .build();
    }
}
