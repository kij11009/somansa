package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.Container;
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
 *
 * 주의: CrashLoopBackOff 상태인 경우 CrashLoopBackOffDetector가 처리하므로
 * 여기서는 Running 상태에서 재시작이 발생한 경우만 감지합니다.
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

        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return faults;
        }

        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            // CrashLoopBackOff 상태면 건너뜀 (CrashLoopBackOffDetector가 처리)
            if (isCrashLoopBackOff(status)) {
                continue;
            }

            // 해당 컨테이너에 Liveness Probe가 설정되어 있는지 확인
            boolean hasLivenessProbe = pod.getSpec().getContainers().stream()
                .filter(c -> c.getName().equals(status.getName()))
                .anyMatch(c -> c.getLivenessProbe() != null);

            if (!hasLivenessProbe) {
                continue;
            }

            if (isLivenessProbeFailure(status)) {
                log.info("Detected liveness probe failure for container: {} (restarts: {})",
                        status.getName(), status.getRestartCount());
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    /**
     * CrashLoopBackOff 상태인지 확인
     */
    private boolean isCrashLoopBackOff(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "CrashLoopBackOff".equals(reason);
        }
        return false;
    }

    /**
     * Liveness Probe 실패로 인한 재시작 감지
     * - 컨테이너가 Running 상태
     * - 재시작 횟수가 threshold 이상
     * - 마지막 종료 원인이 Liveness probe에 의한 것 (exit code 137 또는 reason이 관련된 경우)
     */
    private boolean isLivenessProbeFailure(ContainerStatus status) {
        // 현재 Running 상태여야 함
        if (status.getState() == null || status.getState().getRunning() == null) {
            return false;
        }

        // 재시작 횟수 확인
        Integer restartCount = status.getRestartCount();
        if (restartCount == null || restartCount < RESTART_THRESHOLD) {
            return false;
        }

        // 마지막 종료 상태 확인
        if (status.getLastState() != null && status.getLastState().getTerminated() != null) {
            var terminated = status.getLastState().getTerminated();
            Integer exitCode = terminated.getExitCode();
            String reason = terminated.getReason() != null ? terminated.getReason() : "";

            // OOMKilled이면 liveness probe 실패가 아님 → OOMKilledDetector가 처리
            if ("OOMKilled".equals(reason)) {
                return false;
            }

            // exit code 137 (SIGKILL) 또는 143 (SIGTERM) + OOMKilled 아님 → probe 실패
            if (exitCode != null && (exitCode == 137 || exitCode == 143)) {
                return true;
            }
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

        // Liveness Probe 설정값 추출
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("containerName", status.getName());
        context.put("restartCount", restartCount);
        context.put("image", status.getImage() != null ? status.getImage() : "unknown");
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);

        for (var container : pod.getSpec().getContainers()) {
            if (container.getName().equals(status.getName()) && container.getLivenessProbe() != null) {
                var probe = container.getLivenessProbe();
                if (probe.getFailureThreshold() != null) context.put("failureThreshold", probe.getFailureThreshold());
                if (probe.getPeriodSeconds() != null) context.put("periodSeconds", probe.getPeriodSeconds());
                if (probe.getTimeoutSeconds() != null) context.put("timeoutSeconds", probe.getTimeoutSeconds());
                if (probe.getInitialDelaySeconds() != null) context.put("initialDelaySeconds", probe.getInitialDelaySeconds());
                break;
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
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.LIVENESS_PROBE_FAILED;
    }
}
