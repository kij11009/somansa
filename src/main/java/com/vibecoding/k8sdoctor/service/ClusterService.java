package com.vibecoding.k8sdoctor.service;

import com.vibecoding.k8sdoctor.model.ClusterConfig;
import com.vibecoding.k8sdoctor.model.ClusterInfo;
import com.vibecoding.k8sdoctor.model.ClusterStatus;
import com.vibecoding.k8sdoctor.repository.ClusterRepository;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 클러스터 관리 서비스
 */
@Service
@RequiredArgsConstructor
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private final ClusterRepository clusterRepository;

    /**
     * 애플리케이션 시작 시 DB에서 클러스터 로드 및 KubernetesClient 재생성
     */
    @PostConstruct
    public void initializeClusters() {
        log.info("Initializing clusters from database...");

        List<ClusterInfo> clusters = clusterRepository.findAllInfos();
        log.info("Found {} clusters in database", clusters.size());

        for (ClusterInfo info : clusters) {
            try {
                // ClusterConfig 로드
                Optional<ClusterConfig> configOpt = clusterRepository.findConfigById(info.getId());
                if (configOpt.isEmpty()) {
                    log.warn("ClusterConfig not found for cluster: {}", info.getId());
                    continue;
                }

                ClusterConfig config = configOpt.get();

                // KubernetesClient 재생성
                KubernetesClient client = createKubernetesClient(config);
                clusterRepository.saveClient(info.getId(), client);

                log.info("Successfully initialized cluster: {} ({})", info.getName(), info.getId());
            } catch (Exception e) {
                log.error("Failed to initialize cluster: {} ({})", info.getName(), info.getId(), e);
                // 초기화 실패해도 계속 진행 (다른 클러스터들은 초기화)
            }
        }

        log.info("Cluster initialization completed");
    }

    /**
     * 클러스터 등록
     */
    public ClusterInfo registerCluster(ClusterConfig config) {
        log.info("Registering cluster: {}", config.getName());

        // ID 생성
        String clusterId = UUID.randomUUID().toString();
        config.setId(clusterId);

        try {
            // Kubernetes 클라이언트 생성
            KubernetesClient client = createKubernetesClient(config);

            // 연결 테스트 및 정보 수집
            ClusterInfo info = collectClusterInfo(clusterId, config, client);

            // 저장
            clusterRepository.saveConfig(config);
            clusterRepository.saveInfo(info);
            clusterRepository.saveClient(clusterId, client);

            log.info("Successfully registered cluster: {} (ID: {})", config.getName(), clusterId);
            return info;

        } catch (Exception e) {
            log.error("Failed to register cluster: {}", config.getName(), e);
            // 등록 실패 시 저장하지 않고 바로 예외를 던짐
            throw new RuntimeException("Failed to connect to cluster: " + e.getMessage(), e);
        }
    }

    /**
     * Kubernetes 클라이언트 생성
     */
    private KubernetesClient createKubernetesClient(ClusterConfig config) {
        try {
            // API 서버 URL과 토큰으로 설정
            // autoConfigure(false)로 설정하여 ~/.kube/config 자동 로드 방지
            Config k8sConfig = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withAutoConfigure(false)  // 자동 설정 비활성화 - ~/.kube/config 읽지 않음
                .withMasterUrl(config.getApiServerUrl())
                .withOauthToken(config.getToken())
                .withTrustCerts(true) // 자체 서명 인증서 허용 (프로덕션에서는 false 권장)
                .withRequestTimeout(30000)  // 30초
                .withConnectionTimeout(10000) // 10초
                .build();

            return new KubernetesClientBuilder()
                .withConfig(k8sConfig)
                .build();

        } catch (Exception e) {
            log.error("Failed to create Kubernetes client", e);
            throw new RuntimeException("Invalid cluster configuration: " + e.getMessage(), e);
        }
    }

    /**
     * 클러스터 정보 수집
     */
    private ClusterInfo collectClusterInfo(String clusterId, ClusterConfig config, KubernetesClient client) {
        try {
            // 버전 정보
            String version = client.getKubernetesVersion().getGitVersion();

            // 노드 수
            int nodeCount = client.nodes().list().getItems().size();

            // 네임스페이스 수
            int namespaceCount = client.namespaces().list().getItems().size();

            // Pod 수 (모든 네임스페이스)
            int podCount = client.pods().inAnyNamespace().list().getItems().size();

            // API 서버 URL
            String apiServerUrl = client.getConfiguration().getMasterUrl();

            return ClusterInfo.builder()
                .id(clusterId)
                .name(config.getName())
                .description(config.getDescription())
                .apiServerUrl(apiServerUrl)
                .version(version)
                .status(ClusterStatus.CONNECTED)
                .createdAt(LocalDateTime.now())
                .lastChecked(LocalDateTime.now())
                .nodeCount(nodeCount)
                .namespaceCount(namespaceCount)
                .podCount(podCount)
                .build();

        } catch (KubernetesClientException e) {
            log.error("Failed to collect cluster info", e);
            throw new RuntimeException("Failed to connect to cluster", e);
        }
    }

    /**
     * 클러스터 목록 조회
     */
    public List<ClusterInfo> listClusters() {
        return clusterRepository.findAllInfos();
    }

    /**
     * 클러스터 조회
     */
    public Optional<ClusterInfo> getCluster(String clusterId) {
        return clusterRepository.findInfoById(clusterId);
    }

    /**
     * Kubernetes 클라이언트 조회
     */
    public Optional<KubernetesClient> getKubernetesClient(String clusterId) {
        return clusterRepository.findClientById(clusterId);
    }

    /**
     * 클러스터 삭제
     */
    public void deleteCluster(String clusterId) {
        log.info("Deleting cluster: {}", clusterId);
        clusterRepository.deleteById(clusterId);
    }

    /**
     * 클러스터 연결 테스트
     */
    public ClusterInfo testConnection(String clusterId) {
        log.info("Testing cluster connection: {}", clusterId);

        Optional<KubernetesClient> clientOpt = clusterRepository.findClientById(clusterId);
        Optional<ClusterConfig> configOpt = clusterRepository.findConfigById(clusterId);
        Optional<ClusterInfo> infoOpt = clusterRepository.findInfoById(clusterId);

        if (clientOpt.isEmpty() || configOpt.isEmpty() || infoOpt.isEmpty()) {
            throw new RuntimeException("Cluster not found: " + clusterId);
        }

        KubernetesClient client = clientOpt.get();
        ClusterConfig config = configOpt.get();
        ClusterInfo info = infoOpt.get();

        try {
            // 연결 테스트
            ClusterInfo updatedInfo = collectClusterInfo(clusterId, config, client);
            clusterRepository.saveInfo(updatedInfo);

            log.info("Cluster connection test successful: {}", clusterId);
            return updatedInfo;

        } catch (Exception e) {
            log.error("Cluster connection test failed: {}", clusterId, e);

            // 에러 상태로 업데이트
            info.setStatus(ClusterStatus.ERROR);
            info.setLastChecked(LocalDateTime.now());
            clusterRepository.saveInfo(info);

            throw new RuntimeException("Connection test failed: " + e.getMessage(), e);
        }
    }

    /**
     * 클러스터 정보 갱신
     */
    public ClusterInfo refreshClusterInfo(String clusterId) {
        return testConnection(clusterId);
    }

    /**
     * 클러스터 정보 갱신 (필요 시)
     * 마지막 체크 후 1분 이상 경과했으면 갱신
     */
    public void refreshClusterIfNeeded(String clusterId) {
        Optional<ClusterInfo> infoOpt = clusterRepository.findInfoById(clusterId);
        if (infoOpt.isEmpty()) {
            return;
        }

        ClusterInfo info = infoOpt.get();

        // 마지막 체크가 없거나, 1분 이상 경과했으면 갱신
        if (info.getLastChecked() == null ||
            info.getLastChecked().plusMinutes(1).isBefore(LocalDateTime.now())) {

            try {
                refreshClusterInfo(clusterId);
                log.debug("Auto-refreshed cluster: {} ({})", info.getName(), clusterId);
            } catch (Exception e) {
                log.warn("Failed to auto-refresh cluster: {} ({}). Error: {}",
                    info.getName(), clusterId, e.getMessage());
            }
        }
    }

    /**
     * 모든 클러스터 정보 갱신 (필요 시)
     */
    public void refreshAllClustersIfNeeded() {
        List<ClusterInfo> clusters = listClusters();

        if (clusters.isEmpty()) {
            return;
        }

        log.debug("Checking {} clusters for refresh...", clusters.size());

        for (ClusterInfo cluster : clusters) {
            refreshClusterIfNeeded(cluster.getId());
        }
    }
}
