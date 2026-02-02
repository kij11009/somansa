package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Node NotReady 장애 탐지기
 */

@Component
public class NodeNotReadyDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(NodeNotReadyDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Node".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Node node = (Node) resource;

        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return Collections.emptyList();
        }

        boolean isReady = node.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

        if (!isReady) {
            return List.of(FaultInfo.builder()
                    .faultType(FaultType.NODE_NOT_READY)
                    .severity(Severity.CRITICAL)
                    .resourceKind("Node")
                    .resourceName(node.getMetadata().getName())
                    .summary("노드가 Ready 상태가 아님")
                    .description("노드가 정상적으로 작동하지 않아 Pod 스케줄링이 불가능합니다. " +
                            "노드 상태를 확인하고 필요시 재시작하거나 교체해야 합니다.")
                    .symptoms(extractNodeConditions(node))
                    .detectedAt(LocalDateTime.now())
                    .build());
        }

        return Collections.emptyList();
    }

    private List<String> extractNodeConditions(Node node) {
        return node.getStatus().getConditions().stream()
                .map(c -> String.format("%s: %s%s",
                        c.getType(),
                        c.getStatus(),
                        c.getReason() != null ? " (" + c.getReason() + ")" : ""))
                .collect(Collectors.toList());
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.NODE_NOT_READY;
    }
}
