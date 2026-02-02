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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ImagePullBackOff 장애 탐지기
 */

@Component
public class ImagePullBackOffDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(ImagePullBackOffDetector.class);

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
            if (isImagePullError(status)) {
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    private boolean isImagePullError(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason);
        }
        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
        String message = "";
        if (status.getState() != null &&
            status.getState().getWaiting() != null &&
            status.getState().getWaiting().getMessage() != null) {
            message = status.getState().getWaiting().getMessage();
        }

        // Pod의 owner 정보 추출 (Deployment 관리 여부 확인)
        String ownerKind = "Pod";  // 기본값: 단독 Pod
        String ownerName = pod.getMetadata().getName();

        if (pod.getMetadata().getOwnerReferences() != null && !pod.getMetadata().getOwnerReferences().isEmpty()) {
            var owner = pod.getMetadata().getOwnerReferences().get(0);
            ownerKind = owner.getKind();  // ReplicaSet, StatefulSet, DaemonSet 등
            ownerName = owner.getName();

            // ReplicaSet인 경우 Deployment로 간주 (일반적인 패턴)
            if ("ReplicaSet".equals(ownerKind)) {
                ownerKind = "Deployment";
                // ReplicaSet 이름에서 Deployment 이름 추출 (마지막 해시 제거)
                // 예: nginx-deployment-5d7f9c8b6f -> nginx-deployment
                int lastDash = ownerName.lastIndexOf('-');
                if (lastDash > 0) {
                    ownerName = ownerName.substring(0, lastDash);
                }
            }
        }

        // 에러 메시지에서 구체적인 원인 분류
        String errorCategory = classifyImagePullError(message);

        // context에 더 많은 정보 추가
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("containerName", status.getName());
        context.put("image", status.getImage() != null ? status.getImage() : "unknown");
        context.put("errorMessage", message);
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("errorCategory", errorCategory);

        // 증상에 에러 카테고리 추가
        List<String> symptoms = new ArrayList<>(Arrays.asList(
                "컨테이너가 시작되지 않음",
                "이미지 Pull 실패",
                "ImagePullBackOff 또는 ErrImagePull 상태"
        ));
        symptoms.add("에러 유형: " + errorCategory);

        return FaultInfo.builder()
                .faultType(FaultType.IMAGE_PULL_BACK_OFF)
                .severity(Severity.CRITICAL)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("이미지를 가져올 수 없음: %s", status.getImage()))
                .description(message.isEmpty() ?
                        "컨테이너 이미지를 Pull 할 수 없습니다. 이미지 이름, 태그, 레지스트리 인증 정보를 확인하세요." :
                        message)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 이미지 Pull 에러 메시지에서 구체적인 원인 분류
     */
    private String classifyImagePullError(String message) {
        if (message == null || message.isEmpty()) {
            return "UNKNOWN";
        }

        String lowerMessage = message.toLowerCase();

        // 인증 실패
        if (lowerMessage.contains("401") || lowerMessage.contains("unauthorized") ||
            lowerMessage.contains("authentication") || lowerMessage.contains("denied")) {
            return "AUTHENTICATION_FAILED";
        }

        // 이미지 없음
        if (lowerMessage.contains("404") || lowerMessage.contains("not found") ||
            lowerMessage.contains("manifest unknown") || lowerMessage.contains("does not exist")) {
            return "IMAGE_NOT_FOUND";
        }

        // 레지스트리 연결 실패
        if (lowerMessage.contains("timeout") || lowerMessage.contains("connection refused") ||
            lowerMessage.contains("no such host") || lowerMessage.contains("network")) {
            return "REGISTRY_UNREACHABLE";
        }

        // Rate limit
        if (lowerMessage.contains("429") || lowerMessage.contains("rate limit") ||
            lowerMessage.contains("too many requests")) {
            return "RATE_LIMITED";
        }

        // 이미지 포맷/아키텍처 문제
        if (lowerMessage.contains("manifest") || lowerMessage.contains("platform") ||
            lowerMessage.contains("architecture")) {
            return "IMAGE_FORMAT_ERROR";
        }

        // 권한 문제 (403)
        if (lowerMessage.contains("403") || lowerMessage.contains("forbidden")) {
            return "ACCESS_DENIED";
        }

        return "UNKNOWN";
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.IMAGE_PULL_BACK_OFF;
    }
}
