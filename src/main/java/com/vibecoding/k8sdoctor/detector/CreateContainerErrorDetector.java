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
 * CreateContainerError 장애 탐지기
 *
 * 컨테이너 생성 단계에서 실패 (CrashLoopBackOff와 다름):
 * - 잘못된 command/args
 * - 잘못된 volumeMount 경로
 * - image entrypoint 문제
 * - securityContext 권한 문제
 */
@Component
public class CreateContainerErrorDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(CreateContainerErrorDetector.class);

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
            if (isCreateContainerError(status)) {
                faults.add(createFaultInfo(pod, status));
            }
        }

        // initContainerStatuses도 체크
        if (pod.getStatus().getInitContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                if (isCreateContainerError(status)) {
                    faults.add(createFaultInfo(pod, status));
                }
            }
        }

        return faults;
    }

    private boolean isCreateContainerError(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "CreateContainerError".equals(reason);
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
        symptoms.add("컨테이너 생성 단계에서 실패");
        symptoms.add("CrashLoopBackOff 이전 단계의 오류");

        if (waitingMessage.contains("command") || waitingMessage.contains("entrypoint")) {
            symptoms.add("command/entrypoint 실행 오류");
        }
        if (waitingMessage.contains("volume") || waitingMessage.contains("mount")) {
            symptoms.add("볼륨 마운트 오류");
        }
        if (waitingMessage.contains("permission") || waitingMessage.contains("denied")) {
            symptoms.add("권한 오류");
        }
        if (waitingMessage.contains("OCI") || waitingMessage.contains("runtime")) {
            symptoms.add("컨테이너 런타임 오류");
        }

        String description = buildDescription(waitingMessage, issueCategory);

        return FaultInfo.builder()
                .faultType(FaultType.CREATE_CONTAINER_ERROR)
                .severity(Severity.CRITICAL)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s' 생성 실패", status.getName()))
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

        if (lowerMsg.contains("executable file not found") || lowerMsg.contains("no such file or directory")) {
            return "COMMAND_NOT_FOUND";
        }
        if (lowerMsg.contains("permission denied") || lowerMsg.contains("operation not permitted")) {
            return "PERMISSION_DENIED";
        }
        if (lowerMsg.contains("entrypoint") || lowerMsg.contains("cmd")) {
            return "ENTRYPOINT_ERROR";
        }
        if (lowerMsg.contains("volume") || lowerMsg.contains("mount")) {
            return "VOLUME_MOUNT_ERROR";
        }
        if (lowerMsg.contains("oci runtime") || lowerMsg.contains("runc")) {
            return "OCI_RUNTIME_ERROR";
        }
        if (lowerMsg.contains("securitycontext") || lowerMsg.contains("capabilities")) {
            return "SECURITY_CONTEXT_ERROR";
        }

        return "CONTAINER_CREATE_ERROR";
    }

    /**
     * 카테고리에 따른 상세 설명 생성
     */
    private String buildDescription(String errorMessage, String category) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "COMMAND_NOT_FOUND":
                desc.append("지정된 command 또는 entrypoint 실행 파일을 찾을 수 없습니다. ");
                desc.append("이미지에 해당 바이너리가 포함되어 있는지, 경로가 올바른지 확인하세요.");
                break;
            case "PERMISSION_DENIED":
                desc.append("컨테이너 실행 권한이 거부되었습니다. ");
                desc.append("securityContext의 runAsUser/runAsGroup 설정과 파일 권한을 확인하세요.");
                break;
            case "ENTRYPOINT_ERROR":
                desc.append("이미지의 ENTRYPOINT 또는 command 설정에 문제가 있습니다. ");
                desc.append("올바른 실행 명령어와 인자를 확인하세요.");
                break;
            case "VOLUME_MOUNT_ERROR":
                desc.append("볼륨 마운트 중 오류가 발생했습니다. ");
                desc.append("마운트 경로가 올바른지, 대상 볼륨이 존재하는지 확인하세요.");
                break;
            case "OCI_RUNTIME_ERROR":
                desc.append("OCI 컨테이너 런타임에서 오류가 발생했습니다. ");
                desc.append("이미지 포맷, 아키텍처 호환성, 또는 런타임 설정을 확인하세요.");
                break;
            case "SECURITY_CONTEXT_ERROR":
                desc.append("보안 컨텍스트(securityContext) 설정 문제입니다. ");
                desc.append("capabilities, privileged, readOnlyRootFilesystem 등의 설정을 확인하세요.");
                break;
            default:
                desc.append("컨테이너 생성 단계에서 오류가 발생했습니다. ");
                desc.append("이미지, command, args, volumeMounts 설정을 확인하세요.");
        }

        if (!errorMessage.isEmpty()) {
            desc.append("\n\n원본 에러: ").append(errorMessage);
        }

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.CREATE_CONTAINER_ERROR;
    }
}
