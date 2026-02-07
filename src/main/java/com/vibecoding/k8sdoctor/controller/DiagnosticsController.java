package com.vibecoding.k8sdoctor.controller;

import com.vibecoding.k8sdoctor.model.ClusterInfo;
import com.vibecoding.k8sdoctor.model.DiagnosisResult;
import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.Severity;
import com.vibecoding.k8sdoctor.service.ClusterService;
import com.vibecoding.k8sdoctor.service.DiagnosticsService;
import com.vibecoding.k8sdoctor.service.FaultClassificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 진단 컨트롤러
 */
@Controller
@RequestMapping("/clusters/{clusterId}/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final ClusterService clusterService;
    private final DiagnosticsService diagnosticsService;
    private final FaultClassificationService faultService;

    /**
     * 클러스터 전체 진단 페이지 (트리 구조, On-demand AI 진단)
     */
    @GetMapping
    public String diagnoseCluster(
            @PathVariable String clusterId,
            Model model
    ) {
        log.info("Loading cluster diagnostics page: {}", clusterId);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
                .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));

        // 빠른 장애 탐지만 수행 (AI 분석 제외)
        List<FaultInfo> faults = diagnosticsService.scanCluster(clusterId);

        // 리소스별로 가장 심각한 장애만 선택 (중복 제거) - Pod, Job, CronJob 포함
        Map<String, FaultInfo> uniqueFaults = faults.stream()
                .filter(f -> "Pod".equals(f.getResourceKind()) ||
                             "Job".equals(f.getResourceKind()) ||
                             "CronJob".equals(f.getResourceKind()))
                .collect(Collectors.toMap(
                        f -> f.getNamespace() + "/" + f.getResourceKind() + "/" + f.getResourceName(),
                        f -> f,
                        (f1, f2) -> f1.getSeverity().ordinal() < f2.getSeverity().ordinal() ? f1 : f2
                ));

        // Namespace별로 그룹핑
        Map<String, List<PodInfo>> podsByNamespace = uniqueFaults.values().stream()
                .collect(Collectors.groupingBy(
                        f -> f.getNamespace() != null ? f.getNamespace() : "default",
                        Collectors.mapping(f -> new PodInfo(
                                f.getResourceName(),
                                f.getNamespace(),
                                f.getSeverity(),
                                f.getFaultType().getDescription(),
                                f.getSummary()
                        ), Collectors.toList())
                ));

        // 통계 (중복 제거된 장애만 계산)
        FaultClassificationService.FaultStatistics stats = faultService.getStatistics(new ArrayList<>(uniqueFaults.values()));

        model.addAttribute("cluster", cluster);
        model.addAttribute("podsByNamespace", podsByNamespace);
        model.addAttribute("statistics", stats);
        model.addAttribute("title", "Cluster Diagnostics - " + cluster.getName());

        return "diagnostics/cluster";
    }

    /**
     * Pod 정보 DTO
     */
    public static class PodInfo {
        private String name;
        private String namespace;
        private Severity severity;
        private String faultType;
        private String summary;

        public PodInfo(String name, String namespace, Severity severity, String faultType, String summary) {
            this.name = name;
            this.namespace = namespace;
            this.severity = severity;
            this.faultType = faultType;
            this.summary = summary;
        }

        public String getName() { return name; }
        public String getNamespace() { return namespace; }
        public Severity getSeverity() { return severity; }
        public String getFaultType() { return faultType; }
        public String getSummary() { return summary; }
    }

    // 네임스페이스별 진단 페이지 제거 (클러스터 진단 페이지로 통합)
    // @GetMapping("/namespace/{namespace}")
    // public String diagnoseNamespace(...) { ... }

    /**
     * REST API - Pod 진단 (On-demand, AI 분석 포함)
     */
    @GetMapping("/api/pod/{namespace}/{name}")
    @ResponseBody
    public Map<String, Object> diagnosePodApi(
            @PathVariable String clusterId,
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        log.info("API: Diagnosing pod {}/{} in cluster {}", namespace, name, clusterId);

        try {
            // 빠른 장애 탐지
            List<FaultInfo> allFaults = diagnosticsService.scanNamespace(clusterId, namespace);
            List<FaultInfo> podFaults = allFaults.stream()
                    .filter(f -> name.equals(f.getResourceName()) &&
                                 ("Pod".equals(f.getResourceKind()) ||
                                  "Job".equals(f.getResourceKind()) ||
                                  "CronJob".equals(f.getResourceKind())))
                    .collect(Collectors.toList());

            if (podFaults.isEmpty()) {
                return Map.of(
                        "success", true,
                        "hasFaults", false,
                        "message", "No faults detected"
                );
            }

            // AI 진단 수행 (가장 심각한 장애를 주요 장애로 선택)
            FaultInfo primaryFault = podFaults.stream()
                    .min(Comparator.comparing(f -> f.getSeverity().ordinal()))
                    .orElse(podFaults.get(0));
            DiagnosisResult diagnosis = diagnosticsService.diagnoseWithAI(clusterId, namespace, name, primaryFault, podFaults);

            return Map.of(
                    "success", true,
                    "hasFaults", true,
                    "fault", Map.of(
                            "type", primaryFault.getFaultType().getCode(),
                            "severity", primaryFault.getSeverity().toString(),
                            "summary", primaryFault.getSummary()
                    ),
                    "diagnosis", Map.of(
                            "rootCause", diagnosis.getRootCause(),
                            "solutions", diagnosis.getSolutions(),
                            "preventions", diagnosis.getPreventions()
                    )
            );
        } catch (Exception e) {
            log.error("Failed to diagnose pod {}/{}: {}", namespace, name, e.getMessage(), e);
            return Map.of(
                    "success", false,
                    "error", e.getMessage()
            );
        }
    }

    /**
     * REST API - 클러스터 진단
     */
    @GetMapping("/api/scan")
    @ResponseBody
    public DiagnosticsResult scanClusterApi(@PathVariable String clusterId) {
        List<FaultInfo> faults = diagnosticsService.scanCluster(clusterId);
        FaultClassificationService.FaultStatistics stats = faultService.getStatistics(faults);

        return DiagnosticsResult.builder()
                .faults(faults)
                .statistics(stats)
                .build();
    }

    /**
     * REST API - 네임스페이스 진단
     */
    @GetMapping("/api/namespace/{namespace}/scan")
    @ResponseBody
    public DiagnosticsResult scanNamespaceApi(
            @PathVariable String clusterId,
            @PathVariable String namespace
    ) {
        List<FaultInfo> faults = diagnosticsService.scanNamespace(clusterId, namespace);
        FaultClassificationService.FaultStatistics stats = faultService.getStatistics(faults);

        return DiagnosticsResult.builder()
                .faults(faults)
                .statistics(stats)
                .build();
    }

    public static class DiagnosticsResult {
        private List<FaultInfo> faults;
        private FaultClassificationService.FaultStatistics statistics;

        public DiagnosticsResult() {
        }

        public DiagnosticsResult(List<FaultInfo> faults, FaultClassificationService.FaultStatistics statistics) {
            this.faults = faults;
            this.statistics = statistics;
        }

        public static DiagnosticsResultBuilder builder() {
            return new DiagnosticsResultBuilder();
        }

        public List<FaultInfo> getFaults() {
            return faults;
        }

        public void setFaults(List<FaultInfo> faults) {
            this.faults = faults;
        }

        public FaultClassificationService.FaultStatistics getStatistics() {
            return statistics;
        }

        public void setStatistics(FaultClassificationService.FaultStatistics statistics) {
            this.statistics = statistics;
        }

        public static class DiagnosticsResultBuilder {
            private List<FaultInfo> faults;
            private FaultClassificationService.FaultStatistics statistics;

            public DiagnosticsResultBuilder faults(List<FaultInfo> faults) {
                this.faults = faults;
                return this;
            }

            public DiagnosticsResultBuilder statistics(FaultClassificationService.FaultStatistics statistics) {
                this.statistics = statistics;
                return this;
            }

            public DiagnosticsResult build() {
                return new DiagnosticsResult(faults, statistics);
            }
        }
    }
}
