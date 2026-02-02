package com.vibecoding.k8sdoctor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.k8sdoctor.model.DiagnosisResult;
import com.vibecoding.k8sdoctor.model.FaultInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 기반 진단 서비스
 */
@Service
@RequiredArgsConstructor
public class AIDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(AIDiagnosisService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MultiClusterK8sService k8sService;

    // API 응답 캐시 (메모리 기반, 30분 TTL)
    private final Map<String, DiagnosisResult> diagnosisCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${openrouter.api-url}")
    private String apiUrl;

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.model}")
    private String model;

    @Value("${ai.diagnosis.enabled:true}")
    private boolean aiDiagnosisEnabled;

    @Value("${ai.diagnosis.min-severity:MEDIUM}")
    private String minSeverity;

    @Value("${ai.diagnosis.cache-enabled:true}")
    private boolean cacheEnabled;

    @Value("${ai.diagnosis.cache-ttl-minutes:30}")
    private int cacheTtlMinutes;

    /**
     * 장애에 대한 AI 진단 수행 (로그 + 이벤트 포함)
     */
    public DiagnosisResult diagnose(FaultInfo fault, List<FaultInfo> allFaults) {
        // AI 진단 비활성화 시 fallback만 반환
        if (!aiDiagnosisEnabled) {
            return createFallbackDiagnosis(fault, findRelatedFaults(fault, allFaults));
        }

        // 심각도 필터링 (설정된 레벨 이하는 AI 호출 안 함)
        if (!shouldUseiAI(fault)) {
            log.debug("Skipping AI diagnosis for {} fault (below min severity)", fault.getFaultType());
            return createFallbackDiagnosis(fault, findRelatedFaults(fault, allFaults));
        }

        // 캐시 확인
        String cacheKey = generateCacheKey(fault);
        if (cacheEnabled && isCacheValid(cacheKey)) {
            log.debug("Using cached diagnosis for {}", fault.getFaultType());
            return diagnosisCache.get(cacheKey);
        }

        // 같은 리소스의 관련 장애들 찾기
        List<FaultInfo> relatedFaults = findRelatedFaults(fault, allFaults);

        // 로그와 이벤트 수집 (Pod인 경우만)
        String logs = "";
        List<io.fabric8.kubernetes.api.model.Event> events = new ArrayList<>();

        if ("Pod".equals(fault.getResourceKind()) && fault.getNamespace() != null) {
            try {
                // 첫 번째 컨테이너의 로그 가져오기
                String containerName = (String) fault.getContext().get("containerName");
                logs = k8sService.getPodLogs(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName(),
                    containerName,
                    50 // 최근 50줄
                );

                // Pod 이벤트 가져오기
                events = k8sService.getPodEvents(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName()
                );
            } catch (Exception e) {
                log.warn("Failed to fetch logs/events for pod {}: {}", fault.getResourceName(), e.getMessage());
            }
        }

        // AI에게 진단 요청
        String aiResponse = requestAIDiagnosis(fault, relatedFaults, logs, events);

        // AI 응답 파싱
        DiagnosisResult result = parseAIResponse(fault, relatedFaults, aiResponse);

        // 캐시 저장
        if (cacheEnabled) {
            diagnosisCache.put(cacheKey, result);
            cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        }

        return result;
    }

    /**
     * AI 호출 여부 결정 (심각도 기반)
     */
    private boolean shouldUseiAI(FaultInfo fault) {
        com.vibecoding.k8sdoctor.model.Severity faultSeverity = fault.getSeverity();
        com.vibecoding.k8sdoctor.model.Severity minSeverityLevel;

        try {
            minSeverityLevel = com.vibecoding.k8sdoctor.model.Severity.valueOf(minSeverity);
        } catch (Exception e) {
            minSeverityLevel = com.vibecoding.k8sdoctor.model.Severity.MEDIUM;
        }

        // ordinal()이 작을수록 심각함 (CRITICAL=0, HIGH=1, MEDIUM=2, LOW=3)
        return faultSeverity.ordinal() <= minSeverityLevel.ordinal();
    }

    /**
     * 캐시 키 생성 - 더 정밀한 키로 잘못된 캐시 반환 방지
     */
    private String generateCacheKey(FaultInfo fault) {
        // issueCategory로 정확한 원인별 구분
        String issueCategory = "";
        if (fault.getContext() != null && fault.getContext().get("issueCategory") != null) {
            issueCategory = (String) fault.getContext().get("issueCategory");
        }

        // ownerKind도 캐시 키에 포함 (StatefulSet vs Deployment 등 해결책이 다름)
        String ownerKind = "";
        if (fault.getContext() != null && fault.getContext().get("ownerKind") != null) {
            ownerKind = (String) fault.getContext().get("ownerKind");
        }

        // issueCategory가 없으면 description에서 추출
        if (issueCategory.isEmpty() && fault.getDescription() != null) {
            String desc = fault.getDescription().toLowerCase();
            if (desc.contains("pvc") || desc.contains("volume") || desc.contains("storagec") || desc.contains("바인딩")) {
                issueCategory = "PVC";
            } else if (desc.contains("cpu")) {
                issueCategory = "CPU";
            } else if (desc.contains("memory") || desc.contains("메모리")) {
                issueCategory = "MEMORY";
            } else if (desc.contains("insufficient") || desc.contains("리소스")) {
                issueCategory = "RESOURCE";
            } else if (desc.contains("taint") || desc.contains("toleration")) {
                issueCategory = "TAINT";
            } else if (desc.contains("node") || desc.contains("affinity") || desc.contains("selector")) {
                issueCategory = "NODE";
            }
        }

        // 캐시 키: FaultType:ResourceKind:OwnerKind:IssueCategory
        return fault.getFaultType() + ":" + fault.getResourceKind() + ":" +
               ownerKind + ":" + issueCategory;
    }

    /**
     * 캐시 유효성 확인
     */
    private boolean isCacheValid(String cacheKey) {
        if (!diagnosisCache.containsKey(cacheKey)) {
            return false;
        }

        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp == null) {
            return false;
        }

        long ageMinutes = (System.currentTimeMillis() - timestamp) / 1000 / 60;
        return ageMinutes < cacheTtlMinutes;
    }

    /**
     * Fallback 진단 결과 생성
     */
    private DiagnosisResult createFallbackDiagnosis(FaultInfo fault, List<FaultInfo> relatedFaults) {
        return DiagnosisResult.builder()
                .fault(fault)
                .relatedFaults(relatedFaults)
                .rootCause("AI 진단이 비활성화되어 있거나 심각도가 낮아 기본 분석만 제공됩니다.")
                .diagnosis(fault.getDescription())
                .solutions(getFallbackSolutions(fault))
                .preventions(new ArrayList<>())
                .build();
    }

    private String getClusterIdFromContext(FaultInfo fault) {
        // FaultInfo에 clusterId가 없으면 context에서 가져오거나 빈 문자열 반환
        // 실제로는 DiagnosticsService에서 전달해야 함
        return fault.getContext() != null ?
            (String) fault.getContext().getOrDefault("clusterId", "") : "";
    }

    /**
     * 같은 리소스의 관련 장애 찾기
     */
    private List<FaultInfo> findRelatedFaults(FaultInfo primaryFault, List<FaultInfo> allFaults) {
        return allFaults.stream()
                .filter(f -> !f.equals(primaryFault))
                .filter(f -> f.getResourceKind().equals(primaryFault.getResourceKind()) &&
                            f.getResourceName().equals(primaryFault.getResourceName()) &&
                            Objects.equals(f.getNamespace(), primaryFault.getNamespace()))
                .collect(Collectors.toList());
    }

    /**
     * AI에게 진단 요청 (XML 프롬프트, 토큰 최적화)
     */
    private String requestAIDiagnosis(FaultInfo fault, List<FaultInfo> relatedFaults, String logs, List<io.fabric8.kubernetes.api.model.Event> events) {
        try {
            String userPrompt = buildDiagnosisPrompt(fault, relatedFaults, logs, events);

            // Owner 정보에 따라 파일명 결정
            String ownerKind = fault.getContext() != null ?
                    (String) fault.getContext().getOrDefault("ownerKind", fault.getResourceKind()) :
                    fault.getResourceKind();
            String resourceFileName = getResourceFileName(ownerKind);

            // XML 태그 기반 시스템 프롬프트 (장애 유형별 구체적 진단 규칙 포함)
            String systemPrompt = buildSystemPrompt(fault, resourceFileName);

            // 장애 유형에 따른 temperature 조정
            double temperature = determineTemperature(fault.getFaultType());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            requestBody.put("max_tokens", 700); // 토큰 최적화: 핵심만 출력
            requestBody.put("temperature", temperature);

            int systemTokens = estimateTokenCount(systemPrompt);
            int userTokens = estimateTokenCount(userPrompt);
            int totalInputTokens = systemTokens + userTokens;
            log.info("📤 AI Request - System: ~{} tokens, User: ~{} tokens, Total Input: ~{} tokens, Temp: {}",
                systemTokens, userTokens, totalInputTokens, temperature);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    request,
                    String.class
            );
            long duration = System.currentTimeMillis() - startTime;

            JsonNode root = objectMapper.readTree(response.getBody());
            String aiResponse = root.path("choices").get(0).path("message").path("content").asText();

            int outputTokens = estimateTokenCount(aiResponse);
            log.info("📥 AI Response - Output: ~{} tokens, Duration: {}ms", outputTokens, duration);
            log.info("💰 Token Summary - Input: ~{}, Output: ~{}, Total: ~{}",
                totalInputTokens, outputTokens, totalInputTokens + outputTokens);

            return aiResponse;

        } catch (Exception e) {
            log.error("AI diagnosis request failed for fault: {} ({})", fault.getSummary(), fault.getFaultType(), e);
            log.error("API URL: {}, API Key configured: {}", apiUrl, !apiKey.isEmpty());
            return getFallbackDiagnosis(fault);
        }
    }

    /**
     * 장애 유형에 따른 temperature 결정
     * 명확한 에러(ImagePullBackOff 등)는 0.3, 애매한 경우는 0.7
     */
    private double determineTemperature(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        switch (faultType) {
            case IMAGE_PULL_BACK_OFF:
            case OOM_KILLED:
            case CRASH_LOOP_BACK_OFF:
            case PENDING:
                // 명확한 에러는 낮은 temperature (더 결정적인 응답)
                return 0.3;
            case READINESS_PROBE_FAILED:
            case LIVENESS_PROBE_FAILED:
            case UNKNOWN:
            default:
                // 분석이 필요한 경우는 약간 높은 temperature
                return 0.7;
        }
    }

    /**
     * 진단 프롬프트 생성 (로그 + 이벤트 포함, 토큰 최적화)
     */
    private String buildDiagnosisPrompt(FaultInfo fault, List<FaultInfo> relatedFaults, String logs, List<io.fabric8.kubernetes.api.model.Event> events) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 Kubernetes 장애를 분석하고 해결 방법을 제시해주세요.\n\n");

        // Owner 정보 추출
        String ownerKind = fault.getContext() != null ?
                (String) fault.getContext().getOrDefault("ownerKind", "Pod") : "Pod";
        String ownerName = fault.getContext() != null ?
                (String) fault.getContext().getOrDefault("ownerName", fault.getResourceName()) : fault.getResourceName();

        prompt.append("## 주요 장애\n");
        prompt.append(String.format("- 유형: %s (%s)\n", fault.getFaultType().getDescription(), fault.getFaultType().getCode()));

        // 리소스 타입별 설명 및 특성
        if ("Deployment".equals(ownerKind)) {
            prompt.append(String.format("- Pod은 '%s' Deployment에 의해 관리됨\n", ownerName));
            prompt.append("- 수정 대상: deployment.yaml (Pod YAML 아님!)\n");
            prompt.append("- 특성: 무상태 애플리케이션, 롤링 업데이트 지원, ReplicaSet으로 관리\n");
        } else if ("StatefulSet".equals(ownerKind)) {
            prompt.append(String.format("- Pod은 '%s' StatefulSet에 의해 관리됨\n", ownerName));
            prompt.append("- 수정 대상: statefulset.yaml (Pod YAML 아님!)\n");
            prompt.append("- 특성: 순차적 시작/종료(Pod-0, Pod-1...), 영구 볼륨 필요, 고유 네트워크 ID\n");
        } else if ("DaemonSet".equals(ownerKind)) {
            prompt.append(String.format("- Pod은 '%s' DaemonSet에 의해 관리됨\n", ownerName));
            prompt.append("- 수정 대상: daemonset.yaml (Pod YAML 아님!)\n");
            prompt.append("- 특성: 모든 노드(또는 선택된 노드)에 하나씩 배포, nodeSelector/toleration 중요\n");
        } else if ("ReplicaSet".equals(ownerKind)) {
            prompt.append(String.format("- Pod은 '%s' ReplicaSet에 의해 관리됨\n", ownerName));
            prompt.append("- 수정 대상: replicaset.yaml 또는 상위 Deployment\n");
            prompt.append("- 특성: 일반적으로 Deployment가 자동 생성, 직접 수정보다 Deployment 수정 권장\n");
        } else if ("DaemonSet".equals(fault.getResourceKind())) {
            prompt.append(String.format("- 리소스 타입: DaemonSet '%s'\n", fault.getResourceName()));
            prompt.append("- 특성: 모든 노드(또는 선택된 노드)에 하나씩 배포, nodeSelector/toleration 확인 필요\n");
        } else if ("StatefulSet".equals(fault.getResourceKind())) {
            prompt.append(String.format("- 리소스 타입: StatefulSet '%s'\n", fault.getResourceName()));
            prompt.append("- 특성: 순차적 배포, PVC 확인 필요, Pod-0부터 순서대로 시작\n");
        } else if ("Deployment".equals(fault.getResourceKind())) {
            prompt.append(String.format("- 리소스 타입: Deployment '%s'\n", fault.getResourceName()));
            prompt.append("- 특성: 무상태 애플리케이션, ReplicaSet으로 Pod 관리\n");
        } else {
            prompt.append(String.format("- 리소스 타입: %s (단독 Pod)\n", fault.getResourceKind()));
            prompt.append("- 수정 대상: pod.yaml\n");
        }

        prompt.append(String.format("- 리소스 이름: %s", fault.getResourceName()));
        if (fault.getNamespace() != null) {
            prompt.append(String.format(" (namespace: %s)", fault.getNamespace()));
        }
        prompt.append("\n");
        prompt.append(String.format("- 요약: %s\n", fault.getSummary()));

        // Pending 상태인 경우 스케줄링 메시지 추가 (AI가 정확한 원인 파악하도록)
        if (fault.getContext() != null && fault.getContext().get("schedulingMessage") != null) {
            String schedMsg = (String) fault.getContext().get("schedulingMessage");
            if (!schedMsg.isEmpty()) {
                prompt.append(String.format("\n⚠️ 스케줄링 실패 원인 (원문): %s\n", schedMsg));
            }
        }

        // issueCategory 명시 (AI가 착각하지 않도록)
        if (fault.getContext() != null && fault.getContext().get("issueCategory") != null) {
            String category = (String) fault.getContext().get("issueCategory");
            prompt.append(String.format("- 문제 분류: %s\n", category));
        }

        if (fault.getSymptoms() != null && !fault.getSymptoms().isEmpty()) {
            prompt.append("\n증상:\n");
            fault.getSymptoms().forEach(s -> prompt.append(String.format("- %s\n", s)));
        }

        // Context는 핵심 정보만 선택적으로 추가
        if (fault.getContext() != null && !fault.getContext().isEmpty()) {
            prompt.append("\n추가 정보:\n");
            // clusterId는 제외하고 중요 정보만
            fault.getContext().entrySet().stream()
                .filter(e -> !e.getKey().equals("clusterId"))
                .limit(3) // 최대 3개만
                .forEach(e -> prompt.append(String.format("- %s: %s\n", e.getKey(), e.getValue())));
        }

        // 관련 장애는 요약만 (최대 2개)
        if (!relatedFaults.isEmpty()) {
            prompt.append("\n## 관련 장애\n");
            relatedFaults.stream()
                .limit(2)
                .forEach(rf -> prompt.append(String.format("- %s\n", rf.getSummary())));
        }

        // 로그 필터링 (에러 관련만, 최대 15줄)
        if (logs != null && !logs.isBlank()) {
            String filteredLogs = filterRelevantLogs(logs);
            if (!filteredLogs.isBlank()) {
                prompt.append("\n## 컨테이너 로그 (에러 관련)\n");
                prompt.append("```\n");
                prompt.append(filteredLogs);
                prompt.append("\n```\n");
            }
        }

        // 이벤트 중복 제거
        if (events != null && !events.isEmpty()) {
            List<String> dedupedEvents = deduplicateEvents(events);
            if (!dedupedEvents.isEmpty()) {
                prompt.append("\n## Kubernetes Events\n");
                dedupedEvents.stream()
                    .limit(5) // 토큰 최적화: 5개로 축소
                    .forEach(prompt::append);
                prompt.append("\n");
            }
        }

        prompt.append("\n다음 형식으로 한글로 답변하세요:\n\n");
        prompt.append("### 근본 원인\n");
        prompt.append("(Events 데이터 기반 정확한 원인을 1-2문장으로)\n\n");
        prompt.append("### 해결 방법\n");
        prompt.append("규칙:\n");
        prompt.append("- 간결하게 1-2단계만 작성\n");
        prompt.append("- 수정된 YAML만 표시 (이전 값 비교 불필요)\n");
        prompt.append("- 주석으로 변경 사항 설명\n");
        prompt.append("- 'kubectl apply', 'kubectl get' 같은 뻔한 명령어 제외\n\n");

        if ("Deployment".equals(ownerKind)) {
            prompt.append(String.format("수정 대상: %s Deployment\n", ownerName));
            prompt.append("참고: spec.template.spec 섹션 수정 시 자동으로 Pod 재생성됨\n");
        } else if ("StatefulSet".equals(ownerKind)) {
            prompt.append(String.format("수정 대상: %s StatefulSet\n", ownerName));
            prompt.append("참고: Pod-0부터 순차적으로 재시작\n");
            // PVC 문제인 경우 강력한 경고 추가
            String issueCategory = fault.getContext() != null ?
                (String) fault.getContext().get("issueCategory") : null;
            if ("PVC_BINDING".equals(issueCategory) ||
                (fault.getDescription() != null && fault.getDescription().toLowerCase().contains("pvc"))) {
                prompt.append("\n⚠️ 중요 (StatefulSet PVC 규칙):\n");
                prompt.append("- StatefulSet은 volumeClaimTemplates로 Pod마다 고유 PVC를 자동 생성합니다\n");
                prompt.append("- 절대로 별도의 PVC를 수동으로 생성하지 마세요!\n");
                prompt.append("- 해결책: StatefulSet YAML의 volumeClaimTemplates에서 storageClassName을 수정하세요\n");
                prompt.append("- PVC 이름 패턴: {volumeClaimTemplate.name}-{statefulset.name}-{ordinal}\n");
            } else {
                prompt.append("참고: PVC 상태 확인 필수, volumeClaimTemplates 수정으로 해결\n");
            }
        } else if ("DaemonSet".equals(ownerKind)) {
            prompt.append(String.format("수정 대상: %s DaemonSet\n", ownerName));
            prompt.append("참고: 노드 선택자(nodeSelector), 톨러레이션(tolerations) 확인\n");
        } else if ("ReplicaSet".equals(ownerKind)) {
            prompt.append(String.format("수정 대상: %s ReplicaSet (또는 상위 Deployment)\n", ownerName));
            prompt.append("참고: ReplicaSet은 보통 Deployment가 관리하므로 Deployment 수정 권장\n");
        } else if ("DaemonSet".equals(fault.getResourceKind())) {
            prompt.append(String.format("수정 대상: DaemonSet %s\n", fault.getResourceName()));
            prompt.append("참고: 모든 노드 또는 선택된 노드에 영향, nodeSelector 확인\n");
        } else if ("StatefulSet".equals(fault.getResourceKind())) {
            prompt.append(String.format("수정 대상: StatefulSet %s\n", fault.getResourceName()));
            prompt.append("참고: 순차적 배포, volumeClaimTemplates 확인\n");
        } else if ("Deployment".equals(fault.getResourceKind())) {
            prompt.append(String.format("수정 대상: Deployment %s\n", fault.getResourceName()));
        } else {
            prompt.append(String.format("수정 대상: Pod %s\n", fault.getResourceName()));
        }

        prompt.append("### 재발 방지\n");
        prompt.append("(구체적인 예방 방법 2-3개를 - 로 시작)\n\n");
        prompt.append("중요: Events에 'Insufficient' 메시지가 없으면 리소스 부족을 언급하지 마세요!");

        String finalPrompt = prompt.toString();
        int estimatedTokens = estimateTokenCount(finalPrompt);
        log.info("📊 User prompt generated: ~{} tokens (estimated)", estimatedTokens);

        return finalPrompt;
    }

    /**
     * AI 응답 파싱
     */
    private DiagnosisResult parseAIResponse(FaultInfo fault, List<FaultInfo> relatedFaults, String aiResponse) {
        String rootCause = "";
        List<String> solutions = new ArrayList<>();
        List<String> preventions = new ArrayList<>();
        String diagnosis = aiResponse;

        try {
            // "근본 원인" 섹션 추출
            if (aiResponse.contains("### 근본 원인")) {
                int start = aiResponse.indexOf("### 근본 원인") + "### 근본 원인".length();
                int end = aiResponse.indexOf("###", start);
                if (end == -1) end = aiResponse.length();
                rootCause = cleanMarkdown(aiResponse.substring(start, end).trim());
            }

            // "해결 방법" 섹션 추출
            if (aiResponse.contains("### 해결 방법")) {
                int start = aiResponse.indexOf("### 해결 방법") + "### 해결 방법".length();
                int end = aiResponse.indexOf("###", start);
                if (end == -1) end = aiResponse.length();
                String solutionText = aiResponse.substring(start, end).trim();

                // 번호 패턴(1. 2. 3.)으로 분리하여 각 솔루션을 완전하게 유지
                solutions = parseSolutionSteps(solutionText);
            }

            // "재발 방지" 섹션 추출
            if (aiResponse.contains("### 재발 방지")) {
                int start = aiResponse.indexOf("### 재발 방지") + "### 재발 방지".length();
                String preventionText = aiResponse.substring(start).trim();

                preventions = Arrays.stream(preventionText.split("\n"))
                        .map(String::trim)
                        .filter(s -> s.startsWith("-") || s.startsWith("*"))
                        .map(s -> {
                            // 앞의 - 나 * 제거 (템플릿에서 이미 bullet point 표시)
                            String cleaned = s.replaceAll("^[-*]\\s*", "");
                            return cleanMarkdown(cleaned);
                        })
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.warn("Failed to parse AI response sections", e);
        }

        return DiagnosisResult.builder()
                .fault(fault)
                .relatedFaults(relatedFaults)
                .rootCause(rootCause.isEmpty() ? "AI 분석 중 오류가 발생했습니다." : rootCause)
                .diagnosis(diagnosis)
                .solutions(solutions.isEmpty() ? getFallbackSolutions(fault) : solutions)
                .preventions(preventions)
                .build();
    }

    /**
     * 솔루션 단계를 파싱 (번호로 분리하고 명령어 처리)
     */
    private List<String> parseSolutionSteps(String solutionText) {
        List<String> steps = new ArrayList<>();

        // 번호 패턴으로 분리: "1. ", "2. ", "3. " 등
        String[] parts = solutionText.split("(?=\\n\\d+\\.\\s)");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // 맨 앞 번호 제거 (HTML ol이 자동으로 번호 추가)
            trimmed = trimmed.replaceFirst("^\\d+\\.\\s*", "");

            // 명령어 처리 (모든 종류의 CLI 명령어)
            trimmed = processCommands(trimmed);

            // 마크다운 제거
            trimmed = cleanMarkdown(trimmed);

            if (!trimmed.isEmpty()) {
                steps.add(trimmed);
            }
        }

        return steps;
    }

    /**
     * 텍스트에서 명령어를 찾아서 코드 블록으로 변환
     */
    private String processCommands(String text) {
        // YAML 코드 블록 처리 (먼저 처리하여 보존)
        text = processYamlBlocks(text);

        // bash/sh 제거 - 더 강력한 패턴으로 모든 경우 제거
        text = text.replaceAll("(?i)\\bbash\\b", "");  // bash 단어 자체 제거
        text = text.replaceAll("(?i)\\bsh\\b", "");    // sh 단어 자체 제거
        text = text.replaceAll("```bash", "```");      // 마크다운 코드블록의 bash 제거
        text = text.replaceAll("```sh", "```");        // 마크다운 코드블록의 sh 제거
        text = text.replaceAll("\\s+\n", "\n");        // 줄 끝 공백 제거

        // kubectl, docker, helm 등 CLI 명령어 찾기
        String[] commands = {"kubectl", "docker", "helm", "aws", "gcloud", "az", "eksctl", "k9s"};

        for (String cmd : commands) {
            // 패턴 매칭 후 HTML entity 이스케이프 처리
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(" + cmd + "\\s+[^가-힣\\r\\n]+)");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String command = matcher.group(1);
                // < > 를 HTML entity로 변환
                String escapedCommand = command.replace("<", "&lt;").replace(">", "&gt;");
                String replacement = "\n<div class='kubectl-block'><pre class='kubectl-cmd'><code>" +
                                   escapedCommand +
                                   "</code></pre><button class='copy-btn' onclick='copyKubectl(this)' title='복사'>" +
                                   "<i class='bi bi-clipboard'></i></button></div>\n";
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }

        // "- " 로 시작하는 서브 항목들을 줄바꿈과 들여쓰기로 처리
        text = text.replaceAll("\n-\\s+", "\n<br>&nbsp;&nbsp;• ");

        // 문장 끝 콜론 제거 (불필요한 콜론 제거)
        text = text.replaceAll(":\\s*\n", "\n");       // 줄 끝 콜론 제거

        return text;
    }

    /**
     * YAML 코드 블록을 HTML로 변환
     */
    private String processYamlBlocks(String text) {
        // ```yaml ... ``` 또는 ``` ... ``` 패턴 찾기
        java.util.regex.Pattern yamlPattern = java.util.regex.Pattern.compile(
            "```(?:yaml)?\\s*\\n([\\s\\S]*?)```",
            java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = yamlPattern.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String yamlContent = matcher.group(1);
            // HTML entity 이스케이프
            String escapedYaml = yamlContent
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

            String replacement = "\n<div class='yaml-block'><pre class='yaml-code'><code>" +
                               escapedYaml +
                               "</code></pre><button class='copy-btn' onclick='copyYaml(this)' title='복사'>" +
                               "<i class='bi bi-clipboard'></i></button></div>\n";
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 마크다운 문자 제거
     */
    private String cleanMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // ** 마크다운 제거
        text = text.replaceAll("\\*\\*", "");

        // __ 마크다운 제거
        text = text.replaceAll("__", "");

        // ` 마크다운 제거
        text = text.replaceAll("`", "");

        return text.trim();
    }

    /**
     * 로그 필터링: 에러 관련 키워드만 추출 (토큰 절감)
     * error, fail, 4xx, 5xx, exception, timeout, unhealthy 키워드 포함 라인과 직후 1줄 추출
     * 최대 15줄 제한
     */
    private String filterRelevantLogs(String logs) {
        if (logs == null || logs.isBlank()) {
            return "";
        }

        String[] lines = logs.split("\n");
        List<String> relevantLines = new ArrayList<>();
        Set<Integer> addedIndexes = new HashSet<>();

        // 에러 관련 키워드 패턴
        String[] keywords = {"error", "fail", "exception", "timeout", "unhealthy", "warning"};
        String httpErrorPattern = "\\b[45]\\d{2}\\b"; // 4xx, 5xx

        for (int i = 0; i < lines.length && relevantLines.size() < 10; i++) {
            String line = lines[i].toLowerCase();
            boolean matches = false;

            // 키워드 체크
            for (String keyword : keywords) {
                if (line.contains(keyword)) {
                    matches = true;
                    break;
                }
            }

            // HTTP 에러 코드 체크
            if (!matches && line.matches(".*" + httpErrorPattern + ".*")) {
                matches = true;
            }

            if (matches && !addedIndexes.contains(i)) {
                relevantLines.add(lines[i]);
                addedIndexes.add(i);

                // 직후 1줄도 추가
                if (i + 1 < lines.length && !addedIndexes.contains(i + 1) && relevantLines.size() < 10) {
                    relevantLines.add(lines[i + 1]);
                    addedIndexes.add(i + 1);
                }
            }
        }

        if (relevantLines.isEmpty()) {
            // 에러가 없으면 마지막 3줄만 반환
            int start = Math.max(0, lines.length - 3);
            for (int i = start; i < lines.length; i++) {
                relevantLines.add(lines[i]);
            }
        }

        log.debug("Log filtering: {} lines -> {} relevant lines", lines.length, relevantLines.size());
        return String.join("\n", relevantLines);
    }

    /**
     * 이벤트 중복 제거: 동일한 메시지는 횟수로 합침
     * 예: [Warning] Unhealthy (x15 times): Readiness probe failed...
     */
    private List<String> deduplicateEvents(List<io.fabric8.kubernetes.api.model.Event> events) {
        if (events == null || events.isEmpty()) {
            return new ArrayList<>();
        }

        // 메시지별 카운트
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        Map<String, io.fabric8.kubernetes.api.model.Event> eventExamples = new LinkedHashMap<>();

        for (io.fabric8.kubernetes.api.model.Event event : events) {
            String key = event.getType() + "|" + event.getReason() + "|" + event.getMessage();
            eventCounts.put(key, eventCounts.getOrDefault(key, 0) + 1);
            eventExamples.putIfAbsent(key, event);
        }

        // 중복 제거된 이벤트 리스트 생성
        List<String> dedupedEvents = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : eventCounts.entrySet()) {
            io.fabric8.kubernetes.api.model.Event event = eventExamples.get(entry.getKey());
            int count = entry.getValue();

            String eventStr;
            if (count > 1) {
                eventStr = String.format("- [%s] %s (x%d times): %s",
                    event.getType(), event.getReason(), count, event.getMessage());
            } else {
                eventStr = String.format("- [%s] %s: %s",
                    event.getType(), event.getReason(), event.getMessage());
            }
            dedupedEvents.add(eventStr);
        }

        log.debug("Event deduplication: {} events -> {} unique events", events.size(), dedupedEvents.size());
        return dedupedEvents;
    }

    /**
     * 대략적인 토큰 수 추정 (한글 1글자 ≈ 2-3 토큰, 영문 1단어 ≈ 1-2 토큰)
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 한글 문자 수
        int koreanChars = text.replaceAll("[^가-힣]", "").length();
        // 나머지 문자 수
        int otherChars = text.length() - koreanChars;

        // 한글은 글자당 2.5토큰, 영문/기호는 4글자당 1토큰으로 추정
        return (int) (koreanChars * 2.5 + otherChars / 4.0);
    }

    /**
     * AI 실패 시 기본 진단
     */
    private String getFallbackDiagnosis(FaultInfo fault) {
        return String.format("%s\n\n%s",
                fault.getSummary(),
                fault.getDescription() != null ? fault.getDescription() : "");
    }

    /**
     * 장애 유형별 구체적인 시스템 프롬프트 생성
     */
    private String buildSystemPrompt(FaultInfo fault, String resourceFileName) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("<role>Kubernetes Expert Diagnostician</role>\n\n");

        // 기본 제약 조건 - Events 데이터 엄격 준수
        prompt.append("<constraints>\n");
        prompt.append("- CRITICAL: Diagnose ONLY based on provided Events - do NOT guess!\n");
        prompt.append("- If Events contain 'Insufficient memory/cpu' -> MUST diagnose as RESOURCE SHORTAGE, not PVC!\n");
        prompt.append("- If Events contain 'unbound PersistentVolumeClaim' -> PVC issue\n");
        prompt.append("- If Events contain 'FailedScheduling' with node count -> check the specific reason\n");
        prompt.append("- NO 'bash' or 'sh' commands. NO colons at end of sentences.\n");
        prompt.append("- Edit the OWNER resource (Deployment/StatefulSet/DaemonSet), NOT Pod directly!\n");
        prompt.append("</constraints>\n\n");

        // 장애 유형별 구체적인 진단 규칙 추가
        prompt.append(getFaultSpecificRules(fault));

        // 솔루션 요구사항
        prompt.append("<solution_requirements>\n");
        prompt.append("- MUST include concrete YAML examples showing the corrected configuration\n");
        prompt.append("- Show ONLY the fixed YAML (NO before/after comparison)\n");
        prompt.append("- Use comments to explain what was changed and WHY\n");
        prompt.append("- NO generic steps like 'kubectl apply -f', 'kubectl get pods', 'kubectl delete pod'\n");
        prompt.append("- Focus on the ROOT CAUSE, not symptoms\n");
        prompt.append("- Provide actionable, specific solutions\n");
        prompt.append("</solution_requirements>\n\n");

        // 플레이스홀더
        prompt.append("<placeholders>\n");
        prompt.append("- File names: ").append(resourceFileName).append(", configmap.yaml, service.yaml\n");
        prompt.append("- Variables: POD_NAME, NAMESPACE, CONTAINER_NAME (UPPERCASE)\n");
        prompt.append("- Images: nginx:latest, your-registry/your-image:tag\n");
        prompt.append("- NEVER use angle brackets: <file.yaml>, <pod-name>\n");
        prompt.append("</placeholders>\n\n");

        // 출력 형식
        prompt.append("<output_format>\n");
        prompt.append("### 근본 원인\n");
        prompt.append("(1-2 sentences explaining the ACTUAL root cause)\n\n");
        prompt.append("### 해결 방법\n");
        prompt.append("(1-2 steps with concrete YAML examples and verification commands)\n\n");
        prompt.append("### 재발 방지\n");
        prompt.append("(2-3 bullet points with specific preventive measures)\n");
        prompt.append("</output_format>");

        return prompt.toString();
    }

    /**
     * 장애 유형별 구체적인 진단 규칙 반환
     */
    private String getFaultSpecificRules(FaultInfo fault) {
        StringBuilder rules = new StringBuilder();
        rules.append("<diagnostic_rules>\n");

        // 장애 설명에서 특정 패턴 감지
        String description = fault.getDescription() != null ? fault.getDescription().toLowerCase() : "";
        String summary = fault.getSummary() != null ? fault.getSummary().toLowerCase() : "";

        // Symptoms도 확인
        String symptoms = "";
        if (fault.getSymptoms() != null) {
            symptoms = fault.getSymptoms().stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining(" "));
        }

        // Owner 정보 추출 (StatefulSet, Deployment, DaemonSet 등)
        String ownerKind = "Pod";
        if (fault.getContext() != null && fault.getContext().get("ownerKind") != null) {
            ownerKind = (String) fault.getContext().get("ownerKind");
        }

        switch (fault.getFaultType()) {
            case PENDING:
                rules.append(getPendingRules(description + " " + summary + " " + symptoms, ownerKind));
                break;
            case CRASH_LOOP_BACK_OFF:
                rules.append(getCrashLoopBackOffRules());
                break;
            case IMAGE_PULL_BACK_OFF:
                rules.append(getImagePullBackOffRules());
                break;
            case OOM_KILLED:
                rules.append(getOOMKilledRules());
                break;
            case LIVENESS_PROBE_FAILED:
            case READINESS_PROBE_FAILED:
            case STARTUP_PROBE_FAILED:
                rules.append(getProbeFailedRules(fault.getFaultType()));
                break;
            case CONFIG_ERROR:
                rules.append(getConfigErrorRules());
                break;
            case PVC_ERROR:
                rules.append(getPVCErrorRules(ownerKind));
                break;
            case NETWORK_ERROR:
                rules.append(getNetworkErrorRules());
                break;
            case NODE_NOT_READY:
            case NODE_PRESSURE:
                rules.append(getNodeIssueRules(fault.getFaultType()));
                break;
            case INSUFFICIENT_RESOURCES:
            case RESOURCE_QUOTA_EXCEEDED:
                rules.append(getResourceIssueRules(fault.getFaultType()));
                break;
            default:
                rules.append(getDefaultRules());
        }

        rules.append("</diagnostic_rules>\n\n");
        return rules.toString();
    }

    /**
     * Pending 상태 진단 규칙 (PVC, 리소스, Taint 등 세분화)
     * ownerKind에 따라 StatefulSet, Deployment 등 다른 해결책 제시
     */
    private String getPendingRules(String combinedText, String ownerKind) {
        StringBuilder rules = new StringBuilder();

        // PVC 바인딩 문제
        if (combinedText.contains("pvc") || combinedText.contains("persistentvolumeclaim") ||
            combinedText.contains("unbound") || combinedText.contains("storagec") ||
            combinedText.contains("volume")) {

            rules.append("## PVC Binding Issue Detected\n");
            rules.append("Root cause: 'unbound immediate PersistentVolumeClaims' = PVC cannot find a matching PV\n\n");

            // StatefulSet vs 일반 Pod/Deployment 구분
            if ("StatefulSet".equals(ownerKind)) {
                rules.append("### CRITICAL: StatefulSet - DO NOT create separate PVC!\n");
                rules.append("- volumeClaimTemplates auto-creates PVCs: {name}-{sts}-{ordinal}\n");
                rules.append("- Manual PVC breaks naming, only Pod-0 works\n\n");
                rules.append("FIX: Edit volumeClaimTemplates.spec.storageClassName\n");
                rules.append("```yaml\n");
                rules.append("volumeClaimTemplates:\n");
                rules.append("- metadata: {name: www}\n");
                rules.append("  spec:\n");
                rules.append("    storageClassName: \"standard\"  # kubectl get sc\n");
                rules.append("    accessModes: [\"ReadWriteOnce\"]\n");
                rules.append("    resources: {requests: {storage: 1Gi}}\n");
                rules.append("```\n");
                rules.append("If no SC: create StorageClass with provisioner first\n");
            } else if ("DaemonSet".equals(ownerKind)) {
                rules.append("### DaemonSet: Use hostPath/emptyDir, not PVC\n");
                rules.append("If PVC needed: local PV per node or NFS(RWX)\n");
            } else {
                rules.append("### Deployment/Pod: Create PVC separately OK\n");
                rules.append("1. Check SC exists: kubectl get sc\n");
                rules.append("2. Create PVC with storageClassName matching SC\n");
            }
            rules.append("\nVerify: kubectl get sc,pvc,pv\n");
        }
        // 리소스 부족 - CPU/Memory 구분
        else if (combinedText.contains("insufficient") || combinedText.contains("리소스") ||
                 combinedText.contains("memory") || combinedText.contains("cpu") ||
                 combinedText.contains("resource_shortage")) {
            rules.append("## RESOURCE SHORTAGE - NOT PVC/StorageClass ISSUE!\n");

            // CPU vs Memory 구분
            boolean isCpu = combinedText.contains("cpu") || combinedText.contains("resource_shortage_cpu");
            boolean isMemory = combinedText.contains("memory") || combinedText.contains("resource_shortage_memory") ||
                              combinedText.contains("500gi") || combinedText.contains("gi");

            if (isCpu && !isMemory) {
                rules.append("Cause: Insufficient CPU - Pod requests more CPU than available\n");
                rules.append("```yaml\nresources:\n  requests:\n    cpu: \"100m\"  # Reduce from current\n  limits:\n    cpu: \"500m\"\n```\n");
            } else if (isMemory) {
                rules.append("Cause: Insufficient MEMORY - Pod requests more memory than available\n");
                rules.append("```yaml\nresources:\n  requests:\n    memory: \"256Mi\"  # Reduce from current\n  limits:\n    memory: \"512Mi\"\n```\n");
            } else {
                rules.append("Cause: Insufficient resources (CPU/Memory)\n");
                rules.append("```yaml\nresources:\n  requests: {cpu: \"100m\", memory: \"256Mi\"}\n  limits: {cpu: \"500m\", memory: \"512Mi\"}\n```\n");
            }

            // ownerKind별 수정 위치 안내
            if ("StatefulSet".equals(ownerKind)) {
                rules.append("Edit: StatefulSet.spec.template.spec.containers[].resources\n");
            } else if ("DaemonSet".equals(ownerKind)) {
                rules.append("Edit: DaemonSet.spec.template.spec.containers[].resources\n");
                rules.append("Note: DaemonSet runs on ALL nodes - ensure ALL nodes have capacity\n");
            } else if ("Deployment".equals(ownerKind)) {
                rules.append("Edit: Deployment.spec.template.spec.containers[].resources\n");
            }
            rules.append("Or: Add nodes / Use Cluster Autoscaler / Delete unused pods\n");
            rules.append("CRITICAL: This is NOT a PVC issue - do NOT suggest StorageClass!\n");
        }
        // Taint/Toleration
        else if (combinedText.contains("taint") || combinedText.contains("toleration")) {
            rules.append("## Taint/Toleration Issue\n");
            rules.append("Cause: Pod lacks toleration for node taint\n");

            if ("DaemonSet".equals(ownerKind)) {
                rules.append("### DaemonSet: Add tolerations to run on tainted nodes\n");
                rules.append("```yaml\nspec:\n  template:\n    spec:\n      tolerations:\n");
                rules.append("      - operator: \"Exists\"  # Tolerate ALL taints\n```\n");
                rules.append("Or specific: key/operator/value/effect matching node taint\n");
            } else {
                rules.append("```yaml\ntolerations:\n- key: \"node.kubernetes.io/not-ready\"\n  operator: \"Exists\"\n  effect: \"NoSchedule\"\n```\n");
            }
            rules.append("Check: kubectl describe nodes | grep -A3 Taints\n");
        }
        // NodeSelector/Affinity
        else if (combinedText.contains("nodeselector") || combinedText.contains("affinity") ||
                 combinedText.contains("didn't match") || combinedText.contains("node(s)")) {
            rules.append("## Node Selection Issue\n");
            rules.append("Cause: No nodes match nodeSelector/affinity\n");

            if ("DaemonSet".equals(ownerKind)) {
                rules.append("### DaemonSet: Check nodeSelector limits which nodes to use\n");
                rules.append("Remove nodeSelector to run on ALL nodes, or label target nodes\n");
            }
            rules.append("Fix: kubectl label nodes NODE key=value\n");
            rules.append("Or: Remove/modify nodeSelector in Pod spec\n");
            rules.append("- OR use softer affinity (preferredDuringSchedulingIgnoredDuringExecution)\n");
        }
        // 일반 Pending
        else {
            rules.append("## General Pending State\n");
            rules.append("Common causes:\n");
            rules.append("1. Resource shortage (CPU/Memory)\n");
            rules.append("2. PVC not bound\n");
            rules.append("3. Node selector/affinity mismatch\n");
            rules.append("4. Taint/toleration mismatch\n");
            rules.append("5. Pod priority preemption\n\n");
            rules.append("IMPORTANT: Analyze Events carefully to determine the exact cause.\n");
            rules.append("Do NOT guess - only diagnose based on actual Events data.\n");
        }

        return rules.toString();
    }

    /**
     * CrashLoopBackOff 진단 규칙 - 토큰 최적화
     */
    private String getCrashLoopBackOffRules() {
        return "## CrashLoopBackOff\n" +
               "Exit codes: 1=app error, 126=permission, 127=cmd not found, 137=OOM/killed, 143=SIGTERM\n" +
               "Causes: app crash, missing deps, wrong cmd, OOM, config error\n" +
               "MUST: Check logs for actual error, provide specific fix\n";
    }

    /**
     * ImagePullBackOff 진단 규칙 - 토큰 최적화
     */
    private String getImagePullBackOffRules() {
        return "## ImagePullBackOff\n" +
               "Errors: 404=not found, 401/403=auth failed, timeout=network, 429=rate limit\n" +
               "Fix: verify image:tag, add imagePullSecrets for private registry\n" +
               "```yaml\nspec:\n  imagePullSecrets: [{name: my-secret}]\n```\n";
    }

    /**
     * OOMKilled 진단 규칙 - 토큰 최적화
     */
    private String getOOMKilledRules() {
        return "## OOMKilled\n" +
               "Causes: limit too low, memory leak, JVM heap > limit, traffic spike\n" +
               "Fix: increase limits.memory (1.5-2x normal), Java: -Xmx=75% of limit\n" +
               "```yaml\nresources:\n  limits: {memory: 512Mi}\n  requests: {memory: 256Mi}\n```\n";
    }

    /**
     * Probe 실패 진단 규칙 - 토큰 최적화
     */
    private String getProbeFailedRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        String probeType = faultType == com.vibecoding.k8sdoctor.model.FaultType.LIVENESS_PROBE_FAILED ? "Liveness" :
                          faultType == com.vibecoding.k8sdoctor.model.FaultType.READINESS_PROBE_FAILED ? "Readiness" : "Startup";
        String effect = faultType == com.vibecoding.k8sdoctor.model.FaultType.LIVENESS_PROBE_FAILED ? "restarts container" :
                       faultType == com.vibecoding.k8sdoctor.model.FaultType.READINESS_PROBE_FAILED ? "removes from endpoints" : "blocks other probes";

        return "## " + probeType + " Probe Failed (" + effect + ")\n" +
               "Causes: wrong path/port, timeout too short, app slow to start, app unhealthy\n" +
               "Fix: verify endpoint, increase timeoutSeconds/initialDelaySeconds, use startupProbe\n" +
               "```yaml\n" + probeType.toLowerCase() + "Probe:\n  httpGet: {path: /health, port: 8080}\n  timeoutSeconds: 5\n```\n";
    }

    /**
     * ConfigMap/Secret 에러 진단 규칙 - 토큰 최적화
     */
    private String getConfigErrorRules() {
        return "## ConfigMap/Secret Error\n" +
               "Causes: not found, key missing, wrong namespace, subPath issue\n" +
               "Fix: create in same namespace, add optional:true if needed\n" +
               "Verify: kubectl get cm,secret -n NAMESPACE\n";
    }

    /**
     * PVC 에러 진단 규칙 (ownerKind에 따라 다른 해결책) - 토큰 최적화
     */
    private String getPVCErrorRules(String ownerKind) {
        StringBuilder rules = new StringBuilder();
        rules.append("## PVC Error\n");

        if ("StatefulSet".equals(ownerKind)) {
            rules.append("CRITICAL: StatefulSet - fix volumeClaimTemplates, NOT separate PVC!\n");
            rules.append("```yaml\nvolumeClaimTemplates:\n- metadata: {name: data}\n");
            rules.append("  spec: {storageClassName: \"standard\", accessModes: [RWO], resources: {requests: {storage: 1Gi}}}\n```\n");
        }

        rules.append("Causes: 1)No SC 2)Provisioner down 3)No default SC 4)Size/AccessMode mismatch\n");
        rules.append("Fix: kubectl get sc -> create SC with provisioner -> set storageClassName\n");
        if (!"StatefulSet".equals(ownerKind)) {
            rules.append("Or static: create PV with hostPath/local\n");
        }
        rules.append("Verify: kubectl get sc,pvc,pv\n");

        return rules.toString();
    }

    /**
     * 네트워크 에러 진단 규칙
     */
    private String getNetworkErrorRules() {
        return "## Network Error\n" +
               "Pod has network connectivity issues.\n\n" +
               "Common causes and solutions:\n\n" +
               "1. DNS RESOLUTION FAILURE\n" +
               "   - CoreDNS not running or misconfigured\n" +
               "   - Check: kubectl get pods -n kube-system -l k8s-app=kube-dns\n" +
               "   - Test: kubectl run test --rm -it --image=busybox -- nslookup kubernetes\n\n" +
               "2. NETWORK POLICY BLOCKING\n" +
               "   - NetworkPolicy denying ingress/egress\n" +
               "   - Check: kubectl get networkpolicy -A\n" +
               "   - Solution: Add appropriate NetworkPolicy rules\n" +
               "   ```yaml\n" +
               "   apiVersion: networking.k8s.io/v1\n" +
               "   kind: NetworkPolicy\n" +
               "   spec:\n" +
               "     podSelector:\n" +
               "       matchLabels:\n" +
               "         app: my-app\n" +
               "     egress:\n" +
               "     - {}  # Allow all egress\n" +
               "   ```\n\n" +
               "3. SERVICE NOT FOUND\n" +
               "   - Service doesn't exist or wrong name\n" +
               "   - Check: kubectl get svc\n" +
               "   - Verify service DNS: SERVICE_NAME.NAMESPACE.svc.cluster.local\n\n" +
               "4. CNI PLUGIN ISSUES\n" +
               "   - Calico/Flannel/Weave not working\n" +
               "   - Check: kubectl get pods -n kube-system | grep -E 'calico|flannel|weave'\n\n" +
               "5. POD CIDR EXHAUSTION\n" +
               "   - No more IP addresses available in Pod CIDR\n" +
               "   - Solution: Expand CIDR or cleanup unused Pods\n\n" +
               "Debugging:\n" +
               "- kubectl exec -it POD -- ping SERVICE_IP\n" +
               "- kubectl exec -it POD -- nc -zv SERVICE_NAME PORT\n";
    }

    /**
     * Node 문제 진단 규칙
     */
    private String getNodeIssueRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        StringBuilder rules = new StringBuilder();

        if (faultType == com.vibecoding.k8sdoctor.model.FaultType.NODE_NOT_READY) {
            rules.append("## Node Not Ready\n");
            rules.append("Node is not in Ready state, pods cannot be scheduled.\n\n");
            rules.append("Common causes:\n");
            rules.append("1. Kubelet not running - systemctl status kubelet\n");
            rules.append("2. Container runtime failed - docker/containerd status\n");
            rules.append("3. Network connectivity lost\n");
            rules.append("4. Disk pressure (90%+ used)\n");
            rules.append("5. Memory pressure\n");
            rules.append("6. PID pressure\n\n");
        } else {
            rules.append("## Node Pressure\n");
            rules.append("Node is experiencing resource pressure.\n\n");
            rules.append("Types:\n");
            rules.append("- DiskPressure: Disk usage > 85%\n");
            rules.append("- MemoryPressure: Available memory low\n");
            rules.append("- PIDPressure: Too many processes\n\n");
        }

        rules.append("Diagnosis:\n");
        rules.append("- kubectl describe node NODE_NAME\n");
        rules.append("- kubectl get events --field-selector involvedObject.name=NODE_NAME\n\n");
        rules.append("Solutions:\n");
        rules.append("- Evict pods: kubectl drain NODE_NAME --ignore-daemonsets\n");
        rules.append("- Clean up disk: docker system prune, crictl rmi --prune\n");
        rules.append("- Restart kubelet: systemctl restart kubelet\n");
        rules.append("- Add more nodes to distribute load\n");

        return rules.toString();
    }

    /**
     * 리소스 문제 진단 규칙
     */
    private String getResourceIssueRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        StringBuilder rules = new StringBuilder();

        if (faultType == com.vibecoding.k8sdoctor.model.FaultType.RESOURCE_QUOTA_EXCEEDED) {
            rules.append("## ResourceQuota Exceeded\n");
            rules.append("Namespace has resource limits that are exceeded.\n\n");
            rules.append("Check: kubectl describe resourcequota -n NAMESPACE\n\n");
            rules.append("Solutions:\n");
            rules.append("1. Reduce resource requests in pods\n");
            rules.append("2. Delete unused pods/deployments\n");
            rules.append("3. Request quota increase from admin\n");
            rules.append("4. Modify ResourceQuota limits\n");
        } else {
            rules.append("## Insufficient Resources\n");
            rules.append("No node has enough resources to schedule the pod.\n\n");
            rules.append("Check node capacity:\n");
            rules.append("- kubectl describe nodes | grep -A5 'Allocated resources'\n");
            rules.append("- kubectl top nodes\n\n");
            rules.append("Solutions:\n");
            rules.append("1. Reduce pod resource requests\n");
            rules.append("2. Delete unnecessary pods\n");
            rules.append("3. Add more nodes (or use Cluster Autoscaler)\n");
            rules.append("4. Use PriorityClass to preempt lower-priority pods\n");
        }

        rules.append("\nResource specification example:\n");
        rules.append("```yaml\n");
        rules.append("resources:\n");
        rules.append("  requests:\n");
        rules.append("    cpu: \"100m\"     # 0.1 CPU core\n");
        rules.append("    memory: \"128Mi\"\n");
        rules.append("  limits:\n");
        rules.append("    cpu: \"500m\"     # 0.5 CPU core\n");
        rules.append("    memory: \"256Mi\"\n");
        rules.append("```\n");

        return rules.toString();
    }

    /**
     * 기본 진단 규칙
     */
    private String getDefaultRules() {
        return "## General Diagnosis Guidelines\n" +
               "Analyze the provided Events and Logs carefully.\n\n" +
               "Key analysis points:\n" +
               "1. Event types: Warning events indicate problems\n" +
               "2. Event reasons: Match with known issue patterns\n" +
               "3. Container exit codes: 0=success, 1=error, 137=OOM, 143=SIGTERM\n" +
               "4. Timestamps: Correlate events with log entries\n\n" +
               "DO NOT:\n" +
               "- Guess causes not supported by Events/Logs\n" +
               "- Mention resource shortage without 'Insufficient' in Events\n" +
               "- Provide generic solutions not specific to the actual error\n\n" +
               "MUST:\n" +
               "- Base diagnosis ONLY on provided data\n" +
               "- Provide specific, actionable solutions\n" +
               "- Include verification commands\n";
    }

    /**
     * 리소스 타입에 맞는 파일명 반환
     */
    private String getResourceFileName(String resourceKind) {
        switch (resourceKind) {
            case "Pod":
                return "pod.yaml";
            case "Deployment":
                return "deployment.yaml";
            case "StatefulSet":
                return "statefulset.yaml";
            case "DaemonSet":
                return "daemonset.yaml";
            case "Node":
                return "node.yaml";
            default:
                return resourceKind.toLowerCase() + ".yaml";
        }
    }

    /**
     * AI 실패 시 기본 해결 방법
     */
    private List<String> getFallbackSolutions(FaultInfo fault) {
        List<String> solutions = new ArrayList<>();

        switch (fault.getFaultType()) {
            case IMAGE_PULL_BACK_OFF:
                solutions.add("1. 이미지 이름과 태그를 확인하세요: kubectl describe pod " + fault.getResourceName());
                solutions.add("2. 레지스트리 접근 권한을 확인하세요");
                solutions.add("3. imagePullSecrets이 올바르게 설정되어 있는지 확인하세요");
                break;
            case CRASH_LOOP_BACK_OFF:
                solutions.add("1. 로그를 확인하세요: kubectl logs " + fault.getResourceName());
                solutions.add("2. 애플리케이션 시작 스크립트와 설정을 확인하세요");
                solutions.add("3. 필요한 환경 변수가 모두 설정되어 있는지 확인하세요");
                break;
            case OOM_KILLED:
                solutions.add("1. 메모리 limits을 증가시키세요");
                solutions.add("2. 애플리케이션의 메모리 사용량을 최적화하세요");
                solutions.add("3. 메모리 leak이 있는지 확인하세요");
                break;
            case PENDING:
                String pendingDesc = fault.getDescription() != null ? fault.getDescription().toLowerCase() : "";
                if (pendingDesc.contains("pvc") || pendingDesc.contains("volume") || pendingDesc.contains("unbound")) {
                    solutions.add("1. StorageClass 확인: kubectl get storageclass");
                    solutions.add("2. PVC 상태 확인: kubectl get pvc -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                    solutions.add("3. CSI 드라이버/Provisioner 확인: kubectl get pods -n kube-system | grep -E 'csi|provisioner'");
                    solutions.add("4. 동적 프로비저닝이 없으면 수동으로 PV 생성 필요");
                } else {
                    solutions.add("1. 노드 리소스 확인: kubectl describe nodes | grep -A5 'Allocated resources'");
                    solutions.add("2. 노드 Taint 확인: kubectl describe nodes | grep -A3 Taints");
                    solutions.add("3. Pod nodeSelector/affinity 확인: kubectl describe pod " + fault.getResourceName());
                }
                break;
            default:
                solutions.add("1. kubectl describe로 상세 정보를 확인하세요");
                solutions.add("2. kubectl logs로 로그를 확인하세요");
                solutions.add("3. kubectl get events로 관련 이벤트를 확인하세요");
        }

        return solutions;
    }
}
