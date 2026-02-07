package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 볼륨 마운트 및 권한 오류 탐지기
 *
 * 볼륨 관련 오류 감지:
 * - MountVolume.SetUp failed
 * - Permission denied
 * - Read-only filesystem
 * - fsGroup 권한 문제
 * - CSI driver 마운트 실패
 */
@Component
public class VolumeMountErrorDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(VolumeMountErrorDetector.class);

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

        // Pod 조건에서 볼륨 마운트 오류 확인
        if (pod.getStatus().getConditions() != null) {
            for (PodCondition condition : pod.getStatus().getConditions()) {
                if ("False".equals(condition.getStatus()) && condition.getMessage() != null) {
                    String message = condition.getMessage().toLowerCase();

                    // 볼륨 마운트 오류 키워드 확인
                    if (isVolumeMountError(message)) {
                        faults.add(createFaultInfo(pod, condition.getMessage(), condition.getReason()));
                        break;
                    }
                }
            }
        }

        // ContainerStatus에서 볼륨 관련 Waiting 상태 확인
        if (pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getState() != null && status.getState().getWaiting() != null) {
                    String message = status.getState().getWaiting().getMessage();
                    String reason = status.getState().getWaiting().getReason();

                    if (message != null && isVolumeMountError(message.toLowerCase())) {
                        faults.add(createFaultInfo(pod, message, reason));
                        break;
                    }
                }
            }
        }

        // initContainerStatuses도 확인
        if (pod.getStatus().getInitContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                if (status.getState() != null && status.getState().getWaiting() != null) {
                    String message = status.getState().getWaiting().getMessage();
                    String reason = status.getState().getWaiting().getReason();

                    if (message != null && isVolumeMountError(message.toLowerCase())) {
                        faults.add(createFaultInfo(pod, message, reason));
                        break;
                    }
                }
            }
        }

        return faults;
    }

    /**
     * 볼륨 마운트 오류인지 확인
     */
    private boolean isVolumeMountError(String message) {
        return message.contains("mountvolume") ||
               message.contains("mount volume") ||
               message.contains("volume mount") ||
               message.contains("failed to mount") ||
               message.contains("mount failed") ||
               (message.contains("permission") && (message.contains("volume") || message.contains("mount"))) ||
               message.contains("read-only file system") ||
               message.contains("readonly file system") ||
               message.contains("fsgroup") ||
               message.contains("chown") ||
               (message.contains("csi") && message.contains("mount"));
    }

    private FaultInfo createFaultInfo(Pod pod, String errorMessage, String reason) {
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

        // 이슈 카테고리 분류
        String issueCategory = classifyVolumeMountError(errorMessage);

        // Context 구성
        Map<String, Object> context = new HashMap<>();
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("issueCategory", issueCategory);
        context.put("errorMessage", errorMessage != null ? errorMessage : "");
        if (reason != null) {
            context.put("reason", reason);
        }

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add("볼륨 마운트 실패");
        symptoms.add("컨테이너 시작 불가");

        switch (issueCategory) {
            case "MOUNT_SETUP_FAILED":
                symptoms.add("MountVolume.SetUp 실패");
                break;
            case "PERMISSION_DENIED":
                symptoms.add("권한 거부됨");
                symptoms.add("파일 시스템 접근 불가");
                break;
            case "READONLY_FS":
                symptoms.add("읽기 전용 파일 시스템");
                symptoms.add("쓰기 작업 불가");
                break;
            case "FSGROUP_ERROR":
                symptoms.add("fsGroup 권한 문제");
                symptoms.add("볼륨 소유권 변경 실패");
                break;
            case "CSI_MOUNT_ERROR":
                symptoms.add("CSI 드라이버 마운트 실패");
                break;
            case "SUBPATH_ERROR":
                symptoms.add("subPath 마운트 오류");
                break;
        }

        String description = buildDescription(issueCategory, errorMessage);

        return FaultInfo.builder()
                .faultType(FaultType.VOLUME_MOUNT_ERROR)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary("볼륨 마운트 오류 발생")
                .description(description)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 볼륨 마운트 오류 카테고리 분류
     */
    private String classifyVolumeMountError(String message) {
        if (message == null || message.isEmpty()) {
            return "VOLUME_MOUNT_UNKNOWN";
        }

        String lowerMessage = message.toLowerCase();

        // 순서가 중요: 더 구체적인 것을 먼저 체크
        if (lowerMessage.contains("read-only") || lowerMessage.contains("readonly") ||
            lowerMessage.contains("read only")) {
            return "READONLY_FS";
        }

        if (lowerMessage.contains("fsgroup") || lowerMessage.contains("chown") ||
            (lowerMessage.contains("permission") && lowerMessage.contains("change"))) {
            return "FSGROUP_ERROR";
        }

        if (lowerMessage.contains("permission denied") || lowerMessage.contains("access denied") ||
            lowerMessage.contains("operation not permitted")) {
            return "PERMISSION_DENIED";
        }

        if (lowerMessage.contains("subpath")) {
            return "SUBPATH_ERROR";
        }

        if (lowerMessage.contains("csi") || lowerMessage.contains("driver")) {
            return "CSI_MOUNT_ERROR";
        }

        if (lowerMessage.contains("mountvolume") || lowerMessage.contains("mount volume") ||
            lowerMessage.contains("setup failed")) {
            return "MOUNT_SETUP_FAILED";
        }

        return "VOLUME_MOUNT_UNKNOWN";
    }

    /**
     * 상세 설명 생성
     */
    private String buildDescription(String category, String errorMessage) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "MOUNT_SETUP_FAILED":
                desc.append("볼륨 마운트 설정에 실패했습니다. ");
                desc.append("PV/PVC가 올바르게 바인딩되어 있는지, 마운트 대상이 존재하는지 확인하세요.");
                break;
            case "PERMISSION_DENIED":
                desc.append("볼륨 접근 권한이 거부되었습니다. ");
                desc.append("securityContext의 runAsUser, runAsGroup, fsGroup 설정을 확인하세요. ");
                desc.append("또한 PV/스토리지의 권한 설정도 확인하세요.");
                break;
            case "READONLY_FS":
                desc.append("파일 시스템이 읽기 전용으로 마운트되었습니다. ");
                desc.append("volumeMounts의 readOnly 설정, 또는 securityContext의 readOnlyRootFilesystem을 확인하세요.");
                break;
            case "FSGROUP_ERROR":
                desc.append("fsGroup 권한 변경에 실패했습니다. ");
                desc.append("볼륨이 fsGroup 변경을 지원하는지 확인하고, ");
                desc.append("fsGroupChangePolicy를 'OnRootMismatch'로 설정해 보세요.");
                break;
            case "CSI_MOUNT_ERROR":
                desc.append("CSI 드라이버가 볼륨 마운트에 실패했습니다. ");
                desc.append("CSI 드라이버 Pod 상태와 로그를 확인하세요.");
                break;
            case "SUBPATH_ERROR":
                desc.append("subPath 마운트에 실패했습니다. ");
                desc.append("지정된 subPath가 볼륨 내에 존재하는지 확인하세요.");
                break;
            default:
                desc.append("볼륨 마운트 중 오류가 발생했습니다.");
        }

        if (errorMessage != null && !errorMessage.isEmpty()) {
            desc.append("\n\n원본 에러: ").append(errorMessage);
        }

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.VOLUME_MOUNT_ERROR;
    }
}
