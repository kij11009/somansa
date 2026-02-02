package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Deployment 가용성 장애 탐지기
 */

@Component
public class DeploymentUnavailableDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(DeploymentUnavailableDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Deployment".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Deployment deployment = (Deployment) resource;
        DeploymentStatus status = deployment.getStatus();

        if (status == null) {
            return Collections.emptyList();
        }

        Integer desired = deployment.getSpec().getReplicas() != null ? deployment.getSpec().getReplicas() : 1;
        Integer available = status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;
        Integer ready = status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;

        if (available < desired) {
            return List.of(FaultInfo.builder()
                    .faultType(FaultType.DEPLOYMENT_UNAVAILABLE)
                    .severity(Severity.HIGH)
                    .resourceKind("Deployment")
                    .namespace(deployment.getMetadata().getNamespace())
                    .resourceName(deployment.getMetadata().getName())
                    .summary(String.format("Deployment의 가용 Pod 부족 (%d/%d)", available, desired))
                    .description("일부 Pod이 준비되지 않아 서비스가 정상적으로 작동하지 않을 수 있습니다. " +
                            "Pod 상태를 확인하여 문제를 해결해야 합니다.")
                    .symptoms(List.of(
                            String.format("원하는 레플리카: %d", desired),
                            String.format("가용한 레플리카: %d", available),
                            String.format("준비된 레플리카: %d", ready)
                    ))
                    .context(Map.of(
                            "desired", desired,
                            "available", available,
                            "ready", ready
                    ))
                    .detectedAt(LocalDateTime.now())
                    .build());
        }

        return Collections.emptyList();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.DEPLOYMENT_UNAVAILABLE;
    }
}
