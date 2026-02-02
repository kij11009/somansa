package com.vibecoding.k8sdoctor.controller;

import com.vibecoding.k8sdoctor.model.ClusterInfo;
import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.service.ClusterService;
import com.vibecoding.k8sdoctor.service.FaultClassificationService;
import com.vibecoding.k8sdoctor.service.MultiClusterK8sService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Kubernetes 리소스 조회 컨트롤러 (멀티 클러스터 지원)
 */
@Controller
@RequestMapping("/clusters/{clusterId}/resources")
@RequiredArgsConstructor
public class ResourceController {

    private static final Logger log = LoggerFactory.getLogger(ResourceController.class);

    private final MultiClusterK8sService multiClusterK8sService;
    private final ClusterService clusterService;
    private final FaultClassificationService faultService;

    /**
     * 네임스페이스 목록
     */
    @GetMapping("/namespaces")
    public String listNamespaces(
        @PathVariable String clusterId,
        Model model
    ) {
        log.info("Listing namespaces in cluster: {}", clusterId);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Namespace> namespaces = multiClusterK8sService.listNamespaces(clusterId);

        model.addAttribute("cluster", cluster);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "Namespaces - " + cluster.getName());
        return "resources/namespaces";
    }

    /**
     * Pod 목록 (네임스페이스별)
     */
    @GetMapping("/pods")
    public String listPods(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing pods in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Pod> pods;
        if (namespace != null && !namespace.isBlank()) {
            pods = multiClusterK8sService.listPodsInNamespace(clusterId, namespace);
        } else {
            pods = multiClusterK8sService.listAllPods(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("pods", pods);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "Pods - " + namespace);
        return "resources/pods";
    }

    /**
     * Pod 상세 정보
     */
    @GetMapping("/pods/{namespace}/{name}")
    public String getPodDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting pod detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        Pod pod = multiClusterK8sService.getPod(clusterId, namespace, name);
        List<Event> events = multiClusterK8sService.getPodEvents(clusterId, namespace, name);

        // 로그 가져오기 (첫 번째 컨테이너, 최근 100줄)
        String logs = "";
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
            && !pod.getStatus().getContainerStatuses().isEmpty()) {
            String containerName = pod.getStatus().getContainerStatuses().get(0).getName();
            try {
                logs = multiClusterK8sService.getPodLogs(clusterId, namespace, name, containerName, 100);
            } catch (Exception e) {
                log.warn("Failed to get pod logs: {}/{}/{}", clusterId, namespace, name, e);
                logs = "로그를 가져올 수 없습니다: " + e.getMessage();
            }
        }

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(pod);

        model.addAttribute("cluster", cluster);
        model.addAttribute("pod", pod);
        model.addAttribute("events", events);
        model.addAttribute("logs", logs);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "Pod Detail - " + name);
        return "resources/pod-detail";
    }

    /**
     * Deployment 목록
     */
    @GetMapping("/deployments")
    public String listDeployments(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing deployments in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Deployment> deployments;
        if (namespace != null && !namespace.isBlank()) {
            deployments = multiClusterK8sService.listDeploymentsInNamespace(clusterId, namespace);
        } else {
            deployments = multiClusterK8sService.listAllDeployments(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("deployments", deployments);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "Deployments - " + namespace);
        return "resources/deployments";
    }

    /**
     * Deployment 상세 정보 및 진단
     */
    @GetMapping("/deployments/{namespace}/{name}")
    public String getDeploymentDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting deployment detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        Deployment deployment = multiClusterK8sService.getDeployment(clusterId, namespace, name);

        // Deployment의 Pod 목록
        List<Pod> pods = multiClusterK8sService.listPodsInNamespace(clusterId, namespace).stream()
            .filter(pod -> belongsToDeployment(pod, deployment))
            .collect(Collectors.toList());

        // Deployment 이벤트
        List<Event> events = multiClusterK8sService.getDeploymentEvents(clusterId, namespace, name);

        // Deployment 상태 분석
        boolean isHealthy = isDeploymentHealthy(deployment);
        String statusMessage = getDeploymentStatusMessage(deployment);

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(deployment);

        model.addAttribute("cluster", cluster);
        model.addAttribute("deployment", deployment);
        model.addAttribute("pods", pods);
        model.addAttribute("events", events);
        model.addAttribute("isHealthy", isHealthy);
        model.addAttribute("statusMessage", statusMessage);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "Deployment Detail - " + name);
        return "resources/deployment-detail";
    }

    /**
     * DaemonSet 목록
     */
    @GetMapping("/daemonsets")
    public String listDaemonSets(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing daemonsets in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<DaemonSet> daemonSets;
        if (namespace != null && !namespace.isBlank()) {
            daemonSets = multiClusterK8sService.listDaemonSetsInNamespace(clusterId, namespace);
        } else {
            daemonSets = multiClusterK8sService.listAllDaemonSets(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("daemonsets", daemonSets);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "DaemonSets - " + namespace);
        return "resources/daemonsets";
    }

    /**
     * DaemonSet 상세 정보 및 진단
     */
    @GetMapping("/daemonsets/{namespace}/{name}")
    public String getDaemonSetDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting daemonset detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        DaemonSet daemonSet = multiClusterK8sService.getDaemonSet(clusterId, namespace, name);

        // DaemonSet의 Pod 목록
        List<Pod> pods = multiClusterK8sService.listPodsInNamespace(clusterId, namespace).stream()
            .filter(pod -> belongsToDaemonSet(pod, daemonSet))
            .collect(Collectors.toList());

        // DaemonSet 이벤트
        List<Event> events = multiClusterK8sService.getDaemonSetEvents(clusterId, namespace, name);

        // DaemonSet 상태 분석
        boolean isHealthy = isDaemonSetHealthy(daemonSet);
        String statusMessage = getDaemonSetStatusMessage(daemonSet);

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(daemonSet);

        model.addAttribute("cluster", cluster);
        model.addAttribute("daemonset", daemonSet);
        model.addAttribute("pods", pods);
        model.addAttribute("events", events);
        model.addAttribute("isHealthy", isHealthy);
        model.addAttribute("statusMessage", statusMessage);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "DaemonSet Detail - " + name);
        return "resources/daemonset-detail";
    }

    /**
     * Node 목록
     */
    @GetMapping("/nodes")
    public String listNodes(
        @PathVariable String clusterId,
        Model model
    ) {
        log.info("Listing nodes in cluster: {}", clusterId);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Node> nodes = multiClusterK8sService.listNodes(clusterId);

        model.addAttribute("cluster", cluster);
        model.addAttribute("nodes", nodes);
        model.addAttribute("title", "Nodes - " + cluster.getName());
        return "resources/nodes";
    }

    /**
     * Node 상세 정보 및 진단
     */
    @GetMapping("/nodes/{name}")
    public String getNodeDetail(
        @PathVariable String clusterId,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting node detail: {}/{}", clusterId, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        Node node = multiClusterK8sService.getNode(clusterId, name);

        // Node의 Pod 목록
        List<Pod> pods = multiClusterK8sService.listAllPods(clusterId).stream()
            .filter(pod -> pod.getSpec() != null &&
                          name.equals(pod.getSpec().getNodeName()))
            .collect(Collectors.toList());

        // Node 상태 분석
        boolean isReady = isNodeReady(node);
        List<String> conditions = getNodeConditions(node);
        String statusMessage = getNodeStatusMessage(node);

        // 리소스 사용률
        NodeStatus status = node.getStatus();
        String cpuCapacity = status != null && status.getCapacity() != null ?
            status.getCapacity().get("cpu").getAmount() : "N/A";
        String memoryCapacity = status != null && status.getCapacity() != null ?
            status.getCapacity().get("memory").getAmount() : "N/A";

        // YAML serialization for viewer
        String yaml = Serialization.asYaml(node);
        model.addAttribute("resourceYaml", yaml);

        model.addAttribute("cluster", cluster);
        model.addAttribute("node", node);
        model.addAttribute("pods", pods);
        model.addAttribute("isReady", isReady);
        model.addAttribute("conditions", conditions);
        model.addAttribute("statusMessage", statusMessage);
        model.addAttribute("cpuCapacity", cpuCapacity);
        model.addAttribute("memoryCapacity", memoryCapacity);
        model.addAttribute("title", "Node Detail - " + name);
        return "resources/node-detail";
    }

    /**
     * Events 목록
     */
    @GetMapping("/events")
    public String listEvents(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        @RequestParam(defaultValue = "50") int limit,
        Model model
    ) {
        log.info("Listing events in cluster: {}, namespace: {}, limit: {}", clusterId, namespace, limit);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Event> events;
        if (namespace != null && !namespace.isBlank()) {
            events = multiClusterK8sService.listEventsInNamespace(clusterId, namespace, limit);
        } else {
            namespace = "default";
            events = multiClusterK8sService.listEventsInNamespace(clusterId, namespace, limit);
        }

        model.addAttribute("cluster", cluster);
        model.addAttribute("events", events);
        model.addAttribute("namespace", namespace);
        model.addAttribute("title", "Events - " + namespace);
        return "resources/events";
    }

    // Helper methods

    /**
     * Pod이 특정 Deployment에 속하는지 확인
     */
    private boolean belongsToDeployment(Pod pod, Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getSelector() == null ||
            deployment.getSpec().getSelector().getMatchLabels() == null) {
            return false;
        }

        if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null) {
            return false;
        }

        var deploymentLabels = deployment.getSpec().getSelector().getMatchLabels();
        var podLabels = pod.getMetadata().getLabels();

        return deploymentLabels.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }

    /**
     * Deployment가 정상 상태인지 확인
     */
    private boolean isDeploymentHealthy(Deployment deployment) {
        if (deployment.getStatus() == null) {
            return false;
        }

        var status = deployment.getStatus();
        Integer replicas = deployment.getSpec().getReplicas();
        Integer readyReplicas = status.getReadyReplicas();

        return replicas != null && readyReplicas != null && replicas.equals(readyReplicas);
    }

    /**
     * Deployment 상태 메시지 생성
     */
    private String getDeploymentStatusMessage(Deployment deployment) {
        if (deployment.getStatus() == null) {
            return "상태 정보 없음";
        }

        var status = deployment.getStatus();
        Integer replicas = deployment.getSpec().getReplicas();
        Integer readyReplicas = status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
        Integer availableReplicas = status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;

        if (replicas != null && replicas.equals(readyReplicas)) {
            return String.format("정상: %d/%d replicas ready", readyReplicas, replicas);
        } else {
            return String.format("비정상: %d/%d replicas ready, %d available",
                readyReplicas, replicas != null ? replicas : 0, availableReplicas);
        }
    }

    /**
     * Node가 Ready 상태인지 확인
     */
    private boolean isNodeReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return false;
        }

        return node.getStatus().getConditions().stream()
            .filter(c -> "Ready".equals(c.getType()))
            .anyMatch(c -> "True".equals(c.getStatus()));
    }

    /**
     * Node의 Conditions 목록
     */
    private List<String> getNodeConditions(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return List.of("No conditions");
        }

        return node.getStatus().getConditions().stream()
            .map(c -> String.format("%s: %s (%s)", c.getType(), c.getStatus(), c.getReason()))
            .collect(Collectors.toList());
    }

    /**
     * Node 상태 메시지 생성
     */
    private String getNodeStatusMessage(Node node) {
        if (isNodeReady(node)) {
            return "Node is Ready";
        } else {
            return "Node is NOT Ready";
        }
    }

    /**
     * StatefulSet 목록
     */
    @GetMapping("/statefulsets")
    public String listStatefulSets(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing statefulsets in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<StatefulSet> statefulSets;
        if (namespace != null && !namespace.isBlank()) {
            statefulSets = multiClusterK8sService.listStatefulSetsInNamespace(clusterId, namespace);
        } else {
            statefulSets = multiClusterK8sService.listAllStatefulSets(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("statefulsets", statefulSets);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "StatefulSets - " + namespace);
        return "resources/statefulsets";
    }

    /**
     * StatefulSet 상세 정보
     */
    @GetMapping("/statefulsets/{namespace}/{name}")
    public String getStatefulSetDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting statefulset detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        StatefulSet statefulSet = multiClusterK8sService.getStatefulSet(clusterId, namespace, name);

        // StatefulSet의 Pod 목록
        List<Pod> pods = multiClusterK8sService.listPodsInNamespace(clusterId, namespace).stream()
            .filter(pod -> belongsToStatefulSet(pod, statefulSet))
            .collect(Collectors.toList());

        // StatefulSet 이벤트
        List<Event> events = multiClusterK8sService.getStatefulSetEvents(clusterId, namespace, name);

        // StatefulSet 상태 분석
        boolean isHealthy = isStatefulSetHealthy(statefulSet);
        String statusMessage = getStatefulSetStatusMessage(statefulSet);

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(statefulSet);

        model.addAttribute("cluster", cluster);
        model.addAttribute("statefulset", statefulSet);
        model.addAttribute("pods", pods);
        model.addAttribute("events", events);
        model.addAttribute("isHealthy", isHealthy);
        model.addAttribute("statusMessage", statusMessage);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "StatefulSet Detail - " + name);
        return "resources/statefulset-detail";
    }

    /**
     * Job 목록
     */
    @GetMapping("/jobs")
    public String listJobs(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing jobs in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<Job> jobs;
        if (namespace != null && !namespace.isBlank()) {
            jobs = multiClusterK8sService.listJobsInNamespace(clusterId, namespace);
        } else {
            jobs = multiClusterK8sService.listAllJobs(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("jobs", jobs);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "Jobs - " + namespace);
        return "resources/jobs";
    }

    /**
     * Job 상세 정보
     */
    @GetMapping("/jobs/{namespace}/{name}")
    public String getJobDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting job detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        Job job = multiClusterK8sService.getJob(clusterId, namespace, name);

        // Job의 Pod 목록
        List<Pod> pods = multiClusterK8sService.listPodsInNamespace(clusterId, namespace).stream()
            .filter(pod -> pod.getMetadata() != null &&
                          pod.getMetadata().getLabels() != null &&
                          name.equals(pod.getMetadata().getLabels().get("job-name")))
            .collect(Collectors.toList());

        // Job 로그
        String logs = multiClusterK8sService.getJobLogs(clusterId, namespace, name);

        // Job 이벤트
        List<Event> events = multiClusterK8sService.getJobEvents(clusterId, namespace, name);

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(job);

        model.addAttribute("cluster", cluster);
        model.addAttribute("job", job);
        model.addAttribute("pods", pods);
        model.addAttribute("logs", logs);
        model.addAttribute("events", events);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "Job Detail - " + name);
        return "resources/job-detail";
    }

    /**
     * CronJob 목록
     */
    @GetMapping("/cronjobs")
    public String listCronJobs(
        @PathVariable String clusterId,
        @RequestParam(required = false) String namespace,
        Model model
    ) {
        log.info("Listing cronjobs in cluster: {}, namespace: {}", clusterId, namespace);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        List<CronJob> cronJobs;
        if (namespace != null && !namespace.isBlank()) {
            cronJobs = multiClusterK8sService.listCronJobsInNamespace(clusterId, namespace);
        } else {
            cronJobs = multiClusterK8sService.listAllCronJobs(clusterId);
            namespace = "All Namespaces";
        }

        // Add namespace list for filter dropdown
        List<String> namespaces = multiClusterK8sService.listNamespaces(clusterId)
            .stream()
            .map(ns -> ns.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());

        model.addAttribute("cluster", cluster);
        model.addAttribute("cronjobs", cronJobs);
        model.addAttribute("namespace", namespace);
        model.addAttribute("namespaces", namespaces);
        model.addAttribute("title", "CronJobs - " + namespace);
        return "resources/cronjobs";
    }

    /**
     * CronJob 상세 정보
     */
    @GetMapping("/cronjobs/{namespace}/{name}")
    public String getCronJobDetail(
        @PathVariable String clusterId,
        @PathVariable String namespace,
        @PathVariable String name,
        Model model
    ) {
        log.info("Getting cronjob detail: {}/{}/{}", clusterId, namespace, name);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found"));

        CronJob cronJob = multiClusterK8sService.getCronJob(clusterId, namespace, name);

        // CronJob이 생성한 Job 목록
        List<Job> jobs = multiClusterK8sService.listJobsInNamespace(clusterId, namespace).stream()
            .filter(job -> job.getMetadata() != null &&
                          job.getMetadata().getOwnerReferences() != null &&
                          job.getMetadata().getOwnerReferences().stream()
                              .anyMatch(ref -> "CronJob".equals(ref.getKind()) && name.equals(ref.getName())))
            .sorted(Comparator.comparing(job ->
                job.getMetadata().getCreationTimestamp() != null
                    ? job.getMetadata().getCreationTimestamp()
                    : "",
                Comparator.reverseOrder()
            ))
            .limit(10)
            .collect(Collectors.toList());

        // CronJob 이벤트
        List<Event> events = multiClusterK8sService.getCronJobEvents(clusterId, namespace, name);

        // Add YAML serialization for YAML viewer modal
        String yaml = Serialization.asYaml(cronJob);

        model.addAttribute("cluster", cluster);
        model.addAttribute("cronjob", cronJob);
        model.addAttribute("jobs", jobs);
        model.addAttribute("events", events);
        model.addAttribute("resourceYaml", yaml);
        model.addAttribute("title", "CronJob Detail - " + name);
        return "resources/cronjob-detail";
    }

    /**
     * Pod이 특정 StatefulSet에 속하는지 확인
     */
    private boolean belongsToStatefulSet(Pod pod, StatefulSet statefulSet) {
        if (statefulSet.getSpec() == null || statefulSet.getSpec().getSelector() == null ||
            statefulSet.getSpec().getSelector().getMatchLabels() == null) {
            return false;
        }

        if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null) {
            return false;
        }

        var statefulSetLabels = statefulSet.getSpec().getSelector().getMatchLabels();
        var podLabels = pod.getMetadata().getLabels();

        return statefulSetLabels.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }

    /**
     * Pod이 특정 DaemonSet에 속하는지 확인
     */
    private boolean belongsToDaemonSet(Pod pod, DaemonSet daemonSet) {
        if (daemonSet.getSpec() == null || daemonSet.getSpec().getSelector() == null ||
            daemonSet.getSpec().getSelector().getMatchLabels() == null) {
            return false;
        }

        if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null) {
            return false;
        }

        var daemonSetLabels = daemonSet.getSpec().getSelector().getMatchLabels();
        var podLabels = pod.getMetadata().getLabels();

        return daemonSetLabels.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }

    /**
     * DaemonSet이 정상 상태인지 확인
     */
    private boolean isDaemonSetHealthy(DaemonSet daemonSet) {
        if (daemonSet.getStatus() == null) {
            return false;
        }

        var status = daemonSet.getStatus();
        Integer desired = status.getDesiredNumberScheduled();
        Integer ready = status.getNumberReady();

        return desired != null && ready != null && desired.equals(ready);
    }

    /**
     * DaemonSet 상태 메시지 생성
     */
    private String getDaemonSetStatusMessage(DaemonSet daemonSet) {
        if (daemonSet.getStatus() == null) {
            return "상태 정보 없음";
        }

        var status = daemonSet.getStatus();
        Integer desired = status.getDesiredNumberScheduled() != null ? status.getDesiredNumberScheduled() : 0;
        Integer ready = status.getNumberReady() != null ? status.getNumberReady() : 0;
        Integer available = status.getNumberAvailable() != null ? status.getNumberAvailable() : 0;

        if (desired.equals(ready)) {
            return String.format("정상: %d/%d pods ready", ready, desired);
        } else {
            return String.format("비정상: %d/%d pods ready, %d available", ready, desired, available);
        }
    }

    /**
     * StatefulSet이 정상 상태인지 확인
     */
    private boolean isStatefulSetHealthy(StatefulSet statefulSet) {
        if (statefulSet.getStatus() == null) {
            return false;
        }

        var status = statefulSet.getStatus();
        Integer replicas = statefulSet.getSpec().getReplicas();
        Integer readyReplicas = status.getReadyReplicas();

        return replicas != null && readyReplicas != null && replicas.equals(readyReplicas);
    }

    /**
     * StatefulSet 상태 메시지 생성
     */
    private String getStatefulSetStatusMessage(StatefulSet statefulSet) {
        if (statefulSet.getStatus() == null) {
            return "상태 정보 없음";
        }

        var status = statefulSet.getStatus();
        Integer replicas = statefulSet.getSpec().getReplicas();
        Integer readyReplicas = status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
        Integer currentReplicas = status.getCurrentReplicas() != null ? status.getCurrentReplicas() : 0;

        if (replicas != null && replicas.equals(readyReplicas)) {
            return String.format("정상: %d/%d replicas ready", readyReplicas, replicas);
        } else {
            return String.format("비정상: %d/%d replicas ready, %d current",
                readyReplicas, replicas != null ? replicas : 0, currentReplicas);
        }
    }
}
