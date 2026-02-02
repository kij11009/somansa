package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
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
 * OOMKilled 장애 탐지기
 */

@Component
public class OOMKilledDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(OOMKilledDetector.class);

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
            if (isOOMKilled(status)) {
                faults.add(createFaultInfo(pod, status));
            }
        }

        return faults;
    }

    private boolean isOOMKilled(ContainerStatus status) {
        if (status.getLastState() != null && status.getLastState().getTerminated() != null) {
            String reason = status.getLastState().getTerminated().getReason();
            return "OOMKilled".equals(reason);
        }
        return false;
    }

    private FaultInfo createFaultInfo(Pod pod, ContainerStatus status) {
        ContainerStateTerminated terminated = status.getLastState().getTerminated();
        int exitCode = terminated.getExitCode() != null ? terminated.getExitCode() : 0;
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

        return FaultInfo.builder()
                .faultType(FaultType.OOM_KILLED)
                .severity(Severity.CRITICAL)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("메모리 부족으로 컨테이너 종료됨: %s", status.getName()))
                .description("컨테이너가 메모리 제한을 초과하여 강제 종료되었습니다. " +
                        "메모리 limits을 증가시키거나 애플리케이션의 메모리 사용량을 최적화해야 합니다.")
                .symptoms(Arrays.asList(
                        "컨테이너가 갑자기 종료됨",
                        String.format("종료 코드: %d (OOMKilled)", exitCode),
                        String.format("재시작 횟수: %d회", restartCount),
                        "메모리 사용량이 제한에 도달함"
                ))
                .context(Map.of(
                        "containerName", status.getName(),
                        "exitCode", exitCode,
                        "restartCount", restartCount,
                        "ownerKind", ownerKind,
                        "ownerName", ownerName
                ))
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.OOM_KILLED;
    }
}
