package com.vibecoding.k8sdoctor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 탐지된 장애 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultInfo {
    private FaultType faultType;
    private Severity severity;
    private String resourceKind;      // Pod, Deployment, Node
    private String namespace;
    private String resourceName;
    private String summary;           // 한 줄 요약
    private String description;       // 상세 설명
    private List<String> symptoms;    // 증상 목록
    private Map<String, Object> context; // 추가 컨텍스트
    private LocalDateTime detectedAt;
}
