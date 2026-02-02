# PRD Step 6: 멀티클러스터 지원 (Optional)

## 1. 개요

여러 Kubernetes 클러스터를 관리하고, 클러스터 간 전환하여 진단할 수 있는 기능을 제공합니다.

## 2. 아키텍처

### 2.1 클러스터 관리 구조
```
┌─────────────────────────────────┐
│   K8s Doctor Application        │
│                                 │
│  ┌───────────────────────────┐  │
│  │ ClusterContextManager     │  │
│  │ - Active Cluster          │  │
│  │ - Cluster Registry        │  │
│  └───────────┬───────────────┘  │
│              │                  │
│  ┌───────────┴───────────────┐  │
│  │ KubernetesClientFactory   │  │
│  └───────────┬───────────────┘  │
└──────────────┼──────────────────┘
               │
       ┌───────┴────────┐
       │                │
       ▼                ▼
┌─────────────┐  ┌─────────────┐
│ Cluster A   │  │ Cluster B   │
│ (Production)│  │ (Staging)   │
└─────────────┘  └─────────────┘
```

## 3. 클러스터 설정 모델

### 3.1 ClusterConfig
```java
@Data
@Builder
public class ClusterConfig {
    private String id;                  // 클러스터 고유 ID
    private String name;                // 표시 이름 (예: production, staging)
    private String description;         // 설명
    private ClusterConnectionType type; // IN_CLUSTER, KUBECONFIG, SERVICE_ACCOUNT
    private String kubeconfigPath;      // kubeconfig 파일 경로
    private String context;             // kubeconfig context 이름
    private String apiServerUrl;        // API Server URL (직접 연결 시)
    private String token;               // ServiceAccount 토큰
    private String caCertData;          // CA 인증서
    private Map<String, String> tags;   // 태그 (environment, region 등)
    private boolean active;             // 현재 활성 클러스터 여부
}

public enum ClusterConnectionType {
    IN_CLUSTER,         // Pod 내부에서 실행
    KUBECONFIG,         // kubeconfig 파일 사용
    SERVICE_ACCOUNT,    // ServiceAccount 토큰 사용
    MANUAL              // 수동 설정 (URL + Token)
}
```

### 3.2 ClusterInfo
```java
@Data
@Builder
public class ClusterInfo {
    private String id;
    private String name;
    private String version;             // Kubernetes 버전
    private int nodeCount;
    private int namespaceCount;
    private ClusterHealth health;
    private LocalDateTime lastChecked;
}

public enum ClusterHealth {
    HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
}
```

## 4. 클러스터 관리 서비스

### 4.1 ClusterRegistry
```java
@Component
public class ClusterRegistry {

    private final Map<String, ClusterConfig> clusters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // application.properties에서 클러스터 설정 로드
        loadClustersFromConfig();
    }

    public void registerCluster(ClusterConfig config) {
        clusters.put(config.getId(), config);
    }

    public void unregisterCluster(String clusterId) {
        clusters.remove(clusterId);
    }

    public ClusterConfig getCluster(String clusterId) {
        return clusters.get(clusterId);
    }

    public List<ClusterConfig> listClusters() {
        return new ArrayList<>(clusters.values());
    }

    public Optional<ClusterConfig> getActiveCluster() {
        return clusters.values().stream()
            .filter(ClusterConfig::isActive)
            .findFirst();
    }

    public void setActiveCluster(String clusterId) {
        // 모든 클러스터를 비활성화
        clusters.values().forEach(c -> c.setActive(false));

        // 지정된 클러스터만 활성화
        ClusterConfig cluster = clusters.get(clusterId);
        if (cluster != null) {
            cluster.setActive(true);
        }
    }

    private void loadClustersFromConfig() {
        // application.properties에서 클러스터 설정 읽기
        // 예: k8s-doctor.clusters[0].name=production
        //     k8s-doctor.clusters[0].kubeconfig-path=/path/to/kubeconfig
        //     k8s-doctor.clusters[0].context=prod-context
    }
}
```

### 4.2 KubernetesClientFactory
```java
@Component
@RequiredArgsConstructor
public class KubernetesClientFactory {

    private final Map<String, KubernetesClient> clientCache = new ConcurrentHashMap<>();

    public KubernetesClient createClient(ClusterConfig config) {
        return clientCache.computeIfAbsent(config.getId(), id -> {
            try {
                return switch (config.getType()) {
                    case IN_CLUSTER -> createInClusterClient();
                    case KUBECONFIG -> createKubeconfigClient(config);
                    case SERVICE_ACCOUNT -> createServiceAccountClient(config);
                    case MANUAL -> createManualClient(config);
                };
            } catch (Exception e) {
                throw new ClusterConnectionException("클러스터 연결 실패: " + config.getName(), e);
            }
        });
    }

    private KubernetesClient createInClusterClient() {
        Config config = new ConfigBuilder()
            .withRequestTimeout(30000)
            .build();
        return new KubernetesClientBuilder()
            .withConfig(config)
            .build();
    }

    private KubernetesClient createKubeconfigClient(ClusterConfig clusterConfig) {
        Config config = Config.fromKubeconfig(
            readKubeconfig(clusterConfig.getKubeconfigPath())
        );

        if (clusterConfig.getContext() != null) {
            config.setCurrentContext(
                config.getContexts().stream()
                    .filter(ctx -> ctx.getName().equals(clusterConfig.getContext()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Context not found: " + clusterConfig.getContext()))
            );
        }

        return new KubernetesClientBuilder()
            .withConfig(config)
            .build();
    }

    private KubernetesClient createServiceAccountClient(ClusterConfig clusterConfig) {
        Config config = new ConfigBuilder()
            .withMasterUrl(clusterConfig.getApiServerUrl())
            .withOauthToken(clusterConfig.getToken())
            .withCaCertData(clusterConfig.getCaCertData())
            .withRequestTimeout(30000)
            .build();

        return new KubernetesClientBuilder()
            .withConfig(config)
            .build();
    }

    private KubernetesClient createManualClient(ClusterConfig clusterConfig) {
        return createServiceAccountClient(clusterConfig);
    }

    private String readKubeconfig(String path) {
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("kubeconfig 파일을 읽을 수 없습니다: " + path, e);
        }
    }

    public void closeClient(String clusterId) {
        KubernetesClient client = clientCache.remove(clusterId);
        if (client != null) {
            client.close();
        }
    }

    public void closeAllClients() {
        clientCache.values().forEach(KubernetesClient::close);
        clientCache.clear();
    }
}
```

### 4.3 ClusterContextManager
```java
@Component
@RequiredArgsConstructor
public class ClusterContextManager {

    private final ClusterRegistry clusterRegistry;
    private final KubernetesClientFactory clientFactory;
    private final ThreadLocal<String> currentCluster = new ThreadLocal<>();

    public KubernetesClient getCurrentClient() {
        String clusterId = getCurrentClusterId();
        ClusterConfig config = clusterRegistry.getCluster(clusterId);

        if (config == null) {
            throw new ClusterNotFoundException("클러스터를 찾을 수 없습니다: " + clusterId);
        }

        return clientFactory.createClient(config);
    }

    public String getCurrentClusterId() {
        String clusterId = currentCluster.get();

        if (clusterId == null) {
            // ThreadLocal에 없으면 활성 클러스터 사용
            return clusterRegistry.getActiveCluster()
                .map(ClusterConfig::getId)
                .orElseThrow(() -> new ClusterNotFoundException("활성 클러스터가 없습니다"));
        }

        return clusterId;
    }

    public void setCurrentCluster(String clusterId) {
        currentCluster.set(clusterId);
    }

    public void clearCurrentCluster() {
        currentCluster.remove();
    }

    public ClusterInfo getClusterInfo(String clusterId) {
        ClusterConfig config = clusterRegistry.getCluster(clusterId);
        if (config == null) {
            throw new ClusterNotFoundException("클러스터를 찾을 수 없습니다: " + clusterId);
        }

        KubernetesClient client = clientFactory.createClient(config);

        try {
            VersionInfo version = client.getKubernetesVersion();
            List<Node> nodes = client.nodes().list().getItems();
            List<Namespace> namespaces = client.namespaces().list().getItems();

            ClusterHealth health = calculateClusterHealth(client);

            return ClusterInfo.builder()
                .id(config.getId())
                .name(config.getName())
                .version(version.getGitVersion())
                .nodeCount(nodes.size())
                .namespaceCount(namespaces.size())
                .health(health)
                .lastChecked(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            return ClusterInfo.builder()
                .id(config.getId())
                .name(config.getName())
                .health(ClusterHealth.UNKNOWN)
                .lastChecked(LocalDateTime.now())
                .build();
        }
    }

    private ClusterHealth calculateClusterHealth(KubernetesClient client) {
        try {
            List<Node> nodes = client.nodes().list().getItems();

            long healthyNodes = nodes.stream()
                .filter(node -> node.getStatus().getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus())))
                .count();

            if (healthyNodes == nodes.size()) {
                return ClusterHealth.HEALTHY;
            } else if (healthyNodes > 0) {
                return ClusterHealth.DEGRADED;
            } else {
                return ClusterHealth.UNHEALTHY;
            }
        } catch (Exception e) {
            return ClusterHealth.UNKNOWN;
        }
    }
}
```

## 5. 멀티클러스터 지원 Controller

### 5.1 ClusterController
```java
@Controller
@RequestMapping("/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterRegistry clusterRegistry;
    private final ClusterContextManager contextManager;

    @GetMapping
    public String listClusters(Model model) {
        List<ClusterConfig> clusters = clusterRegistry.listClusters();

        List<ClusterInfo> clusterInfos = clusters.stream()
            .map(c -> contextManager.getClusterInfo(c.getId()))
            .collect(Collectors.toList());

        model.addAttribute("clusters", clusterInfos);

        return "clusters/list";
    }

    @PostMapping("/switch/{clusterId}")
    public String switchCluster(@PathVariable String clusterId) {
        clusterRegistry.setActiveCluster(clusterId);
        return "redirect:/";
    }

    @GetMapping("/add")
    public String addClusterForm(Model model) {
        model.addAttribute("clusterConfig", new ClusterConfig());
        return "clusters/add";
    }

    @PostMapping("/add")
    public String addCluster(@ModelAttribute ClusterConfig config) {
        config.setId(UUID.randomUUID().toString());
        clusterRegistry.registerCluster(config);
        return "redirect:/clusters";
    }

    @PostMapping("/remove/{clusterId}")
    public String removeCluster(@PathVariable String clusterId) {
        clusterRegistry.unregisterCluster(clusterId);
        return "redirect:/clusters";
    }
}
```

### 5.2 클러스터 인터셉터
```java
@Component
@RequiredArgsConstructor
public class ClusterInterceptor implements HandlerInterceptor {

    private final ClusterContextManager contextManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clusterId = request.getParameter("cluster");

        if (clusterId != null) {
            contextManager.setCurrentCluster(clusterId);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        contextManager.clearCurrentCluster();
    }
}
```

## 6. Configuration

### 6.1 application.properties
```properties
# 멀티클러스터 설정
k8s-doctor.clusters[0].id=prod
k8s-doctor.clusters[0].name=Production
k8s-doctor.clusters[0].type=KUBECONFIG
k8s-doctor.clusters[0].kubeconfig-path=/path/to/prod-kubeconfig
k8s-doctor.clusters[0].context=prod-context
k8s-doctor.clusters[0].active=true

k8s-doctor.clusters[1].id=staging
k8s-doctor.clusters[1].name=Staging
k8s-doctor.clusters[1].type=KUBECONFIG
k8s-doctor.clusters[1].kubeconfig-path=/path/to/staging-kubeconfig
k8s-doctor.clusters[1].context=staging-context
k8s-doctor.clusters[1].active=false
```

### 6.2 클러스터 설정 Properties
```java
@Configuration
@ConfigurationProperties(prefix = "k8s-doctor")
@Data
public class MultiClusterProperties {
    private List<ClusterConfigProperties> clusters = new ArrayList<>();

    @Data
    public static class ClusterConfigProperties {
        private String id;
        private String name;
        private String description;
        private ClusterConnectionType type;
        private String kubeconfigPath;
        private String context;
        private String apiServerUrl;
        private String token;
        private String caCertData;
        private boolean active;
    }

    @Bean
    public ClusterRegistry clusterRegistry() {
        ClusterRegistry registry = new ClusterRegistry();

        for (ClusterConfigProperties props : clusters) {
            ClusterConfig config = ClusterConfig.builder()
                .id(props.getId())
                .name(props.getName())
                .description(props.getDescription())
                .type(props.getType())
                .kubeconfigPath(props.getKubeconfigPath())
                .context(props.getContext())
                .apiServerUrl(props.getApiServerUrl())
                .token(props.getToken())
                .caCertData(props.getCaCertData())
                .active(props.isActive())
                .build();

            registry.registerCluster(config);
        }

        return registry;
    }
}
```

## 7. UI 컴포넌트

### 7.1 클러스터 선택 드롭다운
```html
<!-- 모든 페이지의 헤더에 추가 -->
<div class="dropdown">
    <button class="btn btn-secondary dropdown-toggle" type="button" data-bs-toggle="dropdown">
        <i class="bi bi-hdd-network"></i> <span th:text="${currentCluster.name}">Cluster</span>
    </button>
    <ul class="dropdown-menu">
        <li th:each="cluster : ${clusters}">
            <a class="dropdown-item" th:href="@{/clusters/switch/{id}(id=${cluster.id})}">
                <span th:text="${cluster.name}">Cluster Name</span>
                <span th:if="${cluster.active}" class="badge bg-success">Active</span>
            </a>
        </li>
        <li><hr class="dropdown-divider"></li>
        <li><a class="dropdown-item" th:href="@{/clusters}">클러스터 관리</a></li>
    </ul>
</div>
```

### 7.2 클러스터 목록 페이지
```html
<div class="row">
    <div class="col-md-4" th:each="cluster : ${clusters}">
        <div class="card">
            <div class="card-header">
                <h5 th:text="${cluster.name}">Cluster</h5>
                <span th:class="${'badge bg-' + (cluster.health == 'HEALTHY' ? 'success' : cluster.health == 'DEGRADED' ? 'warning' : 'danger')}"
                      th:text="${cluster.health}">Health</span>
            </div>
            <div class="card-body">
                <p><strong>버전:</strong> <span th:text="${cluster.version}">-</span></p>
                <p><strong>노드 수:</strong> <span th:text="${cluster.nodeCount}">0</span></p>
                <p><strong>네임스페이스 수:</strong> <span th:text="${cluster.namespaceCount}">0</span></p>

                <div class="btn-group w-100">
                    <a th:href="@{/clusters/switch/{id}(id=${cluster.id})}" class="btn btn-primary">
                        <i class="bi bi-arrow-right-circle"></i> 선택
                    </a>
                    <a th:href="@{/?cluster={id}(id=${cluster.id})}" class="btn btn-info">
                        <i class="bi bi-search"></i> 진단
                    </a>
                </div>
            </div>
        </div>
    </div>
</div>
```

## 8. Exception 처리

```java
public class ClusterNotFoundException extends RuntimeException {
    public ClusterNotFoundException(String message) {
        super(message);
    }
}

public class ClusterConnectionException extends RuntimeException {
    public ClusterConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 9. 보안 고려사항

- kubeconfig 파일 암호화 저장
- ServiceAccount 토큰 암호화
- 클러스터별 접근 권한 관리 (RBAC)
- 감사 로그 기록

## 10. 다음 단계

Step 7에서는 테스트, 모니터링, 배포 전략을 수립합니다.
