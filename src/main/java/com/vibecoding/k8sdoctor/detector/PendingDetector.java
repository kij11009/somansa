package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pending 상태 Pod 탐지기
 */

@Component
public class PendingDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(PendingDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;

        if (pod.getStatus() == null || !"Pending".equals(pod.getStatus().getPhase())) {
            return Collections.emptyList();
        }

        String reason = analyzeReason(pod);
        List<String> symptoms = extractSymptoms(pod);

        // Pod의 owner 정보 추출
        String ownerKind = "Pod";
        String ownerName = pod.getMetadata().getName();

        if (pod.getMetadata().getOwnerReferences() != null && !pod.getMetadata().getOwnerReferences().isEmpty()) {
            var owner = pod.getMetadata().getOwnerReferences().get(0);
            ownerKind = owner.getKind();
            ownerName = owner.getName();

            if ("ReplicaSet".equals(ownerKind)) {
                ownerKind = "Deployment";
                int lastDash = ownerName.lastIndexOf('-');
                if (lastDash > 0) {
                    ownerName = ownerName.substring(0, lastDash);
                }
            }
        }

        // context에 더 구체적인 정보 추가
        Map<String, Object> context = new java.util.HashMap<>();
        context.put("phase", "Pending");
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);

        // 원본 스케줄링 메시지도 저장 (AI 분석용)
        String schedulingMessage = getSchedulingMessage(pod);
        if (!schedulingMessage.isEmpty()) {
            context.put("schedulingMessage", schedulingMessage);
        }

        // PVC 관련 문제인 경우 추가 정보 수집
        if (reason.contains("PVC") || reason.contains("PersistentVolume") || reason.contains("StorageClass") ||
            reason.contains("바인딩") || reason.contains("unbound")) {
            context.put("issueCategory", "PVC_BINDING");
            context.put("checkCommands", "kubectl get storageclass, kubectl get pvc, kubectl get pv");
        } else if (reason.contains("CPU") || reason.contains("cpu")) {
            context.put("issueCategory", "RESOURCE_SHORTAGE_CPU");
            context.put("checkCommands", "kubectl describe nodes | grep -A5 'Allocated resources'");
        } else if (reason.contains("memory") || reason.contains("메모리")) {
            context.put("issueCategory", "RESOURCE_SHORTAGE_MEMORY");
            context.put("checkCommands", "kubectl describe nodes | grep -A5 'Allocated resources'");
        } else if (reason.contains("리소스") || reason.contains("Insufficient")) {
            context.put("issueCategory", "RESOURCE_SHORTAGE");
            context.put("checkCommands", "kubectl describe nodes, kubectl top nodes");
        } else if (reason.contains("Taint") || reason.contains("Toleration") || reason.contains("taint")) {
            context.put("issueCategory", "TAINT_TOLERATION");
            context.put("checkCommands", "kubectl describe nodes | grep -A3 Taints");
        } else if (reason.contains("node") || reason.contains("affinity") || reason.contains("selector")) {
            context.put("issueCategory", "NODE_SELECTION");
            context.put("checkCommands", "kubectl get nodes --show-labels");
        }

        return List.of(FaultInfo.builder()
                .faultType(FaultType.PENDING)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary("Pod이 스케줄링되지 않음")
                .description(reason)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build());
    }

    private String analyzeReason(Pod pod) {
        if (pod.getStatus().getConditions() != null) {
            for (PodCondition condition : pod.getStatus().getConditions()) {
                if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                    String message = condition.getMessage() != null ? condition.getMessage() : "";
                    String reason = condition.getReason() != null ? condition.getReason() : "";

                    // PVC 바인딩 문제 감지
                    if (message.contains("unbound") && message.contains("PersistentVolumeClaim")) {
                        return "PVC(PersistentVolumeClaim)가 바인딩되지 않음. " +
                               "StorageClass가 없거나 사용 가능한 PV(PersistentVolume)가 없습니다. " +
                               "클러스터에 동적 프로비저닝이 설정되어 있는지 확인하세요.";
                    }

                    // 리소스 부족 감지
                    if (message.contains("Insufficient") || message.contains("insufficient")) {
                        if (message.contains("cpu")) {
                            return "노드에 CPU 리소스가 부족합니다. " +
                                   "Pod의 CPU 요청량을 줄이거나 클러스터에 노드를 추가하세요.";
                        } else if (message.contains("memory")) {
                            return "노드에 메모리 리소스가 부족합니다. " +
                                   "Pod의 메모리 요청량을 줄이거나 클러스터에 노드를 추가하세요.";
                        }
                        return "노드에 리소스가 부족합니다. " + message;
                    }

                    // Node Selector 문제 감지
                    if (message.contains("node(s) didn't match") || message.contains("MatchNodeSelector")) {
                        return "Node Selector 조건과 일치하는 노드가 없습니다. " +
                               "Pod의 nodeSelector 설정과 노드의 레이블을 확인하세요.";
                    }

                    // Taints/Tolerations 문제 감지
                    if (message.contains("taint") || message.contains("toleration")) {
                        return "노드에 Taint가 설정되어 있어 Pod이 스케줄링되지 않습니다. " +
                               "Pod에 적절한 Toleration을 추가하거나 노드의 Taint를 제거하세요.";
                    }

                    // Pod Affinity/Anti-Affinity 문제 감지
                    if (message.contains("Affinity") || message.contains("affinity")) {
                        return "Pod Affinity/Anti-Affinity 조건을 만족하는 노드가 없습니다. " +
                               "Pod의 affinity 설정을 확인하세요.";
                    }

                    // 기본 메시지 반환
                    if (!message.isEmpty()) {
                        return message;
                    }
                    if (!reason.isEmpty()) {
                        return reason;
                    }
                    return "Pod 스케줄링 실패";
                }
            }
        }
        return "알 수 없는 이유로 스케줄링 실패. 리소스 부족이나 노드 선택 제약 조건을 확인하세요.";
    }

    private List<String> extractSymptoms(Pod pod) {
        List<String> symptoms = new ArrayList<>();
        symptoms.add("Pod이 Pending 상태로 유지됨");

        if (pod.getStatus().getConditions() != null) {
            pod.getStatus().getConditions().stream()
                    .filter(c -> "False".equals(c.getStatus()))
                    .map(c -> String.format("%s: %s", c.getType(), c.getReason()))
                    .forEach(symptoms::add);
        }

        return symptoms;
    }

    /**
     * PodScheduled condition에서 원본 메시지 추출 (AI 분석용)
     */
    private String getSchedulingMessage(Pod pod) {
        if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
            for (PodCondition condition : pod.getStatus().getConditions()) {
                if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                    return condition.getMessage() != null ? condition.getMessage() : "";
                }
            }
        }
        return "";
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.PENDING;
    }
}
