package com.scalekit.urlshortener.service;

import com.scalekit.urlshortener.dto.GeoLocationDetails;

/**
 * Service for looking up geographic location details (Country, City) from client IP addresses.
 */
public interface GeoIpService {

    /**
     * Resolves the country and city of the provided IP address.
     *
     * @param ipAddress client IP address
     * @return geolocation details, never null
     */
    GeoLocationDetails lookup(String ipAddress);
}
