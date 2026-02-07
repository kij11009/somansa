package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.Pod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Terminating 상태에서 멈춘 Pod 탐지기
 *
 * 삭제 요청 후 오래 걸리는 Pod 감지:
 * - Finalizer가 실행 완료되지 않음
 * - Volume detach 실패 (CSI driver 문제)
 * - CNI 플러그인 네트워크 정리 실패
 * - Pod가 graceful shutdown 실패
 */
@Component
public class TerminatingStuckDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(TerminatingStuckDetector.class);

    // Terminating 상태가 이 시간(분) 이상 지속되면 stuck으로 간주
    private static final int STUCK_THRESHOLD_MINUTES = 5;

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;
        List<FaultInfo> faults = new ArrayList<>();

        // deletionTimestamp가 있으면 Terminating 상태
        if (pod.getMetadata().getDeletionTimestamp() == null) {
            return faults;
        }

        // 삭제 요청 시간 계산
        String deletionTimestamp = pod.getMetadata().getDeletionTimestamp();
        LocalDateTime deletionTime = LocalDateTime.parse(
            deletionTimestamp.replace("Z", "")
        );
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        Duration stuckDuration = Duration.between(deletionTime, now);

        // 5분 이상 Terminating 상태면 stuck으로 판단
        if (stuckDuration.toMinutes() >= STUCK_THRESHOLD_MINUTES) {
            faults.add(createFaultInfo(pod, stuckDuration));
        }

        return faults;
    }

    private FaultInfo createFaultInfo(Pod pod, Duration stuckDuration) {
        // Finalizer 확인
        List<String> finalizers = pod.getMetadata().getFinalizers();
        boolean hasFinalizers = finalizers != null && !finalizers.isEmpty();

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

        // 이슈 카테고리 분류
        String issueCategory = classifyIssue(pod, hasFinalizers, finalizers);

        // Context 구성
        Map<String, Object> context = new HashMap<>();
        context.put("stuckMinutes", stuckDuration.toMinutes());
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("issueCategory", issueCategory);
        context.put("hasFinalizers", hasFinalizers);
        if (hasFinalizers) {
            context.put("finalizers", String.join(", ", finalizers));
        }
        if (pod.getMetadata().getDeletionGracePeriodSeconds() != null) {
            context.put("gracePeriodSeconds", pod.getMetadata().getDeletionGracePeriodSeconds());
        }

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add(String.format("Pod이 %d분 이상 Terminating 상태", stuckDuration.toMinutes()));
        symptoms.add("kubectl delete로 삭제되지 않음");

        if (hasFinalizers) {
            symptoms.add("Finalizer가 완료되지 않음: " + String.join(", ", finalizers));
        }

        // Phase 확인 (Pod은 여전히 Running일 수 있음)
        if (pod.getStatus() != null && pod.getStatus().getPhase() != null) {
            symptoms.add("현재 Phase: " + pod.getStatus().getPhase());
        }

        String description = buildDescription(issueCategory, hasFinalizers, finalizers, stuckDuration);

        return FaultInfo.builder()
                .faultType(FaultType.TERMINATING_STUCK)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary(String.format("Pod이 %d분 이상 Terminating 상태에서 멈춤", stuckDuration.toMinutes()))
                .description(description)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 이슈 카테고리 분류
     */
    private String classifyIssue(Pod pod, boolean hasFinalizers, List<String> finalizers) {
        // Finalizer 기반 분류
        if (hasFinalizers) {
            String finalizerStr = String.join(" ", finalizers).toLowerCase();

            if (finalizerStr.contains("volume") || finalizerStr.contains("pv") ||
                finalizerStr.contains("storage") || finalizerStr.contains("csi")) {
                return "VOLUME_DETACH_STUCK";
            }
            if (finalizerStr.contains("cni") || finalizerStr.contains("network") ||
                finalizerStr.contains("calico") || finalizerStr.contains("flannel") ||
                finalizerStr.contains("weave")) {
                return "CNI_CLEANUP_STUCK";
            }
            if (finalizerStr.contains("kubernetes") || finalizerStr.contains("foreground")) {
                return "KUBERNETES_FINALIZER_STUCK";
            }
            return "CUSTOM_FINALIZER_STUCK";
        }

        // Pod 상태 기반 분류
        if (pod.getStatus() != null) {
            String phase = pod.getStatus().getPhase();
            if ("Running".equals(phase)) {
                return "GRACEFUL_SHUTDOWN_STUCK";
            }
        }

        return "TERMINATING_UNKNOWN";
    }

    /**
     * 상세 설명 생성
     */
    private String buildDescription(String category, boolean hasFinalizers,
                                   List<String> finalizers, Duration stuckDuration) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "VOLUME_DETACH_STUCK":
                desc.append("볼륨 분리(detach)가 완료되지 않아 Pod이 삭제되지 않습니다. ");
                desc.append("CSI 드라이버 또는 스토리지 시스템에 문제가 있을 수 있습니다.");
                break;
            case "CNI_CLEANUP_STUCK":
                desc.append("CNI 플러그인이 네트워크 리소스를 정리하지 못해 Pod이 삭제되지 않습니다. ");
                desc.append("CNI 플러그인(Calico/Flannel/Weave 등) 로그를 확인하세요.");
                break;
            case "KUBERNETES_FINALIZER_STUCK":
                desc.append("Kubernetes 내장 finalizer가 완료되지 않았습니다. ");
                desc.append("관련 컨트롤러 또는 종속 리소스 상태를 확인하세요.");
                break;
            case "CUSTOM_FINALIZER_STUCK":
                desc.append("커스텀 finalizer가 완료되지 않았습니다. ");
                desc.append("finalizer를 관리하는 컨트롤러/오퍼레이터 상태를 확인하세요.");
                break;
            case "GRACEFUL_SHUTDOWN_STUCK":
                desc.append("컨테이너가 SIGTERM 신호에 응답하지 않아 graceful shutdown이 진행되지 않습니다. ");
                desc.append("애플리케이션이 SIGTERM을 처리하도록 구현되어 있는지 확인하세요.");
                break;
            default:
                desc.append("알 수 없는 이유로 Pod 삭제가 완료되지 않습니다.");
        }

        if (hasFinalizers && finalizers != null) {
            desc.append("\n\nFinalizers: ").append(String.join(", ", finalizers));
        }

        desc.append("\n\n강제 삭제: kubectl delete pod POD_NAME -n NAMESPACE --force --grace-period=0");
        desc.append("\n⚠️ 주의: 강제 삭제는 데이터 손실이나 리소스 누수를 유발할 수 있습니다.");

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.TERMINATING_STUCK;
    }
}
