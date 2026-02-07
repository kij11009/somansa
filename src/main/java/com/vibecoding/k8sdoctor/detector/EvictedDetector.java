package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.Pod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Evicted(축출) 상태 Pod 탐지기
 *
 * 노드 리소스 압박으로 인한 Pod 축출 감지:
 * - ephemeral-storage 초과
 * - 메모리 압박 (MemoryPressure)
 * - 디스크 압박 (DiskPressure)
 * - PID 압박 (PIDPressure)
 */
@Component
public class EvictedDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(EvictedDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;
        List<FaultInfo> faults = new ArrayList<>();

        if (pod.getStatus() == null) {
            return faults;
        }

        // Phase가 Failed이고 reason이 Evicted인 경우
        String phase = pod.getStatus().getPhase();
        String reason = pod.getStatus().getReason();
        String message = pod.getStatus().getMessage();

        if ("Failed".equals(phase) && "Evicted".equals(reason)) {
            faults.add(createFaultInfo(pod, message));
        }

        return faults;
    }

    private FaultInfo createFaultInfo(Pod pod, String evictionMessage) {
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

        // 축출 원인 분류
        String issueCategory = classifyEvictionReason(evictionMessage);

        // Context 구성
        Map<String, Object> context = new HashMap<>();
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("issueCategory", issueCategory);
        context.put("evictionMessage", evictionMessage != null ? evictionMessage : "");
        if (pod.getSpec() != null && pod.getSpec().getNodeName() != null) {
            context.put("nodeName", pod.getSpec().getNodeName());
        }

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add("Pod이 노드에서 축출됨 (Evicted)");
        symptoms.add("Phase: Failed");

        switch (issueCategory) {
            case "EPHEMERAL_STORAGE_EXCEEDED":
                symptoms.add("ephemeral-storage 사용량 초과");
                symptoms.add("로그/임시 파일이 제한보다 많은 디스크 사용");
                break;
            case "DISK_PRESSURE":
                symptoms.add("노드 디스크 압박 (DiskPressure)");
                symptoms.add("노드의 디스크 용량 부족");
                break;
            case "MEMORY_PRESSURE":
                symptoms.add("노드 메모리 압박 (MemoryPressure)");
                symptoms.add("노드의 메모리 부족");
                break;
            case "PID_PRESSURE":
                symptoms.add("노드 PID 압박 (PIDPressure)");
                symptoms.add("노드의 프로세스 수 제한 도달");
                break;
            case "NODE_RESOURCE_PRESSURE":
                symptoms.add("노드 리소스 압박");
                break;
        }

        String description = buildDescription(issueCategory, evictionMessage);

        return FaultInfo.builder()
                .faultType(FaultType.EVICTED)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary("Pod이 노드에서 축출됨 (Evicted)")
                .description(description)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 축출 원인 분류
     */
    private String classifyEvictionReason(String message) {
        if (message == null || message.isEmpty()) {
            return "NODE_RESOURCE_PRESSURE";
        }

        String lowerMessage = message.toLowerCase();

        // ephemeral-storage 초과
        if (lowerMessage.contains("ephemeral-storage") || lowerMessage.contains("ephemeral storage") ||
            lowerMessage.contains("localstorage") || lowerMessage.contains("local storage")) {
            return "EPHEMERAL_STORAGE_EXCEEDED";
        }

        // 디스크 압박
        if (lowerMessage.contains("diskpressure") || lowerMessage.contains("disk pressure") ||
            lowerMessage.contains("nodefs") || lowerMessage.contains("imagefs")) {
            return "DISK_PRESSURE";
        }

        // 메모리 압박
        if (lowerMessage.contains("memorypressure") || lowerMessage.contains("memory pressure") ||
            lowerMessage.contains("memory") && lowerMessage.contains("evict")) {
            return "MEMORY_PRESSURE";
        }

        // PID 압박
        if (lowerMessage.contains("pidpressure") || lowerMessage.contains("pid pressure") ||
            lowerMessage.contains("pid")) {
            return "PID_PRESSURE";
        }

        return "NODE_RESOURCE_PRESSURE";
    }

    /**
     * 상세 설명 생성
     */
    private String buildDescription(String category, String evictionMessage) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "EPHEMERAL_STORAGE_EXCEEDED":
                desc.append("Pod이 ephemeral-storage 제한을 초과하여 축출되었습니다. ");
                desc.append("컨테이너의 로그, 임시 파일, 또는 emptyDir 볼륨 사용량이 제한을 초과했습니다. ");
                desc.append("\n\n해결 방법:\n");
                desc.append("1. ephemeral-storage limits 증가\n");
                desc.append("2. 애플리케이션 로그 로테이션 설정\n");
                desc.append("3. 임시 파일 정리 로직 추가\n");
                desc.append("4. emptyDir에 sizeLimit 설정");
                break;
            case "DISK_PRESSURE":
                desc.append("노드의 디스크 공간이 부족하여 Pod이 축출되었습니다. ");
                desc.append("nodefs(루트 파일시스템) 또는 imagefs(컨테이너 이미지용) 공간이 부족합니다. ");
                desc.append("\n\n해결 방법:\n");
                desc.append("1. 노드에서 불필요한 이미지 정리: crictl rmi --prune\n");
                desc.append("2. 노드 디스크 정리: docker system prune\n");
                desc.append("3. 노드 디스크 용량 증가");
                break;
            case "MEMORY_PRESSURE":
                desc.append("노드의 메모리가 부족하여 Pod이 축출되었습니다. ");
                desc.append("노드의 allocatable 메모리보다 더 많은 메모리가 사용 중입니다. ");
                desc.append("\n\n해결 방법:\n");
                desc.append("1. Pod 메모리 requests/limits 조정\n");
                desc.append("2. 불필요한 Pod 정리\n");
                desc.append("3. 노드 메모리 증가 또는 노드 추가");
                break;
            case "PID_PRESSURE":
                desc.append("노드의 PID 제한에 도달하여 Pod이 축출되었습니다. ");
                desc.append("너무 많은 프로세스가 실행 중입니다. ");
                desc.append("\n\n해결 방법:\n");
                desc.append("1. 프로세스 누수가 있는 컨테이너 확인\n");
                desc.append("2. Pod당 PID 제한 설정 검토\n");
                desc.append("3. 노드의 PID 제한 증가");
                break;
            default:
                desc.append("노드 리소스 압박으로 인해 Pod이 축출되었습니다.");
        }

        if (evictionMessage != null && !evictionMessage.isEmpty()) {
            desc.append("\n\n원본 메시지: ").append(evictionMessage);
        }

        desc.append("\n\n참고: Evicted Pod은 자동으로 재생성되지 않으므로 수동 삭제가 필요할 수 있습니다.");
        desc.append("\nkubectl delete pod POD_NAME -n NAMESPACE");

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.EVICTED;
    }
}
