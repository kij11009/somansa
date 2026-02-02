# PRD Step 7: 테스트, 모니터링 및 배포

## 1. 개요

애플리케이션의 품질을 보장하고, 운영 환경에서 안정적으로 동작하도록 테스트, 모니터링, 배포 전략을 수립합니다.

## 2. 테스트 전략

### 2.1 테스트 레벨
| 레벨 | 범위 | 도구 | 커버리지 목표 |
|------|------|------|--------------|
| **Unit Test** | 개별 클래스/메서드 | JUnit 5, Mockito | 80% 이상 |
| **Integration Test** | 컴포넌트 간 통합 | Spring Boot Test | 주요 시나리오 100% |
| **E2E Test** | 전체 시스템 | Selenium (선택적) | 주요 사용자 플로우 |

### 2.2 Unit Test

#### 2.2.1 FaultDetector 테스트
```java
@SpringBootTest
class CrashLoopBackOffDetectorTest {

    @Autowired
    private CrashLoopBackOffDetector detector;

    @Test
    @DisplayName("CrashLoopBackOff 상태 감지")
    void testDetectCrashLoopBackOff() {
        // Given
        Pod pod = createPodWithCrashLoopBackOff();
        ResourceInfo resource = ResourceInfo.builder()
            .kind("Pod")
            .details(Map.of("pod", pod))
            .build();

        // When
        List<FaultInfo> faults = detector.detect(resource);

        // Then
        assertThat(faults).hasSize(1);
        assertThat(faults.get(0).getFaultType()).isEqualTo(FaultType.CRASH_LOOP_BACK_OFF);
        assertThat(faults.get(0).getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("정상 Pod은 장애 미감지")
    void testNormalPodNoFault() {
        // Given
        Pod pod = createRunningPod();
        ResourceInfo resource = ResourceInfo.builder()
            .kind("Pod")
            .details(Map.of("pod", pod))
            .build();

        // When
        List<FaultInfo> faults = detector.detect(resource);

        // Then
        assertThat(faults).isEmpty();
    }

    private Pod createPodWithCrashLoopBackOff() {
        return new PodBuilder()
            .withNewMetadata()
                .withName("test-pod")
                .withNamespace("default")
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .addNewContainerStatus()
                    .withName("app")
                    .withRestartCount(5)
                    .withNewState()
                        .withNewWaiting()
                            .withReason("CrashLoopBackOff")
                            .withMessage("Back-off restarting failed container")
                        .endWaiting()
                    .endState()
                .endContainerStatus()
            .endStatus()
            .build();
    }

    private Pod createRunningPod() {
        return new PodBuilder()
            .withNewMetadata()
                .withName("healthy-pod")
                .withNamespace("default")
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .addNewContainerStatus()
                    .withName("app")
                    .withRestartCount(0)
                    .withReady(true)
                    .withNewState()
                        .withNewRunning()
                            .withStartedAt("2024-01-01T00:00:00Z")
                        .endRunning()
                    .endState()
                .endContainerStatus()
            .endStatus()
            .build();
    }
}
```

#### 2.2.2 AIAnalysisService 테스트
```java
@SpringBootTest
class AIAnalysisServiceTest {

    @MockBean
    private OpenRouterClient openRouterClient;

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Test
    @DisplayName("AI 분석 성공")
    void testAnalyzeFaultSuccess() {
        // Given
        FaultInfo faultInfo = createCrashLoopBackOffFault();
        String logs = "Error: Cannot connect to database";

        String mockResponse = """
            ## 원인 분석
            - 요약: 데이터베이스 연결 실패
            - 상세 원인:
              - 데이터베이스 서비스가 준비되지 않음
              - 잘못된 연결 문자열

            ## 해결 가이드
            ### 빠른 해결
            데이터베이스 서비스 상태를 확인하세요.
            """;

        when(openRouterClient.sendChatCompletion(anyString(), anyString()))
            .thenReturn(mockResponse);

        // When
        AIAnalysisResult result = aiAnalysisService.analyzeFault(faultInfo, logs, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRootCause()).isNotNull();
        assertThat(result.getRootCause().getSummary()).contains("데이터베이스");
    }

    @Test
    @DisplayName("AI 분석 실패 시 Fallback")
    void testAnalyzeFaultFallback() {
        // Given
        FaultInfo faultInfo = createCrashLoopBackOffFault();

        when(openRouterClient.sendChatCompletion(anyString(), anyString()))
            .thenThrow(new AIAnalysisException("API Error"));

        // When
        AIAnalysisResult result = aiAnalysisService.analyzeFault(faultInfo, null, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRootCause().getTechnicalExplanation())
            .contains("AI 분석을 수행할 수 없습니다");
    }
}
```

### 2.3 Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DiagnoseControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private K8sResourceService k8sService;

    @MockBean
    private AIAnalysisService aiAnalysisService;

    @Test
    @DisplayName("Pod 진단 페이지 로드")
    void testDiagnosePodPage() throws Exception {
        // Given
        Pod pod = createTestPod();
        when(k8sService.getPod("default", "test-pod")).thenReturn(pod);
        when(k8sService.getPodEvents(anyString(), anyString())).thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/diagnose/pod/default/test-pod"))
            .andExpect(status().isOk())
            .andExpect(view().name("diagnose/pod"))
            .andExpect(model().attributeExists("pod"));
    }

    @Test
    @DisplayName("존재하지 않는 Pod 조회 시 404")
    void testDiagnosePodNotFound() throws Exception {
        // Given
        when(k8sService.getPod("default", "nonexistent"))
            .thenThrow(new K8sResourceNotFoundException("Pod not found"));

        // When & Then
        mockMvc.perform(get("/diagnose/pod/default/nonexistent"))
            .andExpect(status().isNotFound());
    }
}
```

### 2.4 테스트 커버리지

#### pom.xml
```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
        <execution>
            <id>check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## 3. 모니터링

### 3.1 Spring Boot Actuator

#### pom.xml
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

#### application.properties
```properties
# Actuator 설정
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.export.prometheus.enabled=true

# Health Check
management.health.kubernetes.enabled=true

# 애플리케이션 정보
info.app.name=K8s Doctor
info.app.description=AI-powered Kubernetes Diagnostics
info.app.version=@project.version@
```

### 3.2 커스텀 메트릭

```java
@Component
@RequiredArgsConstructor
public class DiagnosticMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter diagnosticCounter;
    private final Counter aiAnalysisCounter;
    private final Timer aiAnalysisTimer;
    private final Counter faultDetectionCounter;

    public DiagnosticMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.diagnosticCounter = Counter.builder("k8s_doctor.diagnostic.total")
            .description("총 진단 수")
            .tag("resource", "all")
            .register(meterRegistry);

        this.aiAnalysisCounter = Counter.builder("k8s_doctor.ai_analysis.total")
            .description("AI 분석 요청 수")
            .tag("status", "success")
            .register(meterRegistry);

        this.aiAnalysisTimer = Timer.builder("k8s_doctor.ai_analysis.duration")
            .description("AI 분석 소요 시간")
            .register(meterRegistry);

        this.faultDetectionCounter = Counter.builder("k8s_doctor.fault_detection.total")
            .description("장애 탐지 수")
            .tag("fault_type", "all")
            .register(meterRegistry);
    }

    public void recordDiagnostic(String resourceType) {
        Counter.builder("k8s_doctor.diagnostic.total")
            .tag("resource", resourceType)
            .register(meterRegistry)
            .increment();
    }

    public void recordAIAnalysis(boolean success) {
        Counter.builder("k8s_doctor.ai_analysis.total")
            .tag("status", success ? "success" : "failure")
            .register(meterRegistry)
            .increment();
    }

    public <T> T recordAIAnalysisTime(Supplier<T> operation) {
        return aiAnalysisTimer.record(operation);
    }

    public void recordFaultDetection(FaultType faultType) {
        Counter.builder("k8s_doctor.fault_detection.total")
            .tag("fault_type", faultType.name())
            .tag("severity", faultType.getSeverity().name())
            .register(meterRegistry)
            .increment();
    }
}
```

### 3.3 로깅 전략

#### logback-spring.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/k8s-doctor.log</file>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/k8s-doctor.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
    </appender>

    <!-- 민감 정보 필터링 -->
    <turboFilter class="com.vibecoding.k8sdoctor.logging.SensitiveDataFilter"/>

    <logger name="com.vibecoding.k8sdoctor" level="INFO"/>
    <logger name="io.fabric8.kubernetes.client" level="WARN"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

#### 민감 정보 필터
```java
public class SensitiveDataFilter extends TurboFilter {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-or-v1-[a-zA-Z0-9]+");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token[\"']?\\s*:\\s*[\"']([^\"']+)");

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format != null) {
            String sanitized = sanitize(format);
            if (!sanitized.equals(format)) {
                // 민감 정보가 발견되면 마스킹된 메시지로 대체
                logger.warn("민감 정보가 로그에 포함되어 마스킹 처리되었습니다");
            }
        }
        return FilterReply.NEUTRAL;
    }

    private String sanitize(String message) {
        message = API_KEY_PATTERN.matcher(message).replaceAll("sk-or-v1-***");
        message = TOKEN_PATTERN.matcher(message).replaceAll("token: ***");
        return message;
    }
}
```

## 4. 배포 전략

### 4.1 Dockerfile
```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 애플리케이션 JAR 복사
COPY target/k8s-doctor-*.jar app.jar

# 비root 사용자 생성
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### 4.2 Kubernetes 배포 매니페스트

#### deployment.yaml
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: k8s-doctor
  namespace: k8s-doctor
spec:
  replicas: 2
  selector:
    matchLabels:
      app: k8s-doctor
  template:
    metadata:
      labels:
        app: k8s-doctor
    spec:
      serviceAccountName: k8s-doctor
      containers:
      - name: k8s-doctor
        image: k8s-doctor:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: OPENROUTER_API_KEY
          valueFrom:
            secretKeyRef:
              name: k8s-doctor-secrets
              key: openrouter-api-key
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
        volumeMounts:
        - name: modelkey
          mountPath: /app/modelkey.txt
          subPath: modelkey.txt
      volumes:
      - name: modelkey
        configMap:
          name: k8s-doctor-config
---
apiVersion: v1
kind: Service
metadata:
  name: k8s-doctor
  namespace: k8s-doctor
spec:
  selector:
    app: k8s-doctor
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: k8s-doctor
  namespace: k8s-doctor
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: k8s-doctor-binding
subjects:
- kind: ServiceAccount
  name: k8s-doctor
  namespace: k8s-doctor
roleRef:
  kind: ClusterRole
  name: k8s-doctor-reader
  apiGroup: rbac.authorization.k8s.io
```

#### secret.yaml
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: k8s-doctor-secrets
  namespace: k8s-doctor
type: Opaque
data:
  # base64로 인코딩된 값
  openrouter-api-key: <BASE64_ENCODED_KEY>
```

#### configmap.yaml
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: k8s-doctor-config
  namespace: k8s-doctor
data:
  modelkey.txt: |
    arcee-ai/trinity-large-preview:free
```

### 4.3 Helm Chart (선택적)

#### Chart.yaml
```yaml
apiVersion: v2
name: k8s-doctor
description: AI-powered Kubernetes Diagnostics Tool
type: application
version: 0.1.0
appVersion: "0.1.0"
```

#### values.yaml
```yaml
replicaCount: 2

image:
  repository: k8s-doctor
  tag: latest
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "500m"

openrouter:
  apiKey: ""  # 설치 시 --set으로 제공
  model: "arcee-ai/trinity-large-preview:free"

ingress:
  enabled: false
  className: ""
  annotations: {}
  hosts:
    - host: k8s-doctor.local
      paths:
        - path: /
          pathType: Prefix
```

## 5. CI/CD 파이프라인

### 5.1 GitHub Actions

#### .github/workflows/ci.yml
```yaml
name: CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Cache Maven packages
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}

    - name: Run tests
      run: mvn clean test

    - name: Generate coverage report
      run: mvn jacoco:report

    - name: Upload coverage to Codecov
      uses: codecov/codecov-action@v3

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Build with Maven
      run: mvn clean package -DskipTests

    - name: Build Docker image
      run: docker build -t k8s-doctor:${{ github.sha }} .

    - name: Push to registry
      if: github.ref == 'refs/heads/main'
      run: |
        echo "${{ secrets.DOCKER_PASSWORD }}" | docker login -u "${{ secrets.DOCKER_USERNAME }}" --password-stdin
        docker tag k8s-doctor:${{ github.sha }} k8s-doctor:latest
        docker push k8s-doctor:latest
```

## 6. 성능 테스트

### 6.1 JMeter 테스트 시나리오
- 동시 사용자 수: 10명
- Ramp-up 시간: 10초
- 테스트 시나리오:
  1. 홈 페이지 로드
  2. 네임스페이스 진단
  3. Pod 상세 진단 (AI 분석 포함)

### 6.2 성능 목표
- 응답 시간 (p95): < 3초 (AI 분석 제외)
- AI 분석 응답 시간 (p95): < 15초
- 처리량: 최소 100 req/min

## 7. 보안 체크리스트

- [ ] API Key는 Secret으로 관리
- [ ] Read-only RBAC 권한
- [ ] 컨테이너는 non-root 사용자로 실행
- [ ] 민감 정보는 로그에 기록하지 않음
- [ ] HTTPS 사용 (Ingress TLS)
- [ ] 정기적인 의존성 취약점 스캔
- [ ] Pod Security Standards 준수

## 8. 운영 가이드

### 8.1 트러블슈팅
| 문제 | 원인 | 해결 방법 |
|------|------|----------|
| AI 분석 실패 | OpenRouter API 오류 | API 키 확인, Rate limit 체크 |
| K8s 연결 실패 | RBAC 권한 부족 | ClusterRole 확인 |
| 메모리 부족 | 대량의 로그 조회 | 로그 조회 제한 설정 |

### 8.2 모니터링 알림
- AI API 실패율 > 10%
- 평균 응답 시간 > 5초
- 메모리 사용량 > 80%
- Pod Restart 발생

## 9. 릴리스 체크리스트

- [ ] 모든 테스트 통과
- [ ] 코드 커버리지 80% 이상
- [ ] 보안 취약점 스캔 완료
- [ ] 문서 업데이트 (README, CLAUDE.md)
- [ ] 버전 태깅
- [ ] Docker 이미지 빌드 및 푸시
- [ ] Helm Chart 업데이트
- [ ] 릴리스 노트 작성

## 10. 다음 단계 (향후 개선)

### Phase 2 기능
- 실시간 모니터링 (WebSocket)
- 장애 히스토리 저장 (Database)
- 알림 기능 (Slack, Email)
- 대시보드 개선 (Chart.js)
- 다국어 지원

### Phase 3 기능
- 자동 복구 기능 (Optional)
- ML 기반 장애 예측
- 커스텀 장애 탐지 규칙
- API 제공 (REST API)
- 플러그인 시스템

---

## 전체 개발 로드맵 요약

| Step | 주요 기능 | 예상 기간 |
|------|----------|----------|
| Step 1 | 프로젝트 설정 및 기술 스택 | 1주 |
| Step 2 | K8s 클러스터 연결 및 리소스 조회 | 1주 |
| Step 3 | 장애 탐지 및 분류 엔진 | 2주 |
| Step 4 | AI 분석 엔진 | 2주 |
| Step 5 | 웹 UI 및 리포트 | 2주 |
| Step 6 | 멀티클러스터 지원 (Optional) | 1주 |
| Step 7 | 테스트, 모니터링, 배포 | 1주 |

**총 예상 기간: 10주 (멀티클러스터 포함)**

K8s Doctor PRD 완료! 🎉
