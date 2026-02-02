package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * StatefulSet 가용성 장애 탐지기
 */
@Component
public class StatefulSetUnavailableDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(StatefulSetUnavailableDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "StatefulSet".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        StatefulSet statefulSet = (StatefulSet) resource;
        StatefulSetStatus status = statefulSet.getStatus();

        if (status == null) {
            return Collections.emptyList();
        }

        Integer desired = statefulSet.getSpec().getReplicas() != null ? statefulSet.getSpec().getReplicas() : 1;
        Integer ready = status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
        Integer current = status.getCurrentReplicas() != null ? status.getCurrentReplicas() : 0;
        Integer updated = status.getUpdatedReplicas() != null ? status.getUpdatedReplicas() : 0;

        List<String> symptoms = new ArrayList<>();
        symptoms.add(String.format("원하는 레플리카: %d", desired));
        symptoms.add(String.format("준비된 레플리카: %d", ready));
        symptoms.add(String.format("현재 레플리카: %d", current));
        symptoms.add(String.format("업데이트된 레플리카: %d", updated));

        // StatefulSet은 순서대로 시작되므로 준비되지 않은 Pod이 있으면 문제
        if (ready < desired) {
            String ordinalInfo = "";
            if (ready > 0) {
                ordinalInfo = String.format(" (Pod-0 ~ Pod-%d만 준비됨)", ready - 1);
            }

            return List.of(FaultInfo.builder()
                    .faultType(FaultType.DEPLOYMENT_UNAVAILABLE)
                    .severity(Severity.HIGH)
                    .resourceKind("StatefulSet")
                    .namespace(statefulSet.getMetadata().getNamespace())
                    .resourceName(statefulSet.getMetadata().getName())
                    .summary(String.format("StatefulSet의 일부 레플리카 준비 안 됨 (%d/%d)%s",
                            ready, desired, ordinalInfo))
                    .description("StatefulSet의 일부 Pod이 준비되지 않았습니다. " +
                            "순차적 시작 특성상 이전 Pod이 준비되지 않으면 다음 Pod이 시작되지 않습니다. " +
                            "PVC(영구 볼륨), 이미지, 리소스를 확인하세요.")
                    .symptoms(symptoms)
                    .context(Map.of(
                            "desired", desired,
                            "ready", ready,
                            "current", current,
                            "updated", updated
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
