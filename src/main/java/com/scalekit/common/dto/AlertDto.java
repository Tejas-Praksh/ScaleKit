package com.scalekit.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertDto {
    private String type; // WARNING/CRITICAL
    private String component;
    private String message;
    private Instant detectedAt;
}
