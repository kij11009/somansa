package com.vibecoding.k8sdoctor.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Pod 진단 정보 DTO
 */
@Data
@Builder
public class PodDiagnosticInfo {
    private String namespace;
    private String name;
    private String phase;             // Running, Pending, Failed, Succeeded, Unknown
    private String reason;            // CrashLoopBackOff, ImagePullBackOff, etc.
    private Integer restartCount;
    private List<ContainerStatusInfo> containerStatuses;
    private String logs;              // 최근 로그
    private List<EventInfo> events;   // Pod 관련 이벤트
    private Map<String, String> resourceRequests;
    private Map<String, String> resourceLimits;
    private String nodeName;
    private Map<String, String> labels;
    private Map<String, String> annotations;
}
