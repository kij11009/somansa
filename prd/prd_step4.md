# PRD Step 4: AI 분석 엔진 (OpenRouter 통합)

## 1. 개요

OpenRouter API를 활용하여 탐지된 장애를 AI가 분석하고 해결 가이드를 제공합니다.

## 2. OpenRouter 설정

### 2.1 Configuration 클래스
```java
@Configuration
@ConfigurationProperties(prefix = "openrouter")
@Data
public class OpenRouterConfig {

    private String apiKey;
    private String apiUrl = "https://openrouter.ai/api/v1/chat/completions";
    private String model;
    private Integer timeout = 30000;
    private Integer maxTokens = 2000;
    private Double temperature = 0.7;

    @PostConstruct
    public void init() {
        // .env에서 API 키 로드
        if (apiKey == null) {
            apiKey = System.getenv("OPENROUTER_API_KEY");
        }

        // modelkey.txt에서 모델 로드
        if (model == null) {
            model = loadModelFromFile();
        }

        validateConfig();
    }

    private String loadModelFromFile() {
        try {
            Path modelKeyPath = Paths.get("modelkey.txt");
            if (Files.exists(modelKeyPath)) {
                return Files.readString(modelKeyPath).trim();
            }
        } catch (IOException e) {
            log.warn("modelkey.txt 파일을 읽을 수 없습니다: {}", e.getMessage());
        }
        // fallback 모델
        return "arcee-ai/trinity-large-preview:free";
    }

    public void validateConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENROUTER_API_KEY가 설정되지 않았습니다.");
        }
        log.info("OpenRouter 설정 완료 - Model: {}", model);
    }
}
```

### 2.2 application.properties
```properties
# OpenRouter Configuration
openrouter.api-url=https://openrouter.ai/api/v1/chat/completions
openrouter.timeout=30000
openrouter.max-tokens=2000
openrouter.temperature=0.7

# HTTP Client
spring.http.client.connect-timeout=10000
spring.http.client.read-timeout=30000
```

## 3. AI 분석 요청/응답 모델

### 3.1 요청 모델
```java
@Data
@Builder
public class OpenRouterRequest {
    private String model;
    private List<Message> messages;
    private Integer maxTokens;
    private Double temperature;

    @Data
    @Builder
    public static class Message {
        private String role;  // system, user, assistant
        private String content;
    }
}
```

### 3.2 응답 모델
```java
@Data
public class OpenRouterResponse {
    private String id;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    public static class Choice {
        private Integer index;
        private Message message;
        private String finishReason;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
```

### 3.3 AI 분석 결과 모델
```java
@Data
@Builder
public class AIAnalysisResult {
    private String faultType;
    private RootCauseAnalysis rootCause;
    private ReproductionConditions reproduction;
    private ResolutionGuide resolution;
    private YamlExample yamlExample;
    private PreventionGuide prevention;
    private LocalDateTime analyzedAt;

    @Data
    @Builder
    public static class RootCauseAnalysis {
        private String summary;           // 원인 요약
        private List<String> details;     // 상세 원인
        private String technicalExplanation; // 기술적 설명
    }

    @Data
    @Builder
    public static class ReproductionConditions {
        private List<String> conditions;  // 재현 조건
        private String scenario;          // 재현 시나리오
    }

    @Data
    @Builder
    public static class ResolutionGuide {
        private List<ResolutionStep> steps;
        private String quickFix;          // 빠른 해결 방법
        private String permanentFix;      // 근본적 해결 방법
    }

    @Data
    @Builder
    public static class ResolutionStep {
        private Integer stepNumber;
        private String title;
        private String description;
        private String command;           // 실행할 명령어 (선택적)
    }

    @Data
    @Builder
    public static class YamlExample {
        private String before;            // 수정 전 YAML
        private String after;             // 수정 후 YAML
        private List<String> changes;     // 변경 사항 설명
    }

    @Data
    @Builder
    public static class PreventionGuide {
        private List<String> bestPractices;
        private List<String> monitoring;  // 모니터링 포인트
        private List<String> alerts;      // 알림 설정 권장
    }
}
```

## 4. AI 분석 서비스

### 4.1 OpenRouterClient
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final OpenRouterConfig config;
    private final RestTemplate restTemplate;

    public String sendChatCompletion(String systemPrompt, String userPrompt) {
        OpenRouterRequest request = OpenRouterRequest.builder()
            .model(config.getModel())
            .messages(Arrays.asList(
                OpenRouterRequest.Message.builder()
                    .role("system")
                    .content(systemPrompt)
                    .build(),
                OpenRouterRequest.Message.builder()
                    .role("user")
                    .content(userPrompt)
                    .build()
            ))
            .maxTokens(config.getMaxTokens())
            .temperature(config.getTemperature())
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + config.getApiKey());

        HttpEntity<OpenRouterRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<OpenRouterResponse> response = restTemplate.postForEntity(
                config.getApiUrl(),
                entity,
                OpenRouterResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String content = response.getBody().getChoices().get(0).getMessage().getContent();
                log.info("AI 분석 완료 - 토큰 사용: {}", response.getBody().getUsage().getTotalTokens());
                return content;
            }
        } catch (Exception e) {
            log.error("OpenRouter API 호출 실패: {}", e.getMessage(), e);
            throw new AIAnalysisException("AI 분석 중 오류 발생", e);
        }

        throw new AIAnalysisException("AI 응답을 받을 수 없습니다.");
    }
}
```

### 4.2 AIAnalysisService
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisService {

    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;

    public AIAnalysisResult analyzeFault(FaultInfo faultInfo, String logs, List<EventInfo> events) {
        try {
            String systemPrompt = createSystemPrompt();
            String userPrompt = createUserPrompt(faultInfo, logs, events);

            String aiResponse = openRouterClient.sendChatCompletion(systemPrompt, userPrompt);

            return parseAIResponse(aiResponse, faultInfo.getFaultType());

        } catch (AIAnalysisException e) {
            log.error("AI 분석 실패: {}", e.getMessage());
            return createFallbackAnalysis(faultInfo);
        }
    }

    private String createSystemPrompt() {
        return """
            당신은 Kubernetes 전문가입니다.
            주어진 장애 정보를 분석하고 다음 형식으로 응답해주세요:

            ## 원인 분석
            - 요약: (한 문장으로 요약)
            - 상세 원인: (항목별로 나열)
            - 기술적 설명: (기술적 배경)

            ## 재현 조건
            - 조건: (항목별로 나열)
            - 시나리오: (재현 방법)

            ## 해결 가이드
            ### 빠른 해결
            (임시 조치)

            ### 단계별 해결
            1. [제목] - 설명
            2. [제목] - 설명
            ...

            ### 근본적 해결
            (영구적 해결 방법)

            ## YAML 예제
            ### 수정 전
            ```yaml
            (현재 설정)
            ```

            ### 수정 후
            ```yaml
            (권장 설정)
            ```

            ### 변경 사항
            - (변경 내용 설명)

            ## 예방 방법
            - Best Practice: (항목별로 나열)
            - 모니터링: (모니터링 포인트)
            - 알림 설정: (권장 알림)

            응답은 한국어로 작성하고, 초급 운영자도 이해할 수 있도록 쉽게 설명해주세요.
            """;
    }

    private String createUserPrompt(FaultInfo faultInfo, String logs, List<EventInfo> events) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## 장애 정보\n");
        prompt.append(String.format("- 유형: %s\n", faultInfo.getFaultType().getDescription()));
        prompt.append(String.format("- 심각도: %s\n", faultInfo.getSeverity()));
        prompt.append(String.format("- 리소스: %s/%s (Namespace: %s)\n",
            faultInfo.getResourceKind(),
            faultInfo.getResourceName(),
            faultInfo.getNamespace()));
        prompt.append(String.format("- 요약: %s\n\n", faultInfo.getSummary()));

        if (faultInfo.getSymptoms() != null && !faultInfo.getSymptoms().isEmpty()) {
            prompt.append("## 증상\n");
            faultInfo.getSymptoms().forEach(s -> prompt.append("- ").append(s).append("\n"));
            prompt.append("\n");
        }

        if (logs != null && !logs.isBlank()) {
            prompt.append("## 컨테이너 로그 (최근 50줄)\n");
            prompt.append("```\n");
            prompt.append(truncateLogs(logs, 50));
            prompt.append("\n```\n\n");
        }

        if (events != null && !events.isEmpty()) {
            prompt.append("## Kubernetes Events\n");
            events.stream()
                .limit(10)
                .forEach(e -> prompt.append(String.format("- [%s] %s: %s\n",
                    e.getType(), e.getReason(), e.getMessage())));
            prompt.append("\n");
        }

        if (faultInfo.getContext() != null && !faultInfo.getContext().isEmpty()) {
            prompt.append("## 추가 컨텍스트\n");
            faultInfo.getContext().forEach((k, v) ->
                prompt.append(String.format("- %s: %s\n", k, v)));
        }

        return prompt.toString();
    }

    private String truncateLogs(String logs, int lines) {
        String[] logLines = logs.split("\n");
        if (logLines.length <= lines) {
            return logs;
        }
        return String.join("\n", Arrays.copyOfRange(logLines, logLines.length - lines, logLines.length));
    }

    private AIAnalysisResult parseAIResponse(String response, FaultType faultType) {
        // AI 응답을 파싱하여 구조화된 결과로 변환
        // 마크다운 섹션별로 파싱

        Map<String, String> sections = parseSections(response);

        return AIAnalysisResult.builder()
            .faultType(faultType.name())
            .rootCause(parseRootCause(sections.get("원인 분석")))
            .reproduction(parseReproduction(sections.get("재현 조건")))
            .resolution(parseResolution(sections.get("해결 가이드")))
            .yamlExample(parseYamlExample(sections.get("YAML 예제")))
            .prevention(parsePrevention(sections.get("예방 방법")))
            .analyzedAt(LocalDateTime.now())
            .build();
    }

    private Map<String, String> parseSections(String response) {
        Map<String, String> sections = new HashMap<>();
        String[] parts = response.split("##\\s+");

        for (String part : parts) {
            if (part.trim().isEmpty()) continue;

            String[] lines = part.split("\n", 2);
            if (lines.length == 2) {
                sections.put(lines[0].trim(), lines[1].trim());
            }
        }

        return sections;
    }

    private AIAnalysisResult.RootCauseAnalysis parseRootCause(String section) {
        if (section == null) return null;

        String summary = extractField(section, "요약:");
        List<String> details = extractListItems(section, "상세 원인:");
        String technical = extractField(section, "기술적 설명:");

        return AIAnalysisResult.RootCauseAnalysis.builder()
            .summary(summary)
            .details(details)
            .technicalExplanation(technical)
            .build();
    }

    private AIAnalysisResult.ReproductionConditions parseReproduction(String section) {
        if (section == null) return null;

        List<String> conditions = extractListItems(section, "조건:");
        String scenario = extractField(section, "시나리오:");

        return AIAnalysisResult.ReproductionConditions.builder()
            .conditions(conditions)
            .scenario(scenario)
            .build();
    }

    private AIAnalysisResult.ResolutionGuide parseResolution(String section) {
        if (section == null) return null;

        String quickFix = extractSubsection(section, "빠른 해결");
        String permanentFix = extractSubsection(section, "근본적 해결");
        List<AIAnalysisResult.ResolutionStep> steps = parseResolutionSteps(
            extractSubsection(section, "단계별 해결")
        );

        return AIAnalysisResult.ResolutionGuide.builder()
            .quickFix(quickFix)
            .steps(steps)
            .permanentFix(permanentFix)
            .build();
    }

    private List<AIAnalysisResult.ResolutionStep> parseResolutionSteps(String stepsSection) {
        if (stepsSection == null) return Collections.emptyList();

        List<AIAnalysisResult.ResolutionStep> steps = new ArrayList<>();
        Pattern pattern = Pattern.compile("(\\d+)\\.\\s*\\[([^]]+)]\\s*-\\s*(.+)");

        for (String line : stepsSection.split("\n")) {
            Matcher matcher = pattern.matcher(line.trim());
            if (matcher.find()) {
                steps.add(AIAnalysisResult.ResolutionStep.builder()
                    .stepNumber(Integer.parseInt(matcher.group(1)))
                    .title(matcher.group(2))
                    .description(matcher.group(3))
                    .build());
            }
        }

        return steps;
    }

    private AIAnalysisResult.YamlExample parseYamlExample(String section) {
        if (section == null) return null;

        String before = extractCodeBlock(section, "수정 전");
        String after = extractCodeBlock(section, "수정 후");
        List<String> changes = extractListItems(section, "변경 사항");

        return AIAnalysisResult.YamlExample.builder()
            .before(before)
            .after(after)
            .changes(changes)
            .build();
    }

    private AIAnalysisResult.PreventionGuide parsePrevention(String section) {
        if (section == null) return null;

        List<String> bestPractices = extractListItems(section, "Best Practice:");
        List<String> monitoring = extractListItems(section, "모니터링:");
        List<String> alerts = extractListItems(section, "알림 설정:");

        return AIAnalysisResult.PreventionGuide.builder()
            .bestPractices(bestPractices)
            .monitoring(monitoring)
            .alerts(alerts)
            .build();
    }

    // Helper methods
    private String extractField(String text, String field) {
        Pattern pattern = Pattern.compile(field + "\\s*(.+?)(?=\\n-|\\n\\n|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private List<String> extractListItems(String text, String section) {
        // "- " 로 시작하는 항목들 추출
        List<String> items = new ArrayList<>();
        boolean inSection = false;

        for (String line : text.split("\n")) {
            if (line.contains(section)) {
                inSection = true;
                continue;
            }
            if (inSection && line.trim().startsWith("-")) {
                items.add(line.trim().substring(1).trim());
            } else if (inSection && !line.trim().isEmpty() && !line.trim().startsWith("-")) {
                break;
            }
        }

        return items;
    }

    private String extractSubsection(String text, String subsection) {
        Pattern pattern = Pattern.compile("###\\s*" + subsection + "\\s*\\n(.+?)(?=###|##|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String extractCodeBlock(String text, String label) {
        Pattern pattern = Pattern.compile("###\\s*" + label + "\\s*\\n```(?:yaml)?\\n(.+?)```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private AIAnalysisResult createFallbackAnalysis(FaultInfo faultInfo) {
        // AI 분석 실패 시 기본 분석 결과 반환
        return AIAnalysisResult.builder()
            .faultType(faultInfo.getFaultType().name())
            .rootCause(AIAnalysisResult.RootCauseAnalysis.builder()
                .summary(faultInfo.getSummary())
                .details(faultInfo.getSymptoms())
                .technicalExplanation("AI 분석을 수행할 수 없습니다. 기본 진단 결과를 참고하세요.")
                .build())
            .resolution(AIAnalysisResult.ResolutionGuide.builder()
                .quickFix("Kubernetes 문서를 참고하여 " + faultInfo.getFaultType().getDescription() + " 문제를 해결하세요.")
                .steps(Collections.emptyList())
                .build())
            .analyzedAt(LocalDateTime.now())
            .build();
    }
}
```

## 5. Exception 처리

```java
public class AIAnalysisException extends RuntimeException {
    public AIAnalysisException(String message) {
        super(message);
    }

    public AIAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 6. 캐싱 전략

```java
@Service
@RequiredArgsConstructor
public class CachedAIAnalysisService {

    private final AIAnalysisService aiAnalysisService;
    private final Cache<String, AIAnalysisResult> cache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(100)
        .build();

    public AIAnalysisResult analyzeFault(FaultInfo faultInfo, String logs, List<EventInfo> events) {
        String cacheKey = generateCacheKey(faultInfo, logs);

        AIAnalysisResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            log.info("캐시된 AI 분석 결과 반환: {}", cacheKey);
            return cached;
        }

        AIAnalysisResult result = aiAnalysisService.analyzeFault(faultInfo, logs, events);
        cache.put(cacheKey, result);

        return result;
    }

    private String generateCacheKey(FaultInfo faultInfo, String logs) {
        return String.format("%s:%s:%s:%d",
            faultInfo.getFaultType(),
            faultInfo.getNamespace(),
            faultInfo.getResourceName(),
            logs != null ? logs.hashCode() : 0
        );
    }
}
```

## 7. 테스트

```java
@SpringBootTest
class AIAnalysisServiceTest {

    @MockBean
    private OpenRouterClient openRouterClient;

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Test
    void testAnalyzeCrashLoopBackOff() {
        // Given
        FaultInfo faultInfo = createCrashLoopBackOffFault();
        String logs = "Error: Cannot connect to database\nConnection refused";

        when(openRouterClient.sendChatCompletion(anyString(), anyString()))
            .thenReturn(createMockAIResponse());

        // When
        AIAnalysisResult result = aiAnalysisService.analyzeFault(faultInfo, logs, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRootCause());
        assertNotNull(result.getResolution());
    }
}
```

## 8. 다음 단계

Step 5에서는 웹 UI를 구현하고 진단 리포트를 사용자에게 표시하는 기능을 개발합니다.
