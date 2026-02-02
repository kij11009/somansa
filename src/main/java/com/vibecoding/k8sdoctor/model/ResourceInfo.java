package com.vibecoding.k8sdoctor.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Kubernetes 리소스 기본 정보 DTO
 */
@Data
@Builder
public class ResourceInfo {
    private String kind;              // Pod, Deployment, Node, Namespace
    private String namespace;
    private String name;
    private String status;            // Running, Failed, Pending, etc.
    private Map<String, String> labels;
    private LocalDateTime createdAt;
    private Map<String, Object> details;
}
