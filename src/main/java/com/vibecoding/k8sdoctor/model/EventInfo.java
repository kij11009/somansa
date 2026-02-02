package com.vibecoding.k8sdoctor.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Kubernetes Event 정보 DTO
 */
@Data
@Builder
public class EventInfo {
    private String type;              // Normal, Warning
    private String reason;            // Scheduled, Pulling, Created, Started, Killing, etc.
    private String message;
    private LocalDateTime timestamp;
    private Integer count;
    private String component;         // kubelet, scheduler, etc.
}
