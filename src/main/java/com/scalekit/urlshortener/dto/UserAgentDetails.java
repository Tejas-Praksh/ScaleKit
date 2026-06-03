package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of parsing a user agent string.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAgentDetails {
    private String os;
    private String browser;
    private String deviceType;
}
