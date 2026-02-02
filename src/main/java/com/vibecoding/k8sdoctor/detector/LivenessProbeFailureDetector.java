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
 * Liveness Probe 실패 탐지기
 */
@Component
public class LivenessProbeFailureDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(LivenessProbeFailureDetector.class);
    private static final int RESTART_THRESHOLD = 1; // 1번 이상 재시작 시 탐지

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

        // Liveness Probe 설정 확인
        boolean hasLivenessProbe = pod.getSpec() != null &&
                                   pod.getSpec().getContainers() != null &&
                                   pod.getSpec().getContainers().stream()
                                       .anyMatch(c -> c.getLivenessProbe() != null);

        if (!hasLivenessProbe) {
            return faults; // Liveness Probe가 없으면 검사 안 함
        }

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (isLivenessProbeFailure(status)) {
                log.info("Detected liveness probe failure for container: {} (restarts: {})",
                        status.getName(), status.getRestartCount());
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    private boolean isLivenessProbeFailure(ContainerStatus status) {
        // Liveness Probe 실패 = 컨테이너가 재시작됨
        Integer restartCount = status.getRestartCount();
        if (restartCount != null && restartCount >= RESTART_THRESHOLD) {
            log.debug("Container {} has {} restarts (threshold: {})",
                    status.getName(), restartCount, RESTART_THRESHOLD);
            return true;
        }

        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
        Integer restartCount = status.getRestartCount() != null ? status.getRestartCount() : 0;

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

        return FaultInfo.builder()
                .faultType(FaultType.LIVENESS_PROBE_FAILED)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s'가 Liveness Probe 실패로 %d회 재시작됨",
                        status.getName(), restartCount))
                .description("컨테이너가 Liveness Probe 검사에 실패하여 반복적으로 재시작되고 있습니다. " +
                        "Probe 설정(경로, 포트, 타임아웃)을 확인하고 애플리케이션이 제대로 응답하는지 확인해야 합니다.")
                .symptoms(Arrays.asList(
                        "컨테이너 재시작 횟수 증가 중",
                        "Liveness Probe 검사 실패",
                        "애플리케이션이 응답하지 않거나 비정상 상태"
                ))
                .context(Map.of(
                        "containerName", status.getName(),
                        "restartCount", restartCount,
                        "image", status.getImage() != null ? status.getImage() : "unknown",
                        "ownerKind", ownerKind,
                        "ownerName", ownerName
                ))
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.LIVENESS_PROBE_FAILED;
    }
}
