package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * CreateContainerConfigError 장애 탐지기
 *
 * ConfigMap/Secret 참조 오류로 컨테이너 생성 실패:
 * - ConfigMap 키가 없음
 * - Secret 키가 없음
 * - envFrom 참조 실패
 * - volumeMount 대상 ConfigMap/Secret 없음
 */
@Component
public class CreateContainerConfigErrorDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(CreateContainerConfigErrorDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;
        List<FaultInfo> faults = new ArrayList<>();

        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return faults;
        }

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (isCreateContainerConfigError(status)) {
                faults.add(createFaultInfo(pod, status));
            }
        }

        // initContainerStatuses도 체크
        if (pod.getStatus().getInitContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                if (isCreateContainerConfigError(status)) {
                    faults.add(createFaultInfo(pod, status));
                }
            }
        }

        return faults;
    }

    private boolean isCreateContainerConfigError(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "CreateContainerConfigError".equals(reason);
        }
        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
        String waitingMessage = "";
        if (status.getState() != null && status.getState().getWaiting() != null) {
            waitingMessage = status.getState().getWaiting().getMessage();
            if (waitingMessage == null) waitingMessage = "";
        }

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

        // 에러 원인 분류
        String issueCategory = categorizeError(waitingMessage);

        // Context 구성
        Map<String, Object> context = new HashMap<>();
        context.put("containerName", status.getName());
        context.put("image", status.getImage() != null ? status.getImage() : "unknown");
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("errorMessage", waitingMessage);
        context.put("issueCategory", issueCategory);

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add("컨테이너가 생성 단계에서 실패함");
        symptoms.add("ConfigMap/Secret 참조 오류 발생");

        if (waitingMessage.contains("configmap")) {
            symptoms.add("ConfigMap 관련 오류 감지");
        }
        if (waitingMessage.contains("secret")) {
            symptoms.add("Secret 관련 오류 감지");
        }
        if (waitingMessage.contains("key")) {
            symptoms.add("특정 키를 찾을 수 없음");
        }
        if (waitingMessage.contains("not found") || waitingMessage.contains("doesn't exist")) {
            symptoms.add("참조된 리소스가 존재하지 않음");
        }

        String description = buildDescription(waitingMessage, issueCategory);

        return FaultInfo.builder()
                .faultType(FaultType.CREATE_CONTAINER_CONFIG_ERROR)
                .severity(Severity.CRITICAL)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s' 설정 오류로 생성 실패", status.getName()))
                .description(description)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 에러 메시지를 분석하여 카테고리 분류
     */
    private String categorizeError(String message) {
        String lowerMsg = message.toLowerCase();

        if (lowerMsg.contains("configmap") && lowerMsg.contains("key")) {
            return "CONFIGMAP_KEY_NOT_FOUND";
        }
        if (lowerMsg.contains("secret") && lowerMsg.contains("key")) {
            return "SECRET_KEY_NOT_FOUND";
        }
        if (lowerMsg.contains("configmap") && (lowerMsg.contains("not found") || lowerMsg.contains("doesn't exist"))) {
            return "CONFIGMAP_NOT_FOUND";
        }
        if (lowerMsg.contains("secret") && (lowerMsg.contains("not found") || lowerMsg.contains("doesn't exist"))) {
            return "SECRET_NOT_FOUND";
        }
        if (lowerMsg.contains("envfrom") || lowerMsg.contains("env from")) {
            return "ENVFROM_REFERENCE_ERROR";
        }
        if (lowerMsg.contains("volume") || lowerMsg.contains("mount")) {
            return "VOLUME_MOUNT_CONFIG_ERROR";
        }

        return "CONFIG_REFERENCE_ERROR";
    }

    /**
     * 카테고리에 따른 상세 설명 생성
     */
    private String buildDescription(String errorMessage, String category) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "CONFIGMAP_KEY_NOT_FOUND":
                desc.append("ConfigMap에서 참조한 키가 존재하지 않습니다. ");
                desc.append("env.valueFrom.configMapKeyRef에서 지정한 키가 ConfigMap에 있는지 확인하세요.");
                break;
            case "SECRET_KEY_NOT_FOUND":
                desc.append("Secret에서 참조한 키가 존재하지 않습니다. ");
                desc.append("env.valueFrom.secretKeyRef에서 지정한 키가 Secret에 있는지 확인하세요.");
                break;
            case "CONFIGMAP_NOT_FOUND":
                desc.append("참조된 ConfigMap이 존재하지 않습니다. ");
                desc.append("같은 네임스페이스에 ConfigMap을 생성하거나, 이름을 확인하세요.");
                break;
            case "SECRET_NOT_FOUND":
                desc.append("참조된 Secret이 존재하지 않습니다. ");
                desc.append("같은 네임스페이스에 Secret을 생성하거나, 이름을 확인하세요.");
                break;
            case "ENVFROM_REFERENCE_ERROR":
                desc.append("envFrom으로 전체 ConfigMap/Secret을 참조하는데 실패했습니다. ");
                desc.append("리소스 존재 여부와 이름을 확인하세요.");
                break;
            case "VOLUME_MOUNT_CONFIG_ERROR":
                desc.append("Volume으로 마운트할 ConfigMap/Secret을 찾을 수 없습니다. ");
                desc.append("volumes 섹션의 configMap/secret 참조를 확인하세요.");
                break;
            default:
                desc.append("ConfigMap 또는 Secret 참조 오류로 컨테이너 생성에 실패했습니다.");
        }

        if (!errorMessage.isEmpty()) {
            desc.append("\n\n원본 에러: ").append(errorMessage);
        }

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.CREATE_CONTAINER_CONFIG_ERROR;
    }
}
