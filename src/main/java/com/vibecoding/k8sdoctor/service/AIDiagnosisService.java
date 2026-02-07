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

        // 로그와 이벤트 수집 (Pod 또는 Job인 경우)
        String logs = "";
        List<io.fabric8.kubernetes.api.model.Event> events = new ArrayList<>();

        if ("Pod".equals(fault.getResourceKind()) && fault.getNamespace() != null) {
            try {
                String containerName = (String) fault.getContext().get("containerName");
                logs = k8sService.getPodLogs(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName(),
                    containerName,
                    50
                );
                events = k8sService.getPodEvents(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName()
                );
            } catch (Exception e) {
                log.warn("Failed to fetch logs/events for pod {}: {}", fault.getResourceName(), e.getMessage());
            }
        } else if ("Job".equals(fault.getResourceKind()) && fault.getNamespace() != null) {
            try {
                logs = k8sService.getJobLogs(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName()
                );
                events = k8sService.getJobEvents(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName()
                );
            } catch (Exception e) {
                log.warn("Failed to fetch logs/events for job {}: {}", fault.getResourceName(), e.getMessage());
            }
        } else if ("CronJob".equals(fault.getResourceKind()) && fault.getNamespace() != null) {
            try {
                events = k8sService.getCronJobEvents(
                    getClusterIdFromContext(fault),
                    fault.getNamespace(),
                    fault.getResourceName()
                );
            } catch (Exception e) {
                log.warn("Failed to fetch events for cronjob {}: {}", fault.getResourceName(), e.getMessage());
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
            case CREATE_CONTAINER_CONFIG_ERROR:
            case CREATE_CONTAINER_ERROR:
            case TERMINATING_STUCK:
            case STARTUP_PROBE_FAILED:
            case EVICTED:
            case VOLUME_MOUNT_ERROR:
            case NODE_NOT_READY:
            case NODE_PRESSURE:
            case PVC_ERROR:
            case JOB_FAILED:
            case CRONJOB_FAILED:
                // 명확한 에러는 낮은 temperature (더 결정적인 응답)
                return 0.3;
            case READINESS_PROBE_FAILED:
            case LIVENESS_PROBE_FAILED:
            case NETWORK_ERROR:
            case UNKNOWN:
            default:
                // 분석이 필요한 경우는 약간 높은 temperature
                return 0.7;
        }
    }

    /**
     * 진단 프롬프트 생성 - 토큰 최적화 (핵심 정보만)
     */
    private String buildDiagnosisPrompt(FaultInfo fault, List<FaultInfo> relatedFaults, String logs, List<io.fabric8.kubernetes.api.model.Event> events) {
        StringBuilder prompt = new StringBuilder();

        // Owner 정보 추출
        String ownerKind = fault.getContext() != null ?
                (String) fault.getContext().getOrDefault("ownerKind", "Pod") : "Pod";
        String ownerName = fault.getContext() != null ?
                (String) fault.getContext().getOrDefault("ownerName", fault.getResourceName()) : fault.getResourceName();

        // 핵심 정보 (장애유형, owner, namespace, summary)
        prompt.append(String.format("FaultType: %s\n", fault.getFaultType().getCode()));
        prompt.append(String.format("Owner: %s/%s\n", ownerKind, ownerName));
        if (fault.getNamespace() != null) {
            prompt.append(String.format("Namespace: %s\n", fault.getNamespace()));
        }
        // Summary는 이미지명, 에러메시지 등 핵심 정보 포함
        if (fault.getSummary() != null) {
            prompt.append(String.format("Summary: %s\n", fault.getSummary()));
        }

        // FaultType별 필수 컨텍스트 정보 추가
        if (fault.getContext() != null) {
            Map<String, Object> ctx = fault.getContext();

            // 공통: issueCategory
            if (ctx.get("issueCategory") != null) {
                prompt.append(String.format("Category: %s\n", ctx.get("issueCategory")));
            }

            // Pending: 스케줄링 메시지
            if (ctx.get("schedulingMessage") != null && !((String)ctx.get("schedulingMessage")).isEmpty()) {
                prompt.append(String.format("SchedulingMsg: %s\n", ctx.get("schedulingMessage")));
            }

            // CrashLoopBackOff/Probe: containerName, restartCount, exitCode
            if (ctx.get("containerName") != null) {
                prompt.append(String.format("Container: %s\n", ctx.get("containerName")));
            }
            if (ctx.get("restartCount") != null) {
                prompt.append(String.format("Restarts: %s\n", ctx.get("restartCount")));
            }
            if (ctx.get("exitCode") != null) {
                prompt.append(String.format("ExitCode: %s\n", ctx.get("exitCode")));
            }
            if (ctx.get("terminationReason") != null) {
                prompt.append(String.format("TermReason: %s\n", ctx.get("terminationReason")));
            }
            if (ctx.get("hasLivenessProbe") != null) {
                prompt.append("HasLivenessProbe: true\n");
            }
            if (ctx.get("hasStartupProbe") != null) {
                prompt.append("HasStartupProbe: true\n");
            }

            // ImagePullBackOff: image, errorCategory, errorMessage
            if (ctx.get("image") != null) {
                prompt.append(String.format("Image: %s\n", ctx.get("image")));
            }
            if (ctx.get("errorCategory") != null) {
                prompt.append(String.format("ErrorCategory: %s\n", ctx.get("errorCategory")));
            }
            if (ctx.get("errorMessage") != null) {
                prompt.append(String.format("ErrorMsg: %s\n", ctx.get("errorMessage")));
            }

            // StartupProbe: probe 설정값
            if (ctx.get("failureThreshold") != null) {
                prompt.append(String.format("FailureThreshold: %s\n", ctx.get("failureThreshold")));
            }
            if (ctx.get("periodSeconds") != null) {
                prompt.append(String.format("PeriodSeconds: %s\n", ctx.get("periodSeconds")));
            }

            // PVC/Volume: pvcName, storageClass
            if (ctx.get("pvcName") != null) {
                prompt.append(String.format("PVC: %s\n", ctx.get("pvcName")));
            }
            if (ctx.get("storageClassName") != null) {
                prompt.append(String.format("StorageClass: %s\n", ctx.get("storageClassName")));
            }

            // Node: nodeName
            if (ctx.get("nodeName") != null) {
                prompt.append(String.format("Node: %s\n", ctx.get("nodeName")));
            }

            // OOMKilled: 현재 메모리 설정값
            if (ctx.get("memoryLimit") != null) {
                prompt.append(String.format("MemoryLimit: %s\n", ctx.get("memoryLimit")));
            }
            if (ctx.get("memoryRequest") != null) {
                prompt.append(String.format("MemoryRequest: %s\n", ctx.get("memoryRequest")));
            }

            // Evicted: evictionMessage (Detector가 evictionMessage로 저장)
            if (ctx.get("evictionMessage") != null) {
                prompt.append(String.format("EvictionMsg: %s\n", ctx.get("evictionMessage")));
            }

            // TerminatingStuck: finalizers, stuckMinutes
            if (ctx.get("finalizers") != null) {
                prompt.append(String.format("Finalizers: %s\n", ctx.get("finalizers")));
            }
            if (ctx.get("stuckMinutes") != null) {
                prompt.append(String.format("StuckMinutes: %s\n", ctx.get("stuckMinutes")));
            }

            // Job: failedCount, backoffLimit, failureReason, failureMessage, restartPolicy
            if (ctx.get("failedCount") != null) {
                prompt.append(String.format("FailedCount: %s\n", ctx.get("failedCount")));
            }
            if (ctx.get("succeededCount") != null) {
                prompt.append(String.format("SucceededCount: %s\n", ctx.get("succeededCount")));
            }
            if (ctx.get("backoffLimit") != null) {
                prompt.append(String.format("BackoffLimit: %s\n", ctx.get("backoffLimit")));
            }
            if (ctx.get("completions") != null) {
                prompt.append(String.format("Completions: %s\n", ctx.get("completions")));
            }
            if (ctx.get("failureReason") != null) {
                prompt.append(String.format("FailureReason: %s\n", ctx.get("failureReason")));
            }
            if (ctx.get("failureMessage") != null) {
                prompt.append(String.format("FailureMsg: %s\n", ctx.get("failureMessage")));
            }
            if (ctx.get("restartPolicy") != null) {
                prompt.append(String.format("RestartPolicy: %s\n", ctx.get("restartPolicy")));
            }

            // CronJob: schedule, concurrencyPolicy, activeCount
            if (ctx.get("schedule") != null) {
                prompt.append(String.format("Schedule: %s\n", ctx.get("schedule")));
            }
            if (ctx.get("concurrencyPolicy") != null) {
                prompt.append(String.format("ConcurrencyPolicy: %s\n", ctx.get("concurrencyPolicy")));
            }
            if (ctx.get("activeCount") != null) {
                prompt.append(String.format("ActiveCount: %s\n", ctx.get("activeCount")));
            }
            if (ctx.get("lastScheduleTime") != null) {
                prompt.append(String.format("LastSchedule: %s\n", ctx.get("lastScheduleTime")));
            }
            if (ctx.get("lastSuccessfulTime") != null) {
                prompt.append(String.format("LastSuccess: %s\n", ctx.get("lastSuccessfulTime")));
            }
        }

        // CrashLoopBackOff 여부
        boolean isCrashLoop = fault.getFaultType() == com.vibecoding.k8sdoctor.model.FaultType.CRASH_LOOP_BACK_OFF;

        // 로그 (에러 관련만)
        if (logs != null && !logs.isBlank()) {
            String filteredLogs = filterRelevantLogs(logs);
            if (!filteredLogs.isBlank()) {
                prompt.append(isCrashLoop ? "\n## Logs (ROOT CAUSE)\n" : "\n## Logs\n");
                prompt.append("```\n").append(filteredLogs).append("\n```\n");
            } else if (isCrashLoop) {
                prompt.append("\n## Logs\n(없음)\n");
            }
        } else if (isCrashLoop) {
            prompt.append("\n## Logs\n(수집실패)\n");
        }

        // 이벤트 (최대 5개)
        if (events != null && !events.isEmpty()) {
            List<String> dedupedEvents = deduplicateEvents(events);
            if (!dedupedEvents.isEmpty()) {
                prompt.append(isCrashLoop ? "\n## Events (참고)\n" : "\n## Events\n");
                dedupedEvents.stream().limit(5).forEach(prompt::append);
            }
        }

        String finalPrompt = prompt.toString();
        int estimatedTokens = estimateTokenCount(finalPrompt);
        log.info("📊 User prompt: ~{} tokens", estimatedTokens);

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

        // bash/sh 제거 (YAML 블록 외부만)
        text = text.replaceAll("```bash", "```");
        text = text.replaceAll("```sh", "```");

        // kubectl 명령어 처리 (YAML 블록 외부만 - yaml-block 안에 있으면 스킵)
        String[] commands = {"kubectl", "docker", "helm"};

        for (String cmd : commands) {
            // YAML 블록 외부의 명령어만 매칭 (줄 시작이거나 공백 뒤에 오는 경우)
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?<!yaml-code[^>]*>)(?<!#\\s*)\\b(" + cmd + "\\s+[a-zA-Z0-9_\\-\\.\\s]+)"
            );
            java.util.regex.Matcher matcher = pattern.matcher(text);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String command = matcher.group(1).trim();
                // YAML 블록 안이면 스킵
                int pos = matcher.start();
                String before = text.substring(Math.max(0, pos - 100), pos);
                if (before.contains("<code>") && !before.contains("</code>")) {
                    continue; // YAML 블록 안이면 변환하지 않음
                }

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

        return text;
    }

    /**
     * YAML 코드 블록을 HTML로 변환 (변경 라인 하이라이트)
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
            // 라인별 분류하여 하이라이트 적용
            String highlightedYaml = highlightYamlLines(yamlContent);

            String replacement = "\n<div class='yaml-block'><pre class='yaml-code'><code>" +
                               highlightedYaml +
                               "</code></pre><button class='copy-btn' onclick='copyYaml(this)' title='복사'>" +
                               "<i class='bi bi-clipboard'></i></button></div>\n";
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * YAML 라인별 하이라이트 분류
     * - "기존 유지" 포함 라인 → yaml-dim (흐리게)
     * - 인라인 주석 (YAML값 뒤에 # 주석) → yaml-changed (빨간색 하이라이트)
     * - 나머지 → 그대로
     */
    private String highlightYamlLines(String yamlContent) {
        String[] lines = yamlContent.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // HTML entity 이스케이프
            String escapedLine = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                // 빈 라인
                result.append(escapedLine);
            } else if (line.contains("기존 유지")) {
                // "기존 유지" 라인 → 흐리게
                result.append("<span class='yaml-dim'>").append(escapedLine).append("</span>");
            } else if (!trimmed.startsWith("#") && trimmed.contains("#")) {
                // 인라인 주석: YAML값 뒤에 # 주석이 있는 라인 → 변경된 필드 (빨간색)
                result.append("<span class='yaml-changed'>").append(escapedLine).append("</span>");
            } else {
                result.append(escapedLine);
            }

            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
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

        // 내부 프로세스 룰 - 진단/솔루션 분리
        prompt.append("<process>\n");
        prompt.append("1. root cause 1개 확정 (후보 나열 금지)\n");
        prompt.append("2. 확정된 원인에 맞는 YAML 1개만 출력\n");
        prompt.append("3. 확정 불가 => '추가 필요 데이터' 1개만 요청\n");
        prompt.append("</process>\n\n");

        // 기본 제약 조건 - 키워드 룰 형태
        prompt.append("<constraints>\n");
        prompt.append("# Pending (Events기반)\n");
        prompt.append("Insufficient => RESOURCE_SHORTAGE (PVC 언급 금지)\n");
        prompt.append("unbound PVC => PVC_BINDING\n");
        prompt.append("Taints => TAINT_TOLERATION\n\n");
        prompt.append("# CrashLoopBackOff (Logs기반, Events 무시)\n");
        prompt.append("Logs 에러 인용 필수, 없으면 '로그 확인 필요' 명시\n\n");
        prompt.append("# 공통\n");
        prompt.append("bash/sh 금지, 문장 끝 콜론 금지, Pod 직접수정 금지(Owner 수정)\n");
        prompt.append("</constraints>\n\n");

        // 장애 유형별 구체적인 진단 규칙 추가
        prompt.append(getFaultSpecificRules(fault));

        // 솔루션 요구사항 - 토큰 최적화 버전
        prompt.append("<solution_requirements>\n");
        prompt.append("단계: 1-2개만\n");
        prompt.append("YAML: 1개원칙 (1파일에서 해결), 불가피시 2개까지\n");
        prompt.append("YAML은 반드시 ```yaml 코드블록으로 감싸기\n");
        prompt.append("YAML규칙: 변경필드=반드시 새값+' # ← 변경이유' (인라인주석 필수!), 미변경섹션=통째로 생략→'# 나머지 기존 유지' 1줄로 대체\n");
        prompt.append("금지: '기존 유지'를 변경필드에 사용, 미변경필드를 하위내용 포함해서 나열\n");
        prompt.append("금지cmd: apply -f, get pods, describe pod\n");
        prompt.append("허용cmd: rollout, logs --previous, exec, get events, top\n");
        prompt.append("</solution_requirements>\n\n");

        // 플레이스홀더
        prompt.append("<placeholders>\n");
        prompt.append("File: ").append(resourceFileName).append(" | Var: POD_NAME,NAMESPACE (대문자) | <>금지\n");
        prompt.append("</placeholders>\n\n");

        // 출력 형식
        prompt.append("<output_format>\n");
        prompt.append("### 근본 원인 (1-2문장)\n");
        prompt.append("### 해결 방법 (1-2단계, YAML 1개)\n");
        prompt.append("### 재발 방지 (2-3개)\n");
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

        // issueCategory도 규칙 매칭에 포함 (Detector가 분류한 카테고리)
        String issueCategory = "";
        if (fault.getContext() != null && fault.getContext().get("issueCategory") != null) {
            issueCategory = ((String) fault.getContext().get("issueCategory")).toLowerCase();
        }

        switch (fault.getFaultType()) {
            case PENDING:
                rules.append(getPendingRules(description + " " + summary + " " + symptoms + " " + issueCategory, ownerKind));
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
            case CREATE_CONTAINER_CONFIG_ERROR:
                rules.append(getCreateContainerConfigErrorRules());
                break;
            case CREATE_CONTAINER_ERROR:
                rules.append(getCreateContainerErrorRules());
                break;
            case LIVENESS_PROBE_FAILED:
            case READINESS_PROBE_FAILED:
                rules.append(getProbeFailedRules(fault.getFaultType()));
                break;
            case STARTUP_PROBE_FAILED:
                rules.append(getStartupProbeFailedRules());
                break;
            case CONFIG_ERROR:
                // CONFIG_ERROR는 CREATE_CONTAINER_CONFIG_ERROR와 유사 - 동일한 규칙 사용
                rules.append(getCreateContainerConfigErrorRules());
                break;
            case PVC_ERROR:
                rules.append(getPVCErrorRules(ownerKind));
                break;
            case NETWORK_ERROR:
                rules.append(getNetworkErrorRules());
                break;
            case VOLUME_MOUNT_ERROR:
                rules.append(getVolumeMountErrorRules());
                break;
            case NODE_NOT_READY:
            case NODE_PRESSURE:
                rules.append(getNodeIssueRules(fault.getFaultType()));
                break;
            case INSUFFICIENT_RESOURCES:
            case RESOURCE_QUOTA_EXCEEDED:
                rules.append(getResourceIssueRules(fault.getFaultType()));
                break;
            case TERMINATING_STUCK:
                rules.append(getTerminatingStuckRules());
                break;
            case EVICTED:
                rules.append(getEvictedRules());
                break;
            case JOB_FAILED:
                rules.append(getJobFailedRules());
                break;
            case CRONJOB_FAILED:
                rules.append(getCronJobFailedRules());
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

            rules.append("## Pending/PVC_BINDING\n");
            rules.append("unbound PVC => storageClassName 미설정 또는 SC 없음\n");
            if ("StatefulSet".equals(ownerKind)) {
                rules.append("StatefulSet => spec.volumeClaimTemplates[].spec.storageClassName 수정 (template과 형제위치! template.spec 아래 아님!)(별도PVC생성 금지!)\n");
            } else if ("DaemonSet".equals(ownerKind)) {
                rules.append("DaemonSet => hostPath/emptyDir 권장, PVC필요시 NFS(RWX)\n");
            } else {
                rules.append("Deployment/Pod => PVC별도생성OK, storageClassName 맞추기\n");
            }
            rules.append("SC없으면 => StorageClass+provisioner 먼저 생성\n");
        }
        // 리소스 부족
        else if (combinedText.contains("insufficient") || combinedText.contains("리소스") ||
                 combinedText.contains("memory") || combinedText.contains("cpu") ||
                 combinedText.contains("resource_shortage")) {
            rules.append("## Pending/RESOURCE_SHORTAGE (PVC/StorageClass 언급 금지!)\n");
            boolean isCpu = combinedText.contains("cpu") || combinedText.contains("resource_shortage_cpu");
            boolean isMemory = combinedText.contains("memory") || combinedText.contains("resource_shortage_memory");

            if (isCpu && !isMemory) {
                rules.append("CPU부족 => requests.cpu를 현재보다 낮은 구체적 값으로 변경 (예: 500m→200m)\n");
            } else if (isMemory) {
                rules.append("Memory부족 => requests.memory를 현재보다 낮은 구체적 값으로 변경 (예: 512Mi→256Mi)\n");
            } else {
                rules.append("리소스부족 => requests.cpu/memory를 현재보다 낮은 구체적 값으로 변경\n");
            }
            if ("Pod".equals(ownerKind)) {
                rules.append("수정위치: Pod.spec.containers[].resources\n");
            } else {
                rules.append("수정위치: " + ownerKind + ".spec.template.spec.containers[].resources\n");
            }
            rules.append("또는: 노드추가/Autoscaler/미사용Pod삭제\n");
        }
        // Taint/Toleration
        else if (combinedText.contains("taint") || combinedText.contains("toleration")) {
            rules.append("## Pending/TAINT\n");
            rules.append("Pod에 toleration 없음 => tolerations 추가\n");
            if ("DaemonSet".equals(ownerKind)) {
                rules.append("DaemonSet => operator:Exists (모든 taint 허용)\n");
            }
        }
        // TopologySpreadConstraints
        else if (combinedText.contains("topologyspreadconstraints") || combinedText.contains("topology spread") ||
                 combinedText.contains("topology_spread")) {
            rules.append("## Pending/TOPOLOGY\n");
            rules.append("maxSkew불만족 => whenUnsatisfiable:ScheduleAnyway 또는 노드추가\n");
        }
        // PodAntiAffinity
        else if (combinedText.contains("anti-affinity") || combinedText.contains("podantiaffinity") ||
                 combinedText.contains("pod_anti_affinity")) {
            rules.append("## Pending/ANTI_AFFINITY\n");
            rules.append("required => preferred로 변경 또는 노드추가\n");
        }
        // NodeSelector/Affinity
        else if (combinedText.contains("nodeselector") || combinedText.contains("affinity") ||
                 combinedText.contains("didn't match") || combinedText.contains("node(s)")) {
            rules.append("## Pending/NODE_SELECTOR\n");
            rules.append("매칭노드없음 => nodeSelector제거 또는 노드라벨추가\n");
        }
        // 일반 Pending
        else {
            rules.append("## Pending/UNKNOWN\n");
            rules.append("Events 메시지 기반으로 정확한 원인 판단, 추측 금지\n");
        }

        return rules.toString();
    }

    /**
     * CrashLoopBackOff 진단 규칙 - 토큰 최적화 버전
     */
    private String getCrashLoopBackOffRules() {
        return "## CrashLoopBackOff (Logs기반, Events무시)\n" +
               "ECONNREFUSED => SERVICE_DOWN (서비스 확인)\n" +
               "UnknownHost => DNS_FAIL (CoreDNS/서비스명)\n" +
               "address in use => PORT_CONFLICT\n" +
               "permission denied/126 => PERMISSION (securityContext)\n" +
               "not found/127 => CMD_NOT_FOUND (command/이미지)\n" +
               "137+TermReason:OOMKilled => OOM (memory limit 증가)\n" +
               "137+HasLivenessProbe:true+TermReason!=OOMKilled => PROBE_KILL (livenessProbe 설정 수정)\n" +
               "137+TermReason:Error => 외부SIGKILL (liveness probe 확인 우선)\n" +
               "panic/Exception => APP_ERROR (스택트레이스)\n" +
               "SSL/certificate => TLS_ERROR\n" +
               "로그없음 => 'kubectl logs --previous' 안내\n\n" +
               "Category:LIVENESS_PROBE_KILLED => livenessProbe 실패가 원인! OOM 아님! probe설정/endpoint 수정\n" +
               "Category:STARTUP_PROBE_KILLED => startupProbe 실패가 원인! OOM 아님! failureThreshold*periodSeconds 늘리기\n" +
               "DB연결실패 우선순위: 1)앱복원력 2)startupProbe 3)readiness+liveness분리 4)initContainer\n" +
               "금지: livenessProbe에 DB체크\n" +
               "DNS: 같은ns='mysql', 다른ns='mysql.ns.svc'\n";
    }

    /**
     * ImagePullBackOff 진단 규칙 - 토큰 최적화 버전
     */
    private String getImagePullBackOffRules() {
        return "## ImagePullBackOff (Events기반 - 각 원인별 다른 해결책!)\n" +
               "404/manifest unknown => IMAGE_NOT_FOUND => 이미지명/태그 오타 확인, 존재여부 확인\n" +
               "no such host => REGISTRY_NOT_FOUND => 레지스트리 URL 오타, 존재하지 않는 레지스트리\n" +
               "401/unauthorized => AUTH_FAIL => imagePullSecrets 필요\n" +
               "403/forbidden => PERMISSION => IAM/레지스트리 권한\n" +
               "x509 => CERT_ERROR => CA 인증서\n" +
               "429/rate limit => RATE_LIMIT => 인증 또는 미러\n" +
               "timeout => NETWORK => egress/방화벽\n\n" +
               "중요: 401/403 아니면 imagePullSecrets 언급 금지!\n" +
               "존재하지 않는 이미지/레지스트리면 이미지명 수정이 해결책\n";
    }

    /**
     * OOMKilled 진단 규칙 - 토큰 최적화
     */
    private String getOOMKilledRules() {
        return "## OOMKilled\n" +
               "exit 137 => OOM\n" +
               "MemoryLimit 값이 있으면 => 현재값 기준으로 구체적 증가량 제시 (예: 100Mi→200Mi)\n" +
               "MemoryLimit 미설정 => limits.memory 추가 권고\n" +
               "원인: limit낮음/leak/heap초과/트래픽급증\n" +
               "Java앱 => -Xmx=limit의 75%\n";
    }

    /**
     * CreateContainerConfigError 진단 규칙 - 토큰 최적화
     */
    private String getCreateContainerConfigErrorRules() {
        return "## CreateContainerConfigError\n" +
               "CM/Secret not found => 동일ns에 생성\n" +
               "key not found => 키 확인 (describe)\n" +
               "Fix: optional:true 또는 리소스 생성\n";
    }

    /**
     * CreateContainerError 진단 규칙 - 토큰 최적화
     */
    private String getCreateContainerErrorRules() {
        return "## CreateContainerError\n" +
               "not found => CMD_NOT_FOUND (command 확인)\n" +
               "permission denied => PERMISSION (securityContext)\n" +
               "entrypoint => ENTRYPOINT_ERR (command/args 오버라이드)\n" +
               "mount/volume => MOUNT_ERR (mountPath 확인)\n" +
               "OCI runtime => RUNTIME_ERR (이미지 호환성)\n";
    }

    /**
     * Startup Probe 실패 진단 규칙 - 토큰 최적화
     */
    private String getStartupProbeFailedRules() {
        return "## StartupProbe Failed\n" +
               "원인: 시작시간부족/앱크래시/잘못된endpoint\n" +
               "Fix: failureThreshold*periodSeconds=총허용시간 (예:30*10=5분)\n";
    }

    /**
     * Probe 실패 진단 규칙 - 토큰 최적화
     */
    private String getProbeFailedRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        String probeType = faultType == com.vibecoding.k8sdoctor.model.FaultType.LIVENESS_PROBE_FAILED ? "Liveness" : "Readiness";
        return "## " + probeType + "ProbeFailed\n" +
               "path/port오류 => endpoint확인\n" +
               "timeout => timeoutSeconds증가\n" +
               "slowStart => startupProbe사용\n";
    }

    /**
     * ConfigMap/Secret 에러 진단 규칙 - 토큰 최적화
     */
    private String getConfigErrorRules() {
        return "## ConfigError => 동일ns생성/optional:true\n";
    }

    /**
     * PVC 에러 진단 규칙 - 토큰 최적화
     */
    private String getPVCErrorRules(String ownerKind) {
        if ("StatefulSet".equals(ownerKind)) {
            return "## PVCError (StatefulSet)\n" +
                   "spec.volumeClaimTemplates수정 (template과 형제위치!)(별도PVC생성금지)\n" +
                   "NoSC => storageClassName설정\n";
        }
        return "## PVCError\n" +
               "NoSC => get sc, storageClassName설정\n" +
               "Static => hostPath/local PV생성\n";
    }

    /**
     * 볼륨 마운트 오류 진단 규칙 - 토큰 최적화
     */
    private String getVolumeMountErrorRules() {
        return "## VolumeMountError\n" +
               "MOUNT_FAILED => PVC 미바인딩 (get pv,pvc)\n" +
               "PERMISSION => fsGroup 설정\n" +
               "READONLY => readOnly:false\n" +
               "CSI_ERR => CSI Pod 로그\n" +
               "SUBPATH => 경로 존재 확인\n";
    }

    /**
     * 네트워크 에러 진단 규칙 - 토큰 최적화
     */
    private String getNetworkErrorRules() {
        return "## NetworkError\n" +
               "DNS_FAIL => CoreDNS 확인\n" +
               "NETPOL_BLOCK => get networkpolicy -A\n" +
               "SVC_NOT_FOUND => get svc, DNS=SVC.NS.svc\n" +
               "CNI_ERR => calico/flannel 상태\n" +
               "CIDR_EXHAUST => Pod 정리\n";
    }

    /**
     * Node 문제 진단 규칙 - 토큰 최적화
     */
    private String getNodeIssueRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        if (faultType == com.vibecoding.k8sdoctor.model.FaultType.NODE_NOT_READY) {
            return "## NodeNotReady\n" +
                   "KUBELET_DOWN => systemctl restart kubelet\n" +
                   "RUNTIME_FAIL => docker/containerd 확인\n" +
                   "PRESSURE => disk/memory/pid 확인\n";
        } else {
            return "## NodePressure\n" +
                   "Disk>85%/Memory/PID => drain+prune+노드추가\n";
        }
    }

    /**
     * 리소스 문제 진단 규칙 - 토큰 최적화
     */
    private String getResourceIssueRules(com.vibecoding.k8sdoctor.model.FaultType faultType) {
        if (faultType == com.vibecoding.k8sdoctor.model.FaultType.RESOURCE_QUOTA_EXCEEDED) {
            return "## QuotaExceeded => requests줄이기/Pod삭제/quota증가요청\n";
        } else {
            return "## InsufficientResources => requests줄이기/노드추가/PriorityClass\n";
        }
    }

    /**
     * Evicted Pod 진단 규칙 - 토큰 최적화
     */
    private String getEvictedRules() {
        return "## Evicted\n" +
               "EPHEMERAL => ephemeral-storage limits\n" +
               "DISK => prune\n" +
               "MEMORY => requests줄이기/노드추가\n" +
               "Controller(Deployment등) 관리Pod => 자동재생성됨, 축출Pod는 delete로 정리\n" +
               "standalone Pod => 자동재생성X, delete후 재생성 필요\n";
    }

    /**
     * Terminating 상태 멈춤 진단 규칙 - 토큰 최적화
     */
    private String getTerminatingStuckRules() {
        return "## TerminatingStuck\n" +
               "FINALIZER => patch finalizers:null\n" +
               "VOLUME => get volumeattachments\n" +
               "CNI => CNI 로그/캐시정리\n" +
               "SIGTERM무시 => terminationGracePeriod줄이기\n" +
               "강제: --force --grace-period=0 (주의:데이터손실위험)\n";
    }

    /**
     * Job 실패 진단 규칙
     */
    private String getJobFailedRules() {
        return "## JobFailed (Logs가 근본원인! 반드시 로그 에러 인용)\n" +
               "원칙: 사용자 의도 추측 금지! 항상 팩트 기반 원인+해결책 제시\n" +
               "로그에서 실패원인 특정 => 구체적 수정방법 제시\n" +
               "exit 1+로그에 구체적에러없음 => 'command가 exit 1로 종료, 스크립트 로직 확인 필요' + command/args 수정 YAML\n" +
               "일시적오류(DB연결실패/외부서비스timeout/네트워크) => backoffLimit증가 또는 initContainer로 의존성대기\n" +
               "영구적오류(NPE/syntax error/잘못된설정) => command/args/이미지/코드 수정. backoffLimit증가는 무의미!\n" +
               "DEADLINE_EXCEEDED => activeDeadlineSeconds 초과, deadline 증가 또는 실행최적화\n" +
               "exit 137 => OOM, resources.limits.memory 증가\n" +
               "exit 127 => command not found, image/command 확인\n" +
               "금지: '테스트Job'/'의도된실패'/'조치불필요' 판단, 영구적오류에 backoffLimit증가, 로그 미확인 시 추측\n" +
               "수정위치: Job.spec.template.spec (Pod직접수정 금지)\n" +
               "CronJob소유 Job => CronJob.spec.jobTemplate.spec.template.spec 수정\n";
    }

    /**
     * CronJob 실패 진단 규칙
     */
    private String getCronJobFailedRules() {
        return "## CronJobFailed\n" +
               "SUSPENDED => spec.suspend:false로 변경\n" +
               "TOO_MANY_ACTIVE => 이전Job미완료, concurrencyPolicy/activeDeadlineSeconds 확인\n" +
               "SCHEDULE_STALE => schedule 문법확인, kube-controller-manager 상태확인\n" +
               "수정위치: CronJob.spec\n";
    }

    /**
     * 기본 진단 규칙 - 토큰 최적화
     */
    private String getDefaultRules() {
        return "## Default\n" +
               "exit: 0=OK,1=ERR,137=OOM,143=SIGTERM\n" +
               "금지: 추측, Insufficient없이 리소스부족언급\n";
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
            case "Job":
                return "job.yaml";
            case "CronJob":
                return "cronjob.yaml";
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
            case CREATE_CONTAINER_CONFIG_ERROR:
                solutions.add("1. ConfigMap/Secret 존재 확인: kubectl get configmap,secret -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                solutions.add("2. 참조된 키 확인: kubectl describe configmap CM_NAME -n NAMESPACE");
                solutions.add("3. 동일 네임스페이스에 리소스 생성 또는 optional: true 설정");
                break;
            case CREATE_CONTAINER_ERROR:
                solutions.add("1. Pod 상세 정보 확인: kubectl describe pod " + fault.getResourceName());
                solutions.add("2. 이미지에 command/entrypoint가 있는지 확인");
                solutions.add("3. securityContext 설정 확인 (runAsUser, fsGroup 등)");
                solutions.add("4. volumeMounts 경로가 올바른지 확인");
                break;
            case TERMINATING_STUCK:
                solutions.add("1. Finalizer 확인: kubectl get pod " + fault.getResourceName() + " -o yaml | grep finalizers");
                solutions.add("2. VolumeAttachment 확인: kubectl get volumeattachments");
                solutions.add("3. CNI 로그 확인: kubectl logs -n kube-system -l k8s-app=calico-node");
                solutions.add("4. 강제 삭제 (주의): kubectl delete pod " + fault.getResourceName() + " --force --grace-period=0");
                break;
            case VOLUME_MOUNT_ERROR:
                solutions.add("1. PV/PVC 상태 확인: kubectl get pv,pvc -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                solutions.add("2. securityContext 확인: kubectl get pod " + fault.getResourceName() + " -o yaml | grep -A10 securityContext");
                solutions.add("3. fsGroup 설정 추가 고려 (볼륨 권한 문제 시)");
                solutions.add("4. CSI 드라이버 로그 확인: kubectl logs -n kube-system -l app=csi-driver");
                break;
            case EVICTED:
                solutions.add("1. 축출 원인 확인: kubectl describe pod " + fault.getResourceName());
                solutions.add("2. 노드 상태 확인: kubectl describe nodes | grep -A5 Conditions");
                solutions.add("3. 축출된 Pod 삭제: kubectl delete pod " + fault.getResourceName());
                solutions.add("4. ephemeral-storage limits 설정 고려");
                break;
            case JOB_FAILED:
                solutions.add("1. Job Pod 로그 확인: kubectl logs job/" + fault.getResourceName() + " -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                solutions.add("2. Job 이벤트 확인: kubectl describe job " + fault.getResourceName() + " -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                solutions.add("3. backoffLimit 확인 및 조정");
                solutions.add("4. 애플리케이션 코드/설정 오류 수정 후 Job 재생성");
                break;
            case CRONJOB_FAILED:
                solutions.add("1. CronJob 상태 확인: kubectl describe cronjob " + fault.getResourceName() + " -n " + (fault.getNamespace() != null ? fault.getNamespace() : "NAMESPACE"));
                solutions.add("2. 최근 Job 목록 확인: kubectl get jobs -l job-name=" + fault.getResourceName());
                solutions.add("3. schedule 문법 확인");
                solutions.add("4. suspend=false 확인");
                break;
            default:
                solutions.add("1. kubectl describe로 상세 정보를 확인하세요");
                solutions.add("2. kubectl logs로 로그를 확인하세요");
                solutions.add("3. kubectl get events로 관련 이벤트를 확인하세요");
        }

        return solutions;
    }
}
