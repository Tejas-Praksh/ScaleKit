package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.UserAgentDetails;

/**
 * Service for parsing raw HTTP User-Agent headers into structured details.
 */
public interface UserAgentParserService {

    /**
     * Parses the user agent string.
     *
     * @param userAgent HTTP User-Agent string
     * @return parsed details
     */
    UserAgentDetails parse(String userAgent);
}
