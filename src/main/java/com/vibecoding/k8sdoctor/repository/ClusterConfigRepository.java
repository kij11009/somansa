package com.vibecoding.k8sdoctor.repository;

import com.vibecoding.k8sdoctor.model.ClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 클러스터 설정 저장소 (JPA)
 */
@Repository
public interface ClusterConfigRepository extends JpaRepository<ClusterConfig, String> {
}
