package com.vibecoding.k8sdoctor.service;

import com.vibecoding.k8sdoctor.model.DiagnosisResult;
import com.vibecoding.k8sdoctor.model.FaultInfo;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 진단 서비스 - 리소스 스캔 및 장애 탐지
 */
@Service
@RequiredArgsConstructor
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    private final MultiClusterK8sService k8sService;
    private final FaultClassificationService faultService;
    private final AIDiagnosisService aiDiagnosisService;

    /**
     * 특정 네임스페이스의 모든 Pod 스캔
     */
    public List<FaultInfo> scanPodsInNamespace(String clusterId, String namespace) {
        log.info("Scanning pods in namespace {} of cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<Pod> pods = k8sService.listPodsInNamespace(clusterId, namespace);

        for (Pod pod : pods) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "Pod", pod);
            // ClusterId를 context에 추가
            faults.forEach(f -> {
                Map<String, Object> context = f.getContext();
                if (context == null || context.isEmpty()) {
                    context = new java.util.HashMap<>();
                } else {
                    // 기존 context가 불변 맵일 수 있으므로 새로운 HashMap으로 복사
                    context = new java.util.HashMap<>(context);
                }
                context.put("clusterId", clusterId);
                f.setContext(context);
            });
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} pods", allFaults.size(), pods.size());
        return allFaults;
    }

    /**
     * 클러스터의 모든 Pod 스캔
     */
    public List<FaultInfo> scanAllPods(String clusterId) {
        log.info("Scanning all pods in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<Pod> pods = k8sService.listAllPods(clusterId);

        for (Pod pod : pods) {
            String namespace = pod.getMetadata().getNamespace();
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "Pod", pod);
            // ClusterId를 context에 추가
            faults.forEach(f -> {
                Map<String, Object> context = f.getContext();
                if (context == null || context.isEmpty()) {
                    context = new java.util.HashMap<>();
                } else {
                    // 기존 context가 불변 맵일 수 있으므로 새로운 HashMap으로 복사
                    context = new java.util.HashMap<>(context);
                }
                context.put("clusterId", clusterId);
                f.setContext(context);
            });
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} pods", allFaults.size(), pods.size());
        return allFaults;
    }

    /**
     * Deployment 스캔
     */
    public List<FaultInfo> scanDeploymentsInNamespace(String clusterId, String namespace) {
        log.info("Scanning deployments in namespace {} of cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<Deployment> deployments = k8sService.listDeploymentsInNamespace(clusterId, namespace);

        for (Deployment deployment : deployments) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "Deployment", deployment);
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} deployments", allFaults.size(), deployments.size());
        return allFaults;
    }

    /**
     * 전체 Deployment 스캔
     */
    public List<FaultInfo> scanAllDeployments(String clusterId) {
        log.info("Scanning all deployments in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.Namespace> namespaces = k8sService.listNamespaces(clusterId);

        for (io.fabric8.kubernetes.api.model.Namespace ns : namespaces) {
            allFaults.addAll(scanDeploymentsInNamespace(clusterId, ns.getMetadata().getName()));
        }

        log.info("Found {} total deployment faults", allFaults.size());
        return allFaults;
    }

    /**
     * DaemonSet 스캔 (네임스페이스별)
     */
    public List<FaultInfo> scanDaemonSetsInNamespace(String clusterId, String namespace) {
        log.info("Scanning daemonsets in namespace {} of cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.apps.DaemonSet> daemonSets =
            k8sService.listDaemonSetsInNamespace(clusterId, namespace);

        for (io.fabric8.kubernetes.api.model.apps.DaemonSet daemonSet : daemonSets) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "DaemonSet", daemonSet);
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} daemonsets", allFaults.size(), daemonSets.size());
        return allFaults;
    }

    /**
     * 전체 DaemonSet 스캔
     */
    public List<FaultInfo> scanAllDaemonSets(String clusterId) {
        log.info("Scanning all daemonsets in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.Namespace> namespaces = k8sService.listNamespaces(clusterId);

        for (io.fabric8.kubernetes.api.model.Namespace ns : namespaces) {
            allFaults.addAll(scanDaemonSetsInNamespace(clusterId, ns.getMetadata().getName()));
        }

        log.info("Found {} total daemonset faults", allFaults.size());
        return allFaults;
    }

    /**
     * StatefulSet 스캔 (네임스페이스별)
     */
    public List<FaultInfo> scanStatefulSetsInNamespace(String clusterId, String namespace) {
        log.info("Scanning statefulsets in namespace {} of cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.apps.StatefulSet> statefulSets =
            k8sService.listStatefulSetsInNamespace(clusterId, namespace);

        for (io.fabric8.kubernetes.api.model.apps.StatefulSet statefulSet : statefulSets) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "StatefulSet", statefulSet);
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} statefulsets", allFaults.size(), statefulSets.size());
        return allFaults;
    }

    /**
     * 전체 StatefulSet 스캔
     */
    public List<FaultInfo> scanAllStatefulSets(String clusterId) {
        log.info("Scanning all statefulsets in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.Namespace> namespaces = k8sService.listNamespaces(clusterId);

        for (io.fabric8.kubernetes.api.model.Namespace ns : namespaces) {
            allFaults.addAll(scanStatefulSetsInNamespace(clusterId, ns.getMetadata().getName()));
        }

        log.info("Found {} total statefulset faults", allFaults.size());
        return allFaults;
    }

    /**
     * ReplicaSet 스캔 (네임스페이스별)
     */
    public List<FaultInfo> scanReplicaSetsInNamespace(String clusterId, String namespace) {
        log.info("Scanning replicasets in namespace {} of cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.apps.ReplicaSet> replicaSets =
            k8sService.listReplicaSetsInNamespace(clusterId, namespace);

        for (io.fabric8.kubernetes.api.model.apps.ReplicaSet replicaSet : replicaSets) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, namespace, "ReplicaSet", replicaSet);
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} replicasets", allFaults.size(), replicaSets.size());
        return allFaults;
    }

    /**
     * 전체 ReplicaSet 스캔
     */
    public List<FaultInfo> scanAllReplicaSets(String clusterId) {
        log.info("Scanning all replicasets in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<io.fabric8.kubernetes.api.model.Namespace> namespaces = k8sService.listNamespaces(clusterId);

        for (io.fabric8.kubernetes.api.model.Namespace ns : namespaces) {
            allFaults.addAll(scanReplicaSetsInNamespace(clusterId, ns.getMetadata().getName()));
        }

        log.info("Found {} total replicaset faults", allFaults.size());
        return allFaults;
    }

    /**
     * 노드 스캔
     */
    public List<FaultInfo> scanNodes(String clusterId) {
        log.info("Scanning nodes in cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();
        List<Node> nodes = k8sService.listNodes(clusterId);

        for (Node node : nodes) {
            List<FaultInfo> faults = faultService.detectFaults(clusterId, null, "Node", node);
            allFaults.addAll(faults);
        }

        log.info("Found {} faults in {} nodes", allFaults.size(), nodes.size());
        return allFaults;
    }

    /**
     * 클러스터 전체 스캔 (모든 워크로드 + Node)
     */
    public List<FaultInfo> scanCluster(String clusterId) {
        log.info("Starting full cluster scan for cluster {}", clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();

        // Pod 스캔
        allFaults.addAll(scanAllPods(clusterId));

        // 워크로드 스캔
        allFaults.addAll(scanAllDeployments(clusterId));
        allFaults.addAll(scanAllDaemonSets(clusterId));
        allFaults.addAll(scanAllStatefulSets(clusterId));
        allFaults.addAll(scanAllReplicaSets(clusterId));

        // Node 스캔
        allFaults.addAll(scanNodes(clusterId));

        log.info("Full cluster scan completed. Found {} total faults", allFaults.size());
        return allFaults;
    }

    /**
     * AI 진단과 함께 클러스터 스캔
     */
    public List<DiagnosisResult> scanClusterWithAI(String clusterId) {
        log.info("Starting AI-powered cluster scan for cluster {}", clusterId);

        List<FaultInfo> allFaults = scanCluster(clusterId);

        // 중복 제거: 같은 리소스의 장애들을 그룹핑
        Map<String, List<FaultInfo>> groupedFaults = groupFaultsByResource(allFaults);

        // 각 그룹의 주요 장애에 대해 AI 진단 수행
        List<DiagnosisResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FaultInfo>> entry : groupedFaults.entrySet()) {
            List<FaultInfo> resourceFaults = entry.getValue();

            // 가장 심각한 장애를 주요 장애로 선택
            FaultInfo primaryFault = resourceFaults.stream()
                    .min(Comparator.comparing(f -> f.getSeverity().ordinal()))
                    .orElse(resourceFaults.get(0));

            // AI 진단
            DiagnosisResult diagnosis = aiDiagnosisService.diagnose(primaryFault, allFaults);
            results.add(diagnosis);
        }

        log.info("AI diagnosis completed. Generated {} diagnoses", results.size());
        return results;
    }

    /**
     * AI 진단과 함께 네임스페이스 스캔
     */
    public List<DiagnosisResult> scanNamespaceWithAI(String clusterId, String namespace) {
        log.info("Starting AI-powered namespace scan for {} in cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = scanNamespace(clusterId, namespace);

        // 중복 제거 및 AI 진단
        Map<String, List<FaultInfo>> groupedFaults = groupFaultsByResource(allFaults);

        List<DiagnosisResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FaultInfo>> entry : groupedFaults.entrySet()) {
            List<FaultInfo> resourceFaults = entry.getValue();

            // 가장 심각한 장애를 주요 장애로 선택
            FaultInfo primaryFault = resourceFaults.stream()
                    .min(Comparator.comparing(f -> f.getSeverity().ordinal()))
                    .orElse(resourceFaults.get(0));

            // AI 진단
            DiagnosisResult diagnosis = aiDiagnosisService.diagnose(primaryFault, allFaults);
            results.add(diagnosis);
        }

        log.info("AI diagnosis completed. Generated {} diagnoses", results.size());
        return results;
    }

    /**
     * 특정 Pod에 대한 AI 진단 (On-demand)
     */
    public DiagnosisResult diagnoseWithAI(String clusterId, String namespace, String podName,
                                          FaultInfo primaryFault, List<FaultInfo> allFaults) {
        log.info("Performing AI diagnosis for pod {}/{}", namespace, podName);

        // AI 진단 수행
        DiagnosisResult diagnosis = aiDiagnosisService.diagnose(primaryFault, allFaults);

        log.info("AI diagnosis completed for pod {}/{}", namespace, podName);
        return diagnosis;
    }

    /**
     * 리소스별로 장애 그룹핑 (중복 제거용)
     * 같은 리소스(Pod/Deployment 등)의 여러 장애를 하나로 묶음
     */
    private Map<String, List<FaultInfo>> groupFaultsByResource(List<FaultInfo> faults) {
        return faults.stream()
                .collect(Collectors.groupingBy(f -> {
                    String namespace = f.getNamespace() != null ? f.getNamespace() : "";
                    return String.format("%s/%s/%s", namespace, f.getResourceKind(), f.getResourceName());
                }));
    }

    /**
     * 특정 네임스페이스 전체 스캔 (Pod + Deployment)
     */
    public List<FaultInfo> scanNamespace(String clusterId, String namespace) {
        log.info("Starting namespace scan for {} in cluster {}", namespace, clusterId);

        List<FaultInfo> allFaults = new ArrayList<>();

        // Pod 스캔
        allFaults.addAll(scanPodsInNamespace(clusterId, namespace));

        // 워크로드 스캔
        allFaults.addAll(scanDeploymentsInNamespace(clusterId, namespace));
        allFaults.addAll(scanDaemonSetsInNamespace(clusterId, namespace));
        allFaults.addAll(scanStatefulSetsInNamespace(clusterId, namespace));
        allFaults.addAll(scanReplicaSetsInNamespace(clusterId, namespace));

        log.info("Namespace scan completed. Found {} total faults", allFaults.size());
        return allFaults;
    }
}
