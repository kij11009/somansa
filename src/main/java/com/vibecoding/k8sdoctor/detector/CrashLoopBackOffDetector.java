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
 * CrashLoopBackOff 장애 탐지기
 */

@Component
public class CrashLoopBackOffDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(CrashLoopBackOffDetector.class);

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
            if (isCrashLoopBackOff(status)) {
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    private boolean isCrashLoopBackOff(ContainerStatus status) {
        if (status.getState() != null && status.getState().getWaiting() != null) {
            String reason = status.getState().getWaiting().getReason();
            return "CrashLoopBackOff".equals(reason);
        }
        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
        int restartCount = status.getRestartCount() != null ? status.getRestartCount() : 0;

        // 마지막 종료 상태에서 exitCode 추출
        int exitCode = -1;
        String terminationReason = "";
        String terminationMessage = "";
        if (status.getLastState() != null && status.getLastState().getTerminated() != null) {
            var terminated = status.getLastState().getTerminated();
            exitCode = terminated.getExitCode() != null ? terminated.getExitCode() : -1;
            terminationReason = terminated.getReason() != null ? terminated.getReason() : "";
            terminationMessage = terminated.getMessage() != null ? terminated.getMessage() : "";
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

        // exitCode에 따른 구체적인 설명 추가
        String exitCodeDesc = getExitCodeDescription(exitCode);

        // context에 더 많은 정보 추가
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        context.put("containerName", status.getName());
        context.put("restartCount", restartCount);
        context.put("image", status.getImage() != null ? status.getImage() : "unknown");
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("exitCode", exitCode);
        if (!terminationReason.isEmpty()) {
            context.put("terminationReason", terminationReason);
        }
        if (!terminationMessage.isEmpty()) {
            context.put("terminationMessage", terminationMessage);
        }

        List<String> symptoms = new ArrayList<>(Arrays.asList(
                "컨테이너가 시작 후 즉시 종료됨",
                String.format("재시작 횟수: %d회", restartCount),
                "Pod이 Running 상태로 전환되지 않음"
        ));
        if (exitCode >= 0) {
            symptoms.add(String.format("종료 코드: %d (%s)", exitCode, exitCodeDesc));
        }

        return FaultInfo.builder()
                .faultType(FaultType.CRASH_LOOP_BACK_OFF)
                .severity(Severity.CRITICAL)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("컨테이너 '%s'가 반복적으로 재시작됨 (재시작 횟수: %d)",
                        status.getName(), restartCount))
                .description(String.format("컨테이너가 시작 후 즉시 종료되어 계속 재시작되고 있습니다. " +
                        "종료 코드: %d (%s). 애플리케이션 로그를 확인하세요.", exitCode, exitCodeDesc))
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Exit code에 대한 설명 반환
     */
    private String getExitCodeDescription(int exitCode) {
        switch (exitCode) {
            case 0: return "정상 종료";
            case 1: return "애플리케이션 오류";
            case 2: return "셸 오류";
            case 126: return "권한 없음 또는 실행 불가";
            case 127: return "명령어 없음";
            case 128: return "잘못된 종료 인자";
            case 130: return "Ctrl+C (SIGINT)";
            case 137: return "SIGKILL - 메모리 초과 또는 강제 종료";
            case 143: return "SIGTERM - 정상 종료 요청";
            case 255: return "종료 상태 범위 초과";
            default:
                if (exitCode > 128 && exitCode < 255) {
                    return String.format("시그널 %d로 종료", exitCode - 128);
                }
                return "알 수 없음";
        }
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.CRASH_LOOP_BACK_OFF;
    }
}
