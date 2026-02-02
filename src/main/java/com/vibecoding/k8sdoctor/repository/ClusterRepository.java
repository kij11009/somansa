package com.vibecoding.k8sdoctor.repository;

import com.vibecoding.k8sdoctor.model.ClusterConfig;
import com.vibecoding.k8sdoctor.model.ClusterInfo;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클러스터 정보 저장소 (하이브리드: DB + 인메모리)
 * - ClusterConfig, ClusterInfo는 DB에 저장 (JPA)
 * - KubernetesClient는 인메모리에 저장 (직렬화 불가능)
 */

@Repository
@RequiredArgsConstructor
public class ClusterRepository {

    private static final Logger log = LoggerFactory.getLogger(ClusterRepository.class);

    private final ClusterConfigRepository clusterConfigRepository;
    private final ClusterInfoRepository clusterInfoRepository;

    // Kubernetes 클라이언트 저장 (ID -> KubernetesClient) - 인메모리만
    private final Map<String, KubernetesClient> kubernetesClients = new ConcurrentHashMap<>();

    /**
     * 클러스터 설정 저장
     */
    public void saveConfig(ClusterConfig config) {
        clusterConfigRepository.save(config);
        log.info("Saved cluster config to DB: {}", config.getId());
    }

    /**
     * 클러스터 정보 저장
     */
    public void saveInfo(ClusterInfo info) {
        clusterInfoRepository.save(info);
        log.info("Saved cluster info to DB: {}", info.getId());
    }

    /**
     * Kubernetes 클라이언트 저장 (인메모리만)
     */
    public void saveClient(String clusterId, KubernetesClient client) {
        kubernetesClients.put(clusterId, client);
        log.info("Saved Kubernetes client to memory: {}", clusterId);
    }

    /**
     * 클러스터 설정 조회
     */
    public Optional<ClusterConfig> findConfigById(String id) {
        return clusterConfigRepository.findById(id);
    }

    /**
     * 클러스터 정보 조회
     */
    public Optional<ClusterInfo> findInfoById(String id) {
        return clusterInfoRepository.findById(id);
    }

    /**
     * Kubernetes 클라이언트 조회
     */
    public Optional<KubernetesClient> findClientById(String id) {
        return Optional.ofNullable(kubernetesClients.get(id));
    }

    /**
     * 모든 클러스터 정보 조회
     */
    public List<ClusterInfo> findAllInfos() {
        return clusterInfoRepository.findAll();
    }

    /**
     * 클러스터 삭제
     */
    public void deleteById(String id) {
        clusterConfigRepository.deleteById(id);
        clusterInfoRepository.deleteById(id);

        // Kubernetes 클라이언트 종료
        KubernetesClient client = kubernetesClients.remove(id);
        if (client != null) {
            try {
                client.close();
                log.info("Closed Kubernetes client: {}", id);
            } catch (Exception e) {
                log.warn("Failed to close Kubernetes client: {}", id, e);
            }
        }

        log.info("Deleted cluster from DB: {}", id);
    }

    /**
     * 클러스터 존재 여부 확인
     */
    public boolean existsById(String id) {
        return clusterConfigRepository.existsById(id);
    }

    /**
     * 클러스터 개수
     */
    public long count() {
        return clusterInfoRepository.count();
    }
}
