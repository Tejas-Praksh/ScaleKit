package com.scalekit.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a geolocation lookup from an IP address.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoLocationDetails {
    private String country;
    private String city;
}
