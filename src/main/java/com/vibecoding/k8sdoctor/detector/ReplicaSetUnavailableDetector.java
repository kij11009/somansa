package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ReplicaSet 가용성 장애 탐지기
 * 주의: 대부분의 ReplicaSet은 Deployment에 의해 관리되므로,
 *       Deployment 레벨에서 문제를 표시하는 것이 더 적절함
 */
@Component
public class ReplicaSetUnavailableDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSetUnavailableDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "ReplicaSet".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        ReplicaSet replicaSet = (ReplicaSet) resource;
        ReplicaSetStatus status = replicaSet.getStatus();

        if (status == null) {
            return Collections.emptyList();
        }

        // Owner 확인 - Deployment가 소유한 ReplicaSet은 스킵
        // (Deployment 레벨에서 처리됨)
        if (replicaSet.getMetadata().getOwnerReferences() != null &&
            !replicaSet.getMetadata().getOwnerReferences().isEmpty()) {
            String ownerKind = replicaSet.getMetadata().getOwnerReferences().get(0).getKind();
            if ("Deployment".equals(ownerKind)) {
                log.debug("Skipping ReplicaSet {} - owned by Deployment", replicaSet.getMetadata().getName());
                return Collections.emptyList();
            }
        }

        Integer desired = replicaSet.getSpec().getReplicas() != null ? replicaSet.getSpec().getReplicas() : 0;
        Integer ready = status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
        Integer available = status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;

        if (ready < desired) {
            return List.of(FaultInfo.builder()
                    .faultType(FaultType.DEPLOYMENT_UNAVAILABLE)
                    .severity(Severity.HIGH)
                    .resourceKind("ReplicaSet")
                    .namespace(replicaSet.getMetadata().getNamespace())
                    .resourceName(replicaSet.getMetadata().getName())
                    .summary(String.format("ReplicaSet의 가용 Pod 부족 (%d/%d)", ready, desired))
                    .description("일부 Pod이 준비되지 않았습니다. Pod 상태를 확인하여 문제를 해결하세요.")
                    .symptoms(List.of(
                            String.format("원하는 레플리카: %d", desired),
                            String.format("준비된 레플리카: %d", ready),
                            String.format("가용한 레플리카: %d", available)
                    ))
                    .context(Map.of(
                            "desired", desired,
                            "ready", ready,
                            "available", available
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
