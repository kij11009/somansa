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
 * Startup Probe 실패 탐지기
 *
 * Startup Probe는 컨테이너 시작 시 한 번만 실행되며,
 * 실패하면 컨테이너가 재시작됩니다.
 * Liveness/Readiness Probe는 Startup Probe가 성공한 후에만 실행됩니다.
 */
@Component
public class StartupProbeFailureDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(StartupProbeFailureDetector.class);

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

        // Startup Probe 설정 확인
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return faults;
        }

        // 각 컨테이너별 Startup Probe 확인
        for (var container : pod.getSpec().getContainers()) {
            if (container.getStartupProbe() == null) {
                continue;
            }

            // 해당 컨테이너의 상태 찾기
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getName().equals(container.getName()) && isStartupProbeFailure(status)) {
                    faults.add(createFaultInfo(pod, status, container.getStartupProbe()));
                }
            }
        }

        return faults;
    }

    /**
     * Startup Probe 실패 여부 확인
     * - Started가 false이고 재시작 횟수가 있으면 Startup Probe 실패로 판단
     * - 또는 Waiting 상태에서 reason이 관련된 경우
     */
    private boolean isStartupProbeFailure(ContainerStatus status) {
        // started가 명시적으로 false인 경우 (Startup Probe 진행 중 또는 실패)
        if (status.getStarted() != null && !status.getStarted()) {
            // 재시작 횟수가 있으면 Startup Probe 실패로 인한 재시작
            if (status.getRestartCount() != null && status.getRestartCount() > 0) {
                return true;
            }
        }

        // Waiting 상태에서 ContainerCreating이 아니고, restart가 있는 경우
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            // CrashLoopBackOff 상태이고 started가 false인 경우
            if ("CrashLoopBackOff".equals(reason) &&
                status.getStarted() != null && !status.getStarted()) {
                return true;
            }
        }

        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status,
                                      io.fabric8.kubernetes.api.model.Probe startupProbe) {
        int restartCount = status.getRestartCount() != null ? status.getRestartCount() : 0;

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

        // Probe 설정 정보 추출
        Map<String, Object> context = new HashMap<>();
        context.put("containerName", status.getName());
        context.put("restartCount", restartCount);
        context.put("image", status.getImage() != null ? status.getImage() : "unknown");
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);

        if (startupProbe.getFailureThreshold() != null) {
            context.put("failureThreshold", startupProbe.getFailureThreshold());
        }
        if (startupProbe.getPeriodSeconds() != null) {
            context.put("periodSeconds", startupProbe.getPeriodSeconds());
        }
        if (startupProbe.getTimeoutSeconds() != null) {
            context.put("timeoutSeconds", startupProbe.getTimeoutSeconds());
        }

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add("컨테이너가 시작 단계에서 실패");
        symptoms.add(String.format("재시작 횟수: %d회", restartCount));
        symptoms.add("Startup Probe 검사 실패");
        symptoms.add("Liveness/Readiness Probe는 아직 실행되지 않음");

        return FaultInfo.builder()
                .faultType(FaultType.STARTUP_PROBE_FAILED)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s' Startup Probe 실패 (재시작: %d회)",
                        status.getName(), restartCount))
                .description("컨테이너가 Startup Probe 검사를 통과하지 못해 반복적으로 재시작되고 있습니다. " +
                        "애플리케이션 시작 시간이 길거나 시작 시 오류가 발생하고 있을 수 있습니다. " +
                        "failureThreshold와 periodSeconds를 늘려 충분한 시작 시간을 확보하세요.")
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.STARTUP_PROBE_FAILED;
    }
}
