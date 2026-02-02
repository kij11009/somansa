# PRD Step 2: Kubernetes 클러스터 연결 및 리소스 조회

## 1. 개요

Kubernetes 클러스터에 안전하게 연결하고 리소스 정보를 조회하는 기능을 구현합니다.

## 2. Kubernetes Client 설정

### 2.1 의존성
```xml
<dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>kubernetes-client</artifactId>
    <version>6.10.0</version>
</dependency>
```

### 2.2 연결 방식
| 환경 | 연결 방식 | 설명 |
|------|----------|------|
| **In-Cluster** | ServiceAccount | Pod 내부에서 실행 시 자동 인증 |
| **Out-of-Cluster** | kubeconfig | 로컬 개발 시 ~/.kube/config 사용 |

### 2.3 Config 클래스
```java
@Configuration
public class K8sClientConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        Config config = new ConfigBuilder()
            .withRequestTimeout(30000)
            .withConnectionTimeout(10000)
            .build();
        return new KubernetesClientBuilder()
            .withConfig(config)
            .build();
    }
}
```

## 3. RBAC 권한 설정

### 3.1 ClusterRole (Read-only)
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: k8s-doctor-reader
rules:
  - apiGroups: [""]
    resources:
      - pods
      - pods/log
      - services
      - configmaps
      - secrets
      - events
      - nodes
      - namespaces
      - persistentvolumeclaims
    verbs: ["get", "list", "watch"]

  - apiGroups: ["apps"]
    resources:
      - deployments
      - replicasets
      - statefulsets
      - daemonsets
    verbs: ["get", "list", "watch"]

  - apiGroups: ["batch"]
    resources:
      - jobs
      - cronjobs
    verbs: ["get", "list", "watch"]

  - apiGroups: ["networking.k8s.io"]
    resources:
      - ingresses
      - networkpolicies
    verbs: ["get", "list", "watch"]
```

### 3.2 ServiceAccount Binding
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: k8s-doctor-binding
subjects:
  - kind: ServiceAccount
    name: k8s-doctor
    namespace: default
roleRef:
  kind: ClusterRole
  name: k8s-doctor-reader
  apiGroup: rbac.authorization.k8s.io
```

## 4. 리소스 조회 Service

### 4.1 K8sResourceService 인터페이스
```java
public interface K8sResourceService {
    // Pod 관련
    List<Pod> listPodsInNamespace(String namespace);
    Pod getPod(String namespace, String name);
    String getPodLogs(String namespace, String name, String containerName, int tailLines);
    List<Event> getPodEvents(String namespace, String name);

    // Deployment 관련
    List<Deployment> listDeploymentsInNamespace(String namespace);
    Deployment getDeployment(String namespace, String name);

    // Node 관련
    List<Node> listNodes();
    Node getNode(String name);

    // Namespace 관련
    List<Namespace> listNamespaces();
    ResourceQuota getResourceQuota(String namespace);
}
```

### 4.2 구현 예시
```java
@Service
@RequiredArgsConstructor
public class K8sResourceServiceImpl implements K8sResourceService {

    private final KubernetesClient client;

    @Override
    public List<Pod> listPodsInNamespace(String namespace) {
        return client.pods()
            .inNamespace(namespace)
            .list()
            .getItems();
    }

    @Override
    public String getPodLogs(String namespace, String name, String containerName, int tailLines) {
        return client.pods()
            .inNamespace(namespace)
            .withName(name)
            .inContainer(containerName)
            .tailingLines(tailLines)
            .getLog();
    }

    @Override
    public List<Event> getPodEvents(String namespace, String name) {
        return client.v1().events()
            .inNamespace(namespace)
            .withField("involvedObject.name", name)
            .withField("involvedObject.kind", "Pod")
            .list()
            .getItems()
            .stream()
            .sorted(Comparator.comparing(Event::getLastTimestamp).reversed())
            .limit(20)
            .collect(Collectors.toList());
    }
}
```

## 5. 데이터 모델

### 5.1 ResourceInfo DTO
```java
@Data
@Builder
public class ResourceInfo {
    private String kind;              // Pod, Deployment, Node
    private String namespace;
    private String name;
    private String status;            // Running, Failed, Pending
    private Map<String, String> labels;
    private LocalDateTime createdAt;
    private Map<String, Object> details;
}
```

### 5.2 PodDiagnosticInfo
```java
@Data
@Builder
public class PodDiagnosticInfo {
    private String namespace;
    private String name;
    private String phase;             // Running, Pending, Failed
    private String reason;            // CrashLoopBackOff, etc
    private Integer restartCount;
    private List<ContainerStatus> containerStatuses;
    private String logs;
    private List<EventInfo> events;
    private ResourceRequirements resources;
    private String nodeName;
}
```

### 5.3 EventInfo
```java
@Data
@Builder
public class EventInfo {
    private String type;              // Normal, Warning
    private String reason;
    private String message;
    private LocalDateTime timestamp;
    private Integer count;
}
```

## 6. 에러 처리

### 6.1 Custom Exception
```java
public class K8sResourceNotFoundException extends RuntimeException {
    public K8sResourceNotFoundException(String message) {
        super(message);
    }
}

public class K8sApiException extends RuntimeException {
    public K8sApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 6.2 Exception Handler
```java
@ControllerAdvice
public class K8sExceptionHandler {

    @ExceptionHandler(K8sResourceNotFoundException.class)
    public String handleResourceNotFound(K8sResourceNotFoundException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        return "error/404";
    }

    @ExceptionHandler(KubernetesClientException.class)
    public String handleK8sClientException(KubernetesClientException ex, Model model) {
        model.addAttribute("error", "Kubernetes API 호출 실패: " + ex.getMessage());
        return "error/500";
    }
}
```

## 7. 캐싱 전략

### 7.1 캐시 설정
```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
            "namespaces", "nodes", "deployments"
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100));
        return cacheManager;
    }
}
```

### 7.2 캐시 적용
```java
@Cacheable(value = "namespaces")
public List<Namespace> listNamespaces() {
    return client.namespaces().list().getItems();
}

@Cacheable(value = "nodes")
public List<Node> listNodes() {
    return client.nodes().list().getItems();
}
```

## 8. 테스트

### 8.1 Unit Test
```java
@SpringBootTest
class K8sResourceServiceTest {

    @MockBean
    private KubernetesClient client;

    @Autowired
    private K8sResourceService service;

    @Test
    void testListPodsInNamespace() {
        // Given
        String namespace = "default";
        PodList podList = new PodList();
        when(client.pods().inNamespace(namespace).list())
            .thenReturn(podList);

        // When
        List<Pod> pods = service.listPodsInNamespace(namespace);

        // Then
        assertNotNull(pods);
    }
}
```

## 9. 성능 고려사항

### 9.1 페이징
- 대량의 리소스 조회 시 페이징 처리
- Limit/Continue 토큰 활용

### 9.2 필터링
- Label Selector 활용
- Field Selector 활용

### 9.3 타임아웃
- API 호출 타임아웃: 30초
- 로그 조회 타임아웃: 10초

## 10. 보안 체크리스트

- [ ] Read-only 권한만 부여
- [ ] ServiceAccount 토큰 보안
- [ ] API 호출 로깅 (민감 정보 제외)
- [ ] Input validation (namespace, pod name)
- [ ] Rate limiting 구현

## 11. 다음 단계

Step 3에서는 수집한 리소스 정보를 기반으로 장애를 탐지하고 분류하는 엔진을 구현합니다.
