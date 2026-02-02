package com.vibecoding.k8sdoctor.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 클러스터 연결 설정
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cluster_config")
public class ClusterConfig {
    @Id
    private String id;                  // 클러스터 ID

    @Column(nullable = false)
    private String name;                // 클러스터 이름

    @Column(length = 1000)
    private String description;         // 설명

    @Column(nullable = false, length = 500)
    private String apiServerUrl;        // API 서버 URL

    @Column(nullable = false, length = 10000)
    private String token;               // Service Account 토큰

    private String namespace;           // 기본 네임스페이스 (선택적)
}
