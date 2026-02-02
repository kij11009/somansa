package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DaemonSet 가용성 장애 탐지기
 */
@Component
public class DaemonSetUnavailableDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(DaemonSetUnavailableDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "DaemonSet".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        DaemonSet daemonSet = (DaemonSet) resource;
        DaemonSetStatus status = daemonSet.getStatus();

        if (status == null) {
            return Collections.emptyList();
        }

        Integer desired = status.getDesiredNumberScheduled() != null ? status.getDesiredNumberScheduled() : 0;
        Integer ready = status.getNumberReady() != null ? status.getNumberReady() : 0;
        Integer available = status.getNumberAvailable() != null ? status.getNumberAvailable() : 0;
        Integer misscheduled = status.getNumberMisscheduled() != null ? status.getNumberMisscheduled() : 0;

        List<String> symptoms = new ArrayList<>();
        symptoms.add(String.format("전체 노드: %d개", desired));
        symptoms.add(String.format("준비된 노드: %d개", ready));
        symptoms.add(String.format("가용한 노드: %d개", available));

        if (misscheduled > 0) {
            symptoms.add(String.format("잘못 스케줄된 노드: %d개", misscheduled));
        }

        // DaemonSet은 모든 노드(또는 선택된 노드)에서 실행되어야 함
        if (ready < desired) {
            return List.of(FaultInfo.builder()
                    .faultType(FaultType.DEPLOYMENT_UNAVAILABLE)
                    .severity(Severity.HIGH)
                    .resourceKind("DaemonSet")
                    .namespace(daemonSet.getMetadata().getNamespace())
                    .resourceName(daemonSet.getMetadata().getName())
                    .summary(String.format("DaemonSet이 일부 노드에서 실행 안 됨 (%d/%d 노드)", ready, desired))
                    .description("일부 노드에서 Pod이 실행되지 않거나 준비되지 않았습니다. " +
                            "노드 선택자(nodeSelector), 톨러레이션(toleration), 리소스 제약을 확인하세요.")
                    .symptoms(symptoms)
                    .context(Map.of(
                            "desired", desired,
                            "ready", ready,
                            "available", available,
                            "misscheduled", misscheduled
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
