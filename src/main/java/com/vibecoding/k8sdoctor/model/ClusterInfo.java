package com.vibecoding.k8sdoctor.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 클러스터 정보 모델
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cluster_info")
public class ClusterInfo {
    @Id
    private String id;                  // UUID

    @Column(nullable = false)
    private String name;                // 클러스터 이름

    @Column(length = 1000)
    private String description;         // 설명

    @Column(length = 500)
    private String apiServerUrl;        // Kubernetes API 서버 URL

    private String version;             // Kubernetes 버전

    @Enumerated(EnumType.STRING)
    private ClusterStatus status;       // 연결 상태

    private LocalDateTime createdAt;    // 등록 시간
    private LocalDateTime lastChecked;  // 마지막 연결 확인 시간
    private Integer nodeCount;          // 노드 수
    private Integer namespaceCount;     // 네임스페이스 수
    private Integer podCount;           // Pod 수
}
