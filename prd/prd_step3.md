# PRD Step 3: 장애 탐지 및 분류 엔진

## 1. 개요

Kubernetes 리소스의 상태를 분석하여 장애를 자동으로 탐지하고 유형별로 분류합니다.

## 2. 장애 탐지 아키텍처

### 2.1 탐지 플로우
```
리소스 정보 수집
    ↓
상태 분석
    ↓
장애 감지 여부 판단
    ↓
장애 유형 분류
    ↓
상세 정보 수집 (로그, 이벤트)
    ↓
진단 결과 생성
```

### 2.2 핵심 컴포넌트
- **FaultDetector**: 장애 탐지 인터페이스
- **FaultClassifier**: 장애 분류 엔진
- **DiagnosticCollector**: 진단 정보 수집기
- **FaultAnalyzer**: 장애 분석 통합 서비스

## 3. 장애 유형 정의

### 3.1 장애 타입 Enum
```java
public enum FaultType {
    CRASH_LOOP_BACK_OFF("CrashLoopBackOff", "컨테이너가 반복적으로 재시작됨", Severity.CRITICAL),
    IMAGE_PULL_BACK_OFF("ImagePullBackOff", "컨테이너 이미지를 가져올 수 없음", Severity.CRITICAL),
    OOM_KILLED("OOMKilled", "메모리 부족으로 컨테이너 종료됨", Severity.CRITICAL),
    PENDING("Pending", "Pod이 스케줄링되지 않음", Severity.HIGH),
    ERROR("Error", "일반적인 에러 상태", Severity.HIGH),

    LIVENESS_PROBE_FAILED("LivenessProbeFailed", "Liveness Probe 실패", Severity.HIGH),
    READINESS_PROBE_FAILED("ReadinessProbeFailed", "Readiness Probe 실패", Severity.MEDIUM),
    STARTUP_PROBE_FAILED("StartupProbeFailed", "Startup Probe 실패", Severity.HIGH),

    CONFIG_ERROR("ConfigError", "ConfigMap/Secret 마운트 실패", Severity.HIGH),
    PVC_ERROR("PVCError", "PersistentVolumeClaim 바인딩 실패", Severity.HIGH),
    NETWORK_ERROR("NetworkError", "네트워크 연결 실패", Severity.MEDIUM),

    RESOURCE_QUOTA_EXCEEDED("ResourceQuotaExceeded", "리소스 쿼터 초과", Severity.HIGH),
    INSUFFICIENT_RESOURCES("InsufficientResources", "노드 리소스 부족", Severity.HIGH),

    NODE_NOT_READY("NodeNotReady", "노드가 Ready 상태가 아님", Severity.CRITICAL),
    NODE_PRESSURE("NodePressure", "노드에 리소스 압박", Severity.MEDIUM),

    DEPLOYMENT_UNAVAILABLE("DeploymentUnavailable", "Deployment의 Pod이 준비되지 않음", Severity.HIGH),

    UNKNOWN("Unknown", "알 수 없는 장애", Severity.LOW);

    private final String code;
    private final String description;
    private final Severity severity;

    // constructor, getters
}

public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW
}
```

### 3.2 FaultInfo 모델
```java
@Data
@Builder
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
```

## 4. 장애 탐지 전략 (Strategy Pattern)

### 4.1 FaultDetector 인터페이스
```java
public interface FaultDetector {
    boolean canDetect(ResourceInfo resource);
    List<FaultInfo> detect(ResourceInfo resource);
    FaultType getFaultType();
}
```

### 4.2 Pod 장애 탐지기

#### 4.2.1 CrashLoopBackOff Detector
```java
@Component
public class CrashLoopBackOffDetector implements FaultDetector {

    @Override
    public boolean canDetect(ResourceInfo resource) {
        return "Pod".equals(resource.getKind());
    }

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Pod pod = (Pod) resource.getDetails().get("pod");
        List<FaultInfo> faults = new ArrayList<>();

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (status.getState() != null &&
                status.getState().getWaiting() != null &&
                "CrashLoopBackOff".equals(status.getState().getWaiting().getReason())) {

                faults.add(FaultInfo.builder()
                    .faultType(FaultType.CRASH_LOOP_BACK_OFF)
                    .severity(Severity.CRITICAL)
                    .resourceKind("Pod")
                    .namespace(pod.getMetadata().getNamespace())
                    .resourceName(pod.getMetadata().getName())
                    .summary(String.format("컨테이너 '%s'가 반복적으로 재시작됨 (재시작 횟수: %d)",
                        status.getName(), status.getRestartCount()))
                    .symptoms(Arrays.asList(
                        "컨테이너가 시작 후 즉시 종료됨",
                        "재시작 횟수가 계속 증가함",
                        "Pod이 Running 상태로 전환되지 않음"
                    ))
                    .context(Map.of(
                        "containerName", status.getName(),
                        "restartCount", status.getRestartCount(),
                        "lastState", status.getLastState()
                    ))
                    .detectedAt(LocalDateTime.now())
                    .build());
            }
        }

        return faults;
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.CRASH_LOOP_BACK_OFF;
    }
}
```

#### 4.2.2 ImagePullBackOff Detector
```java
@Component
public class ImagePullBackOffDetector implements FaultDetector {

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Pod pod = (Pod) resource.getDetails().get("pod");
        List<FaultInfo> faults = new ArrayList<>();

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (isImagePullError(status)) {
                faults.add(createImagePullFault(pod, status));
            }
        }

        return faults;
    }

    private boolean isImagePullError(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason);
        }
        return false;
    }

    private FaultInfo createImagePullFault(Pod pod, ContainerStatus status) {
        String message = status.getState().getWaiting().getMessage();

        return FaultInfo.builder()
            .faultType(FaultType.IMAGE_PULL_BACK_OFF)
            .severity(Severity.CRITICAL)
            .resourceKind("Pod")
            .namespace(pod.getMetadata().getNamespace())
            .resourceName(pod.getMetadata().getName())
            .summary(String.format("이미지를 가져올 수 없음: %s", status.getImage()))
            .description(message)
            .symptoms(Arrays.asList(
                "컨테이너가 시작되지 않음",
                "이미지 Pull 실패",
                "ImagePullBackOff 또는 ErrImagePull 상태"
            ))
            .context(Map.of(
                "containerName", status.getName(),
                "image", status.getImage(),
                "errorMessage", message
            ))
            .detectedAt(LocalDateTime.now())
            .build();
    }
}
```

#### 4.2.3 OOMKilled Detector
```java
@Component
public class OOMKilledDetector implements FaultDetector {

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Pod pod = (Pod) resource.getDetails().get("pod");
        List<FaultInfo> faults = new ArrayList<>();

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (isOOMKilled(status)) {
                faults.add(createOOMFault(pod, status));
            }
        }

        return faults;
    }

    private boolean isOOMKilled(ContainerStatus status) {
        if (status.getLastState() != null &&
            status.getLastState().getTerminated() != null) {
            return "OOMKilled".equals(status.getLastState().getTerminated().getReason());
        }
        return false;
    }

    private FaultInfo createOOMFault(Pod pod, ContainerStatus status) {
        ContainerStateTerminated terminated = status.getLastState().getTerminated();

        return FaultInfo.builder()
            .faultType(FaultType.OOM_KILLED)
            .severity(Severity.CRITICAL)
            .resourceKind("Pod")
            .namespace(pod.getMetadata().getNamespace())
            .resourceName(pod.getMetadata().getName())
            .summary(String.format("메모리 부족으로 컨테이너 종료됨: %s", status.getName()))
            .description("컨테이너가 메모리 제한을 초과하여 강제 종료되었습니다.")
            .symptoms(Arrays.asList(
                "컨테이너가 갑자기 종료됨",
                "종료 코드: 137",
                "메모리 사용량이 제한에 도달함"
            ))
            .context(Map.of(
                "containerName", status.getName(),
                "exitCode", terminated.getExitCode(),
                "restartCount", status.getRestartCount()
            ))
            .detectedAt(LocalDateTime.now())
            .build();
    }
}
```

#### 4.2.4 Pending Detector
```java
@Component
@RequiredArgsConstructor
public class PendingDetector implements FaultDetector {

    private final K8sResourceService k8sService;

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Pod pod = (Pod) resource.getDetails().get("pod");

        if (!"Pending".equals(pod.getStatus().getPhase())) {
            return Collections.emptyList();
        }

        // Event 분석하여 Pending 이유 파악
        List<Event> events = k8sService.getPodEvents(
            pod.getMetadata().getNamespace(),
            pod.getMetadata().getName()
        );

        String reason = analyzeReason(events);
        List<String> symptoms = extractSymptoms(events);

        return List.of(FaultInfo.builder()
            .faultType(FaultType.PENDING)
            .severity(Severity.HIGH)
            .resourceKind("Pod")
            .namespace(pod.getMetadata().getNamespace())
            .resourceName(pod.getMetadata().getName())
            .summary("Pod이 스케줄링되지 않음")
            .description(reason)
            .symptoms(symptoms)
            .context(Map.of("events", events))
            .detectedAt(LocalDateTime.now())
            .build());
    }

    private String analyzeReason(List<Event> events) {
        for (Event event : events) {
            if ("FailedScheduling".equals(event.getReason())) {
                return event.getMessage();
            }
        }
        return "알 수 없는 이유로 스케줄링 실패";
    }

    private List<String> extractSymptoms(List<Event> events) {
        return events.stream()
            .filter(e -> "Warning".equals(e.getType()))
            .map(Event::getMessage)
            .limit(5)
            .collect(Collectors.toList());
    }
}
```

#### 4.2.5 Probe Failure Detector
```java
@Component
@RequiredArgsConstructor
public class ProbeFailureDetector implements FaultDetector {

    private final K8sResourceService k8sService;

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Pod pod = (Pod) resource.getDetails().get("pod");
        List<Event> events = k8sService.getPodEvents(
            pod.getMetadata().getNamespace(),
            pod.getMetadata().getName()
        );

        List<FaultInfo> faults = new ArrayList<>();

        // Liveness Probe 실패
        if (hasProbeFailure(events, "Unhealthy", "Liveness")) {
            faults.add(createProbeFault(pod, FaultType.LIVENESS_PROBE_FAILED, events));
        }

        // Readiness Probe 실패
        if (hasProbeFailure(events, "Unhealthy", "Readiness")) {
            faults.add(createProbeFault(pod, FaultType.READINESS_PROBE_FAILED, events));
        }

        return faults;
    }

    private boolean hasProbeFailure(List<Event> events, String reason, String probeType) {
        return events.stream()
            .anyMatch(e -> reason.equals(e.getReason()) &&
                          e.getMessage().contains(probeType));
    }

    private FaultInfo createProbeFault(Pod pod, FaultType faultType, List<Event> events) {
        String probeMessage = events.stream()
            .filter(e -> "Unhealthy".equals(e.getReason()))
            .map(Event::getMessage)
            .findFirst()
            .orElse("");

        return FaultInfo.builder()
            .faultType(faultType)
            .severity(faultType == FaultType.LIVENESS_PROBE_FAILED ? Severity.HIGH : Severity.MEDIUM)
            .resourceKind("Pod")
            .namespace(pod.getMetadata().getNamespace())
            .resourceName(pod.getMetadata().getName())
            .summary(faultType.getDescription())
            .description(probeMessage)
            .symptoms(List.of("Health check 실패", "트래픽이 전달되지 않거나 Pod이 재시작됨"))
            .context(Map.of("probeType", faultType.name()))
            .detectedAt(LocalDateTime.now())
            .build();
    }
}
```

### 4.3 Deployment 장애 탐지기

```java
@Component
public class DeploymentUnavailableDetector implements FaultDetector {

    @Override
    public boolean canDetect(ResourceInfo resource) {
        return "Deployment".equals(resource.getKind());
    }

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Deployment deployment = (Deployment) resource.getDetails().get("deployment");
        DeploymentStatus status = deployment.getStatus();

        Integer desired = deployment.getSpec().getReplicas();
        Integer available = status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;

        if (available < desired) {
            return List.of(FaultInfo.builder()
                .faultType(FaultType.DEPLOYMENT_UNAVAILABLE)
                .severity(Severity.HIGH)
                .resourceKind("Deployment")
                .namespace(deployment.getMetadata().getNamespace())
                .resourceName(deployment.getMetadata().getName())
                .summary(String.format("Deployment의 가용 Pod 부족 (%d/%d)", available, desired))
                .description("일부 Pod이 준비되지 않아 서비스가 정상적으로 작동하지 않을 수 있습니다.")
                .symptoms(List.of(
                    String.format("원하는 레플리카: %d", desired),
                    String.format("가용한 레플리카: %d", available),
                    String.format("준비된 레플리카: %d", status.getReadyReplicas() != null ? status.getReadyReplicas() : 0)
                ))
                .context(Map.of(
                    "desired", desired,
                    "available", available,
                    "ready", status.getReadyReplicas() != null ? status.getReadyReplicas() : 0
                ))
                .detectedAt(LocalDateTime.now())
                .build());
        }

        return Collections.emptyList();
    }
}
```

### 4.4 Node 장애 탐지기

```java
@Component
public class NodeNotReadyDetector implements FaultDetector {

    @Override
    public boolean canDetect(ResourceInfo resource) {
        return "Node".equals(resource.getKind());
    }

    @Override
    public List<FaultInfo> detect(ResourceInfo resource) {
        Node node = (Node) resource.getDetails().get("node");

        boolean isReady = node.getStatus().getConditions().stream()
            .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

        if (!isReady) {
            return List.of(FaultInfo.builder()
                .faultType(FaultType.NODE_NOT_READY)
                .severity(Severity.CRITICAL)
                .resourceKind("Node")
                .resourceName(node.getMetadata().getName())
                .summary("노드가 Ready 상태가 아님")
                .description("노드가 정상적으로 작동하지 않아 Pod 스케줄링이 불가능합니다.")
                .symptoms(extractNodeConditions(node))
                .detectedAt(LocalDateTime.now())
                .build());
        }

        return Collections.emptyList();
    }

    private List<String> extractNodeConditions(Node node) {
        return node.getStatus().getConditions().stream()
            .map(c -> String.format("%s: %s (%s)", c.getType(), c.getStatus(), c.getReason()))
            .collect(Collectors.toList());
    }
}
```

## 5. 장애 분류 서비스

```java
@Service
@RequiredArgsConstructor
public class FaultClassificationService {

    private final List<FaultDetector> detectors;

    public List<FaultInfo> detectFaults(ResourceInfo resource) {
        return detectors.stream()
            .filter(detector -> detector.canDetect(resource))
            .flatMap(detector -> detector.detect(resource).stream())
            .collect(Collectors.toList());
    }

    public Map<Severity, List<FaultInfo>> groupBySeverity(List<FaultInfo> faults) {
        return faults.stream()
            .collect(Collectors.groupingBy(FaultInfo::getSeverity));
    }

    public List<FaultInfo> filterBySeverity(List<FaultInfo> faults, Severity minSeverity) {
        return faults.stream()
            .filter(f -> f.getSeverity().ordinal() <= minSeverity.ordinal())
            .collect(Collectors.toList());
    }
}
```

## 6. 테스트

```java
@SpringBootTest
class FaultDetectionTest {

    @Autowired
    private CrashLoopBackOffDetector detector;

    @Test
    void testCrashLoopBackOffDetection() {
        // Given
        Pod pod = createCrashLoopBackOffPod();
        ResourceInfo resource = ResourceInfo.builder()
            .kind("Pod")
            .details(Map.of("pod", pod))
            .build();

        // When
        List<FaultInfo> faults = detector.detect(resource);

        // Then
        assertEquals(1, faults.size());
        assertEquals(FaultType.CRASH_LOOP_BACK_OFF, faults.get(0).getFaultType());
        assertEquals(Severity.CRITICAL, faults.get(0).getSeverity());
    }
}
```

## 7. 다음 단계

Step 4에서는 탐지된 장애를 AI가 분석하여 원인과 해결 방법을 제공하는 기능을 구현합니다.
