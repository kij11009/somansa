# PRD Step 5: 웹 UI 및 리포트 생성

## 1. 개요

Spring Boot + Thymeleaf를 사용하여 사용자 친화적인 웹 인터페이스를 구현하고, AI 분석 결과를 시각화합니다.

## 2. UI 구조

### 2.1 페이지 구성
| 페이지 | URL | 설명 |
|--------|-----|------|
| **홈** | `/` | 대시보드, 네임스페이스 선택 |
| **네임스페이스 진단** | `/diagnose/namespace/{name}` | 네임스페이스 전체 리소스 진단 |
| **Pod 상세 진단** | `/diagnose/pod/{namespace}/{name}` | 특정 Pod 상세 분석 |
| **Deployment 진단** | `/diagnose/deployment/{namespace}/{name}` | Deployment 분석 |
| **Node 진단** | `/diagnose/node/{name}` | Node 상태 분석 |
| **리포트** | `/report/{id}` | 저장된 진단 리포트 조회 |

### 2.2 화면 레이아웃
```
┌─────────────────────────────────────────┐
│           Header (Navigation)            │
├─────────────────────────────────────────┤
│                                          │
│            Main Content Area             │
│                                          │
│  ┌────────────┐  ┌──────────────────┐   │
│  │  Sidebar   │  │  Content Panel   │   │
│  │  (Filter)  │  │  (Results)       │   │
│  └────────────┘  └──────────────────┘   │
│                                          │
├─────────────────────────────────────────┤
│           Footer (Version Info)          │
└─────────────────────────────────────────┘
```

## 3. Controller

### 3.1 HomeController
```java
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final K8sResourceService k8sService;
    private final FaultClassificationService faultService;

    @GetMapping("/")
    public String home(Model model) {
        List<Namespace> namespaces = k8sService.listNamespaces();

        // 각 네임스페이스별 간단한 통계
        Map<String, NamespaceStats> stats = namespaces.stream()
            .collect(Collectors.toMap(
                ns -> ns.getMetadata().getName(),
                this::calculateNamespaceStats
            ));

        model.addAttribute("namespaces", namespaces);
        model.addAttribute("stats", stats);

        return "index";
    }

    private NamespaceStats calculateNamespaceStats(Namespace ns) {
        String nsName = ns.getMetadata().getName();
        List<Pod> pods = k8sService.listPodsInNamespace(nsName);

        long totalPods = pods.size();
        long healthyPods = pods.stream()
            .filter(p -> "Running".equals(p.getStatus().getPhase()))
            .count();
        long faultyPods = totalPods - healthyPods;

        return NamespaceStats.builder()
            .totalPods(totalPods)
            .healthyPods(healthyPods)
            .faultyPods(faultyPods)
            .build();
    }
}
```

### 3.2 DiagnoseController
```java
@Controller
@RequestMapping("/diagnose")
@RequiredArgsConstructor
@Slf4j
public class DiagnoseController {

    private final K8sResourceService k8sService;
    private final FaultClassificationService faultService;
    private final AIAnalysisService aiAnalysisService;

    @GetMapping("/namespace/{namespace}")
    public String diagnoseNamespace(@PathVariable String namespace, Model model) {
        log.info("네임스페이스 진단 시작: {}", namespace);

        List<Pod> pods = k8sService.listPodsInNamespace(namespace);
        List<DiagnosticResult> results = new ArrayList<>();

        for (Pod pod : pods) {
            ResourceInfo resourceInfo = ResourceInfo.builder()
                .kind("Pod")
                .namespace(namespace)
                .name(pod.getMetadata().getName())
                .status(pod.getStatus().getPhase())
                .details(Map.of("pod", pod))
                .build();

            List<FaultInfo> faults = faultService.detectFaults(resourceInfo);

            if (!faults.isEmpty()) {
                DiagnosticResult result = DiagnosticResult.builder()
                    .resourceInfo(resourceInfo)
                    .faults(faults)
                    .severity(faults.stream()
                        .map(FaultInfo::getSeverity)
                        .min(Comparator.naturalOrder())
                        .orElse(Severity.LOW))
                    .build();

                results.add(result);
            }
        }

        // 심각도별로 정렬
        results.sort(Comparator.comparing(DiagnosticResult::getSeverity));

        model.addAttribute("namespace", namespace);
        model.addAttribute("totalPods", pods.size());
        model.addAttribute("faultyPods", results.size());
        model.addAttribute("results", results);

        return "diagnose/namespace";
    }

    @GetMapping("/pod/{namespace}/{name}")
    public String diagnosePod(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(defaultValue = "true") boolean aiAnalysis,
            Model model) {

        log.info("Pod 진단 시작: {}/{}, AI 분석: {}", namespace, name, aiAnalysis);

        // Pod 정보 수집
        Pod pod = k8sService.getPod(namespace, name);
        List<Event> events = k8sService.getPodEvents(namespace, name);

        // 로그 수집 (첫 번째 컨테이너)
        String logs = null;
        if (!pod.getSpec().getContainers().isEmpty()) {
            String containerName = pod.getSpec().getContainers().get(0).getName();
            try {
                logs = k8sService.getPodLogs(namespace, name, containerName, 100);
            } catch (Exception e) {
                log.warn("로그 수집 실패: {}", e.getMessage());
            }
        }

        // 장애 탐지
        ResourceInfo resourceInfo = ResourceInfo.builder()
            .kind("Pod")
            .namespace(namespace)
            .name(name)
            .status(pod.getStatus().getPhase())
            .details(Map.of("pod", pod))
            .build();

        List<FaultInfo> faults = faultService.detectFaults(resourceInfo);

        // AI 분석
        List<AIAnalysisResult> aiResults = new ArrayList<>();
        if (aiAnalysis && !faults.isEmpty()) {
            for (FaultInfo fault : faults) {
                try {
                    AIAnalysisResult aiResult = aiAnalysisService.analyzeFault(
                        fault,
                        logs,
                        events.stream()
                            .map(this::convertToEventInfo)
                            .collect(Collectors.toList())
                    );
                    aiResults.add(aiResult);
                } catch (Exception e) {
                    log.error("AI 분석 실패: {}", e.getMessage(), e);
                }
            }
        }

        model.addAttribute("pod", pod);
        model.addAttribute("faults", faults);
        model.addAttribute("aiResults", aiResults);
        model.addAttribute("events", events);
        model.addAttribute("logs", logs);

        return "diagnose/pod";
    }

    @GetMapping("/deployment/{namespace}/{name}")
    public String diagnoseDeployment(
            @PathVariable String namespace,
            @PathVariable String name,
            Model model) {

        Deployment deployment = k8sService.getDeployment(namespace, name);

        ResourceInfo resourceInfo = ResourceInfo.builder()
            .kind("Deployment")
            .namespace(namespace)
            .name(name)
            .details(Map.of("deployment", deployment))
            .build();

        List<FaultInfo> faults = faultService.detectFaults(resourceInfo);

        // Deployment의 Pod들도 진단
        List<Pod> pods = k8sService.listPodsInNamespace(namespace).stream()
            .filter(p -> belongsToDeployment(p, deployment))
            .collect(Collectors.toList());

        model.addAttribute("deployment", deployment);
        model.addAttribute("faults", faults);
        model.addAttribute("pods", pods);

        return "diagnose/deployment";
    }

    @GetMapping("/node/{name}")
    public String diagnoseNode(@PathVariable String name, Model model) {
        Node node = k8sService.getNode(name);

        ResourceInfo resourceInfo = ResourceInfo.builder()
            .kind("Node")
            .name(name)
            .details(Map.of("node", node))
            .build();

        List<FaultInfo> faults = faultService.detectFaults(resourceInfo);

        model.addAttribute("node", node);
        model.addAttribute("faults", faults);

        return "diagnose/node";
    }

    private EventInfo convertToEventInfo(Event event) {
        return EventInfo.builder()
            .type(event.getType())
            .reason(event.getReason())
            .message(event.getMessage())
            .timestamp(LocalDateTime.parse(event.getLastTimestamp()))
            .count(event.getCount())
            .build();
    }

    private boolean belongsToDeployment(Pod pod, Deployment deployment) {
        Map<String, String> deploymentLabels = deployment.getSpec().getSelector().getMatchLabels();
        Map<String, String> podLabels = pod.getMetadata().getLabels();

        return deploymentLabels.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }
}
```

## 4. Thymeleaf 템플릿

### 4.1 레이아웃 (layout.html)
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${title} ?: 'K8s Doctor'">K8s Doctor</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.0/font/bootstrap-icons.css">
    <link th:href="@{/css/custom.css}" rel="stylesheet">
</head>
<body>
    <!-- Navigation -->
    <nav class="navbar navbar-expand-lg navbar-dark bg-primary">
        <div class="container-fluid">
            <a class="navbar-brand" th:href="@{/}">
                <i class="bi bi-bandaid"></i> K8s Doctor
            </a>
            <div class="navbar-nav">
                <a class="nav-link" th:href="@{/}">홈</a>
            </div>
        </div>
    </nav>

    <!-- Main Content -->
    <div class="container-fluid mt-4">
        <div th:replace="~{::content}">Page content</div>
    </div>

    <!-- Footer -->
    <footer class="footer mt-auto py-3 bg-light">
        <div class="container text-center">
            <span class="text-muted">K8s Doctor v0.1.0 - AI-powered Kubernetes Diagnostics</span>
        </div>
    </footer>

    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

### 4.2 홈 페이지 (index.html)
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: layout(title='K8s Doctor - 홈')}">
<body>
    <div th:fragment="content">
        <h1>K8s Doctor <i class="bi bi-heart-pulse text-danger"></i></h1>
        <p class="lead">Kubernetes 클러스터 장애를 AI로 자동 진단합니다</p>

        <hr class="my-4">

        <h2>네임스페이스 목록</h2>

        <div class="row">
            <div class="col-md-4" th:each="ns : ${namespaces}">
                <div class="card mb-3">
                    <div class="card-header">
                        <h5 th:text="${ns.metadata.name}">Namespace</h5>
                    </div>
                    <div class="card-body">
                        <div th:with="stat=${stats.get(ns.metadata.name)}">
                            <p class="mb-1">
                                <i class="bi bi-box"></i> 전체 Pod: <strong th:text="${stat.totalPods}">0</strong>
                            </p>
                            <p class="mb-1">
                                <i class="bi bi-check-circle text-success"></i> 정상: <strong th:text="${stat.healthyPods}">0</strong>
                            </p>
                            <p class="mb-3">
                                <i class="bi bi-exclamation-triangle text-danger"></i> 장애: <strong th:text="${stat.faultyPods}">0</strong>
                            </p>

                            <a th:href="@{/diagnose/namespace/{ns}(ns=${ns.metadata.name})}"
                               class="btn btn-primary btn-sm w-100">
                                <i class="bi bi-search"></i> 진단 시작
                            </a>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</body>
</html>
```

### 4.3 Pod 진단 페이지 (diagnose/pod.html)
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: layout(title='Pod 진단')}">
<body>
    <div th:fragment="content">
        <nav aria-label="breadcrumb">
            <ol class="breadcrumb">
                <li class="breadcrumb-item"><a th:href="@{/}">홈</a></li>
                <li class="breadcrumb-item active" th:text="${pod.metadata.namespace}">Namespace</li>
                <li class="breadcrumb-item active" th:text="${pod.metadata.name}">Pod</li>
            </ol>
        </nav>

        <h1>
            <i class="bi bi-box"></i> Pod 진단: <span th:text="${pod.metadata.name}">pod-name</span>
        </h1>

        <!-- Pod 기본 정보 -->
        <div class="card mb-3">
            <div class="card-header">
                <h5><i class="bi bi-info-circle"></i> 기본 정보</h5>
            </div>
            <div class="card-body">
                <table class="table table-sm">
                    <tr>
                        <th>Namespace:</th>
                        <td th:text="${pod.metadata.namespace}">default</td>
                    </tr>
                    <tr>
                        <th>Status:</th>
                        <td>
                            <span th:class="${'badge ' + (pod.status.phase == 'Running' ? 'bg-success' : 'bg-danger')}"
                                  th:text="${pod.status.phase}">Running</span>
                        </td>
                    </tr>
                    <tr>
                        <th>Node:</th>
                        <td th:text="${pod.spec.nodeName}">node-1</td>
                    </tr>
                    <tr>
                        <th>생성 시간:</th>
                        <td th:text="${pod.metadata.creationTimestamp}">2024-01-01</td>
                    </tr>
                </table>
            </div>
        </div>

        <!-- 장애 감지 결과 -->
        <div class="card mb-3" th:if="${!faults.isEmpty()}">
            <div class="card-header bg-danger text-white">
                <h5><i class="bi bi-exclamation-triangle"></i> 감지된 장애</h5>
            </div>
            <div class="card-body">
                <div th:each="fault : ${faults}" class="alert alert-danger">
                    <h6 th:text="${fault.faultType.description}">Fault Type</h6>
                    <p th:text="${fault.summary}">Summary</p>
                    <ul th:if="${fault.symptoms != null}">
                        <li th:each="symptom : ${fault.symptoms}" th:text="${symptom}">Symptom</li>
                    </ul>
                </div>
            </div>
        </div>

        <!-- AI 분석 결과 -->
        <div th:if="${!aiResults.isEmpty()}">
            <div th:each="aiResult : ${aiResults}" class="mb-4">
                <h3><i class="bi bi-robot"></i> AI 분석 결과</h3>

                <!-- 원인 분석 -->
                <div class="card mb-3">
                    <div class="card-header">
                        <h5><i class="bi bi-search"></i> 원인 분석</h5>
                    </div>
                    <div class="card-body">
                        <p><strong>요약:</strong> <span th:text="${aiResult.rootCause.summary}">-</span></p>
                        <p><strong>상세 원인:</strong></p>
                        <ul>
                            <li th:each="detail : ${aiResult.rootCause.details}" th:text="${detail}">Detail</li>
                        </ul>
                        <p th:if="${aiResult.rootCause.technicalExplanation != null}">
                            <strong>기술적 설명:</strong><br>
                            <span th:text="${aiResult.rootCause.technicalExplanation}">-</span>
                        </p>
                    </div>
                </div>

                <!-- 해결 가이드 -->
                <div class="card mb-3">
                    <div class="card-header">
                        <h5><i class="bi bi-tools"></i> 해결 가이드</h5>
                    </div>
                    <div class="card-body">
                        <div th:if="${aiResult.resolution.quickFix != null}" class="alert alert-info">
                            <strong><i class="bi bi-lightning"></i> 빠른 해결:</strong><br>
                            <span th:text="${aiResult.resolution.quickFix}">-</span>
                        </div>

                        <h6>단계별 해결:</h6>
                        <ol>
                            <li th:each="step : ${aiResult.resolution.steps}">
                                <strong th:text="${step.title}">Step Title</strong><br>
                                <span th:text="${step.description}">Description</span>
                                <pre th:if="${step.command != null}" class="mt-2 bg-light p-2"><code th:text="${step.command}">command</code></pre>
                            </li>
                        </ol>

                        <div th:if="${aiResult.resolution.permanentFix != null}" class="alert alert-success">
                            <strong><i class="bi bi-shield-check"></i> 근본적 해결:</strong><br>
                            <span th:text="${aiResult.resolution.permanentFix}">-</span>
                        </div>
                    </div>
                </div>

                <!-- YAML 예제 -->
                <div class="card mb-3" th:if="${aiResult.yamlExample != null}">
                    <div class="card-header">
                        <h5><i class="bi bi-file-code"></i> YAML 예제</h5>
                    </div>
                    <div class="card-body">
                        <div class="row">
                            <div class="col-md-6">
                                <h6>수정 전:</h6>
                                <pre class="bg-light p-3"><code th:text="${aiResult.yamlExample.before}">before</code></pre>
                            </div>
                            <div class="col-md-6">
                                <h6>수정 후:</h6>
                                <pre class="bg-light p-3"><code th:text="${aiResult.yamlExample.after}">after</code></pre>
                            </div>
                        </div>
                        <div th:if="${aiResult.yamlExample.changes != null}">
                            <h6>변경 사항:</h6>
                            <ul>
                                <li th:each="change : ${aiResult.yamlExample.changes}" th:text="${change}">Change</li>
                            </ul>
                        </div>
                    </div>
                </div>

                <!-- 예방 방법 -->
                <div class="card mb-3" th:if="${aiResult.prevention != null}">
                    <div class="card-header">
                        <h5><i class="bi bi-shield"></i> 예방 방법</h5>
                    </div>
                    <div class="card-body">
                        <div th:if="${aiResult.prevention.bestPractices != null}">
                            <h6>Best Practices:</h6>
                            <ul>
                                <li th:each="practice : ${aiResult.prevention.bestPractices}" th:text="${practice}">Practice</li>
                            </ul>
                        </div>
                        <div th:if="${aiResult.prevention.monitoring != null}">
                            <h6>모니터링 포인트:</h6>
                            <ul>
                                <li th:each="monitor : ${aiResult.prevention.monitoring}" th:text="${monitor}">Monitor</li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>
        </div>

        <!-- 로그 -->
        <div class="card mb-3" th:if="${logs != null}">
            <div class="card-header">
                <h5><i class="bi bi-terminal"></i> 컨테이너 로그 (최근 100줄)</h5>
            </div>
            <div class="card-body">
                <pre class="bg-dark text-light p-3" style="max-height: 400px; overflow-y: auto;"><code th:text="${logs}">logs</code></pre>
            </div>
        </div>

        <!-- Events -->
        <div class="card mb-3" th:if="${events != null && !events.isEmpty()}">
            <div class="card-header">
                <h5><i class="bi bi-list-ul"></i> Kubernetes Events</h5>
            </div>
            <div class="card-body">
                <table class="table table-sm">
                    <thead>
                        <tr>
                            <th>Type</th>
                            <th>Reason</th>
                            <th>Message</th>
                            <th>Count</th>
                            <th>Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr th:each="event : ${events}">
                            <td>
                                <span th:class="${'badge ' + (event.type == 'Warning' ? 'bg-warning' : 'bg-info')}"
                                      th:text="${event.type}">Type</span>
                            </td>
                            <td th:text="${event.reason}">Reason</td>
                            <td th:text="${event.message}">Message</td>
                            <td th:text="${event.count}">1</td>
                            <td th:text="${event.lastTimestamp}">Time</td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</body>
</html>
```

## 5. CSS (custom.css)

```css
:root {
    --severity-critical: #dc3545;
    --severity-high: #fd7e14;
    --severity-medium: #ffc107;
    --severity-low: #6c757d;
}

.severity-badge {
    font-weight: bold;
    padding: 0.25rem 0.5rem;
    border-radius: 0.25rem;
}

.severity-critical {
    background-color: var(--severity-critical);
    color: white;
}

.severity-high {
    background-color: var(--severity-high);
    color: white;
}

.severity-medium {
    background-color: var(--severity-medium);
    color: black;
}

.severity-low {
    background-color: var(--severity-low);
    color: white;
}

pre code {
    font-family: 'Courier New', monospace;
    font-size: 0.875rem;
}

.footer {
    position: fixed;
    bottom: 0;
    width: 100%;
}
```

## 6. DTO 모델

```java
@Data
@Builder
public class DiagnosticResult {
    private ResourceInfo resourceInfo;
    private List<FaultInfo> faults;
    private Severity severity;
}

@Data
@Builder
public class NamespaceStats {
    private long totalPods;
    private long healthyPods;
    private long faultyPods;
}
```

## 7. 다음 단계

Step 6에서는 멀티클러스터 지원 기능을 추가합니다.
