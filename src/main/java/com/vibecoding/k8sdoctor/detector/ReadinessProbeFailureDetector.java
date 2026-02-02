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
 * Readiness Probe 실패 탐지기
 */
@Component
public class ReadinessProbeFailureDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(ReadinessProbeFailureDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;
        List<FaultInfo> faults = new ArrayList<>();

        // Pod이 Running 상태가 아니면 검사 안 함
        if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
            return faults;
        }

        if (pod.getStatus().getContainerStatuses() == null) {
            return faults;
        }

        // Readiness Probe 설정 확인
        boolean hasReadinessProbe = pod.getSpec() != null &&
                                   pod.getSpec().getContainers() != null &&
                                   pod.getSpec().getContainers().stream()
                                       .anyMatch(c -> c.getReadinessProbe() != null);

        if (!hasReadinessProbe) {
            return faults; // Readiness Probe가 없으면 검사 안 함
        }

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (isReadinessProbeFailure(status)) {
                log.info("Detected readiness probe failure for container: {}", status.getName());
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    private boolean isReadinessProbeFailure(ContainerStatus status) {
        // 단순 체크: Ready가 false이고, 컨테이너가 Running 상태인 경우
        if (Boolean.FALSE.equals(status.getReady())) {
            // Running 상태 확인
            if (status.getState() != null && status.getState().getRunning() != null) {
                log.debug("Container {} is running but not ready", status.getName());
                return true;
            }
        }

        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
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
                .faultType(FaultType.READINESS_PROBE_FAILED)
                .severity(Severity.MEDIUM)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s'의 Readiness Probe가 실패함",
                        status.getName()))
                .description("컨테이너가 실행 중이지만 Readiness Probe 검사에 실패하여 트래픽을 받을 수 없습니다. " +
                        "Probe 설정(경로, 포트, 응답 코드)을 확인하고 애플리케이션이 올바르게 응답하는지 확인해야 합니다.")
                .symptoms(Arrays.asList(
                        "Pod이 Running 상태이지만 Ready가 false",
                        "Service에서 트래픽을 받지 못함",
                        "Readiness Probe 검사 실패"
                ))
                .context(Map.of(
                        "containerName", status.getName(),
                        "ready", false,
                        "image", status.getImage() != null ? status.getImage() : "unknown",
                        "ownerKind", ownerKind,
                        "ownerName", ownerName
                ))
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.READINESS_PROBE_FAILED;
    }
}
