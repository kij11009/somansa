package com.vibecoding.k8sdoctor.service;

import com.vibecoding.k8sdoctor.exception.K8sApiException;
import com.vibecoding.k8sdoctor.exception.K8sResourceNotFoundException;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 멀티 클러스터 환경에서 Kubernetes 리소스 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class MultiClusterK8sService {

    private static final Logger log = LoggerFactory.getLogger(MultiClusterK8sService.class);

    private final ClusterService clusterService;

    /**
     * 클러스터의 Kubernetes 클라이언트 조회
     */
    private KubernetesClient getClient(String clusterId) {
        Optional<KubernetesClient> clientOpt = clusterService.getKubernetesClient(clusterId);
        if (clientOpt.isEmpty()) {
            throw new K8sResourceNotFoundException("Cluster not found: " + clusterId);
        }
        return clientOpt.get();
    }

    // ========== Namespace ==========

    public List<Namespace> listNamespaces(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.namespaces().list().getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list namespaces in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list namespaces", e);
        }
    }

    public Namespace getNamespace(String clusterId, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            Namespace namespace = client.namespaces().withName(name).get();
            if (namespace == null) {
                throw new K8sResourceNotFoundException("Namespace not found: " + name);
            }
            return namespace;
        } catch (KubernetesClientException e) {
            log.error("Failed to get namespace: {}/{}", clusterId, name, e);
            throw new K8sApiException("Failed to get namespace", e);
        }
    }

    // ========== Pod ==========

    public List<Pod> listPodsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.pods()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list pods in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list pods", e);
        }
    }

    public List<Pod> listAllPods(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.pods()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all pods in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all pods", e);
        }
    }

    public Pod getPod(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            Pod pod = client.pods()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (pod == null) {
                throw new K8sResourceNotFoundException(
                    String.format("Pod not found: %s/%s", namespace, name)
                );
            }
            return pod;
        } catch (KubernetesClientException e) {
            log.error("Failed to get pod: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get pod", e);
        }
    }

    public String getPodLogs(String clusterId, String namespace, String name, String containerName, int tailLines) {
        try {
            KubernetesClient client = getClient(clusterId);

            String logs;
            if (containerName != null && !containerName.isBlank()) {
                logs = client.pods()
                    .inNamespace(namespace)
                    .withName(name)
                    .inContainer(containerName)
                    .tailingLines(tailLines)
                    .getLog();
            } else {
                logs = client.pods()
                    .inNamespace(namespace)
                    .withName(name)
                    .tailingLines(tailLines)
                    .getLog();
            }

            return logs != null ? logs : "No logs available";
        } catch (KubernetesClientException e) {
            log.error("Failed to get pod logs: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get pod logs", e);
        }
    }

    public List<Event> getPodEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "Pod")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get pod events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get pod events", e);
        }
    }

    // ========== Deployment ==========

    public List<Deployment> listDeploymentsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().deployments()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list deployments in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list deployments", e);
        }
    }

    public List<Deployment> listAllDeployments(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().deployments()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all deployments in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all deployments", e);
        }
    }

    public Deployment getDeployment(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            Deployment deployment = client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (deployment == null) {
                throw new K8sResourceNotFoundException(
                    String.format("Deployment not found: %s/%s", namespace, name)
                );
            }
            return deployment;
        } catch (KubernetesClientException e) {
            log.error("Failed to get deployment: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get deployment", e);
        }
    }

    public List<Event> getDeploymentEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "Deployment")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get deployment events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get deployment events", e);
        }
    }

    // ========== DaemonSet ==========

    public List<DaemonSet> listDaemonSetsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().daemonSets()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list daemonsets in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list daemonsets", e);
        }
    }

    public List<DaemonSet> listAllDaemonSets(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().daemonSets()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all daemonsets in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all daemonsets", e);
        }
    }

    public DaemonSet getDaemonSet(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            DaemonSet daemonSet = client.apps().daemonSets()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (daemonSet == null) {
                throw new K8sResourceNotFoundException(
                    String.format("DaemonSet not found: %s/%s", namespace, name)
                );
            }
            return daemonSet;
        } catch (KubernetesClientException e) {
            log.error("Failed to get daemonset: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get daemonset", e);
        }
    }

    public List<Event> getDaemonSetEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "DaemonSet")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get daemonset events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get daemonset events", e);
        }
    }

    // ========== Node ==========

    public List<Node> listNodes(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.nodes().list().getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list nodes in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list nodes", e);
        }
    }

    public Node getNode(String clusterId, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            Node node = client.nodes().withName(name).get();
            if (node == null) {
                throw new K8sResourceNotFoundException("Node not found: " + name);
            }
            return node;
        } catch (KubernetesClientException e) {
            log.error("Failed to get node: {}/{}", clusterId, name, e);
            throw new K8sApiException("Failed to get node", e);
        }
    }

    // ========== Events ==========

    public List<Event> listEventsInNamespace(String clusterId, String namespace, int limit) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(limit)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to list events in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list events", e);
        }
    }

    /**
     * Pod 로그 조회
     */
    public String getPodLogs(String clusterId, String namespace, String podName, String containerName, Integer tailLines) {
        log.info("Getting logs for pod {}/{} container {} from cluster {}", namespace, podName, containerName, clusterId);

        try {
            KubernetesClient client = getClient(clusterId);

            if (containerName != null && !containerName.isBlank()) {
                return client.pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .inContainer(containerName)
                        .tailingLines(tailLines != null ? tailLines : 100)
                        .getLog();
            } else {
                return client.pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .tailingLines(tailLines != null ? tailLines : 100)
                        .getLog();
            }
        } catch (Exception e) {
            log.warn("Failed to get logs for pod {}/{}: {}", namespace, podName, e.getMessage());
            return "";
        }
    }

    // ==================== StatefulSet ====================

    public List<StatefulSet> listStatefulSetsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().statefulSets()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list statefulsets in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list statefulsets", e);
        }
    }

    public List<StatefulSet> listAllStatefulSets(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().statefulSets()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all statefulsets in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all statefulsets", e);
        }
    }

    public StatefulSet getStatefulSet(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            StatefulSet statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (statefulSet == null) {
                throw new K8sResourceNotFoundException(
                    String.format("StatefulSet not found: %s/%s", namespace, name)
                );
            }
            return statefulSet;
        } catch (KubernetesClientException e) {
            log.error("Failed to get statefulset: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get statefulset", e);
        }
    }

    public List<Event> getStatefulSetEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "StatefulSet")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get statefulset events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get statefulset events", e);
        }
    }

    // ==================== ReplicaSet ====================

    public List<io.fabric8.kubernetes.api.model.apps.ReplicaSet> listReplicaSetsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().replicaSets()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list replicasets in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list replicasets", e);
        }
    }

    public List<io.fabric8.kubernetes.api.model.apps.ReplicaSet> listAllReplicaSets(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.apps().replicaSets()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all replicasets in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all replicasets", e);
        }
    }

    // ==================== Job ====================

    public List<Job> listJobsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.batch().v1().jobs()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list jobs in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list jobs", e);
        }
    }

    public List<Job> listAllJobs(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.batch().v1().jobs()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all jobs in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all jobs", e);
        }
    }

    public Job getJob(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            Job job = client.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (job == null) {
                throw new K8sResourceNotFoundException(
                    String.format("Job not found: %s/%s", namespace, name)
                );
            }
            return job;
        } catch (KubernetesClientException e) {
            log.error("Failed to get job: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get job", e);
        }
    }

    public String getJobLogs(String clusterId, String namespace, String jobName) {
        try {
            KubernetesClient client = getClient(clusterId);

            // Job이 생성한 Pod 찾기
            List<Pod> jobPods = client.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list()
                .getItems();

            if (jobPods.isEmpty()) {
                return "No pods found for this job";
            }

            // 가장 최근 Pod의 로그 가져오기
            Pod latestPod = jobPods.stream()
                .max(Comparator.comparing(pod ->
                    pod.getMetadata().getCreationTimestamp() != null
                        ? pod.getMetadata().getCreationTimestamp()
                        : ""
                ))
                .orElse(jobPods.get(0));

            String logs = client.pods()
                .inNamespace(namespace)
                .withName(latestPod.getMetadata().getName())
                .tailingLines(100)
                .getLog();

            return logs != null ? logs : "No logs available";
        } catch (KubernetesClientException e) {
            log.error("Failed to get job logs: {}/{}/{}", clusterId, namespace, jobName, e);
            return "Failed to retrieve logs: " + e.getMessage();
        }
    }

    public List<Event> getJobEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "Job")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get job events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get job events", e);
        }
    }

    // ==================== CronJob ====================

    public List<CronJob> listCronJobsInNamespace(String clusterId, String namespace) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.batch().v1().cronjobs()
                .inNamespace(namespace)
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list cronjobs in cluster: {}, namespace: {}", clusterId, namespace, e);
            throw new K8sApiException("Failed to list cronjobs", e);
        }
    }

    public List<CronJob> listAllCronJobs(String clusterId) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.batch().v1().cronjobs()
                .inAnyNamespace()
                .list()
                .getItems();
        } catch (KubernetesClientException e) {
            log.error("Failed to list all cronjobs in cluster: {}", clusterId, e);
            throw new K8sApiException("Failed to list all cronjobs", e);
        }
    }

    public CronJob getCronJob(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            CronJob cronJob = client.batch().v1().cronjobs()
                .inNamespace(namespace)
                .withName(name)
                .get();
            if (cronJob == null) {
                throw new K8sResourceNotFoundException(
                    String.format("CronJob not found: %s/%s", namespace, name)
                );
            }
            return cronJob;
        } catch (KubernetesClientException e) {
            log.error("Failed to get cronjob: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get cronjob", e);
        }
    }

    public List<Event> getCronJobEvents(String clusterId, String namespace, String name) {
        try {
            KubernetesClient client = getClient(clusterId);
            return client.v1().events()
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .withField("involvedObject.kind", "CronJob")
                .list()
                .getItems()
                .stream()
                .sorted(Comparator.comparing(
                    event -> event.getLastTimestamp() != null
                        ? event.getLastTimestamp()
                        : event.getFirstTimestamp(),
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(20)
                .collect(Collectors.toList());
        } catch (KubernetesClientException e) {
            log.error("Failed to get cronjob events: {}/{}/{}", clusterId, namespace, name, e);
            throw new K8sApiException("Failed to get cronjob events", e);
        }
    }

}
