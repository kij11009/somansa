package com.vibecoding.k8sdoctor.repository;

import com.vibecoding.k8sdoctor.model.ClusterInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 클러스터 정보 저장소 (JPA)
 */
@Repository
public interface ClusterInfoRepository extends JpaRepository<ClusterInfo, String> {
}
