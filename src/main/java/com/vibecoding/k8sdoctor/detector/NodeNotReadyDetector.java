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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Node 장애 탐지기
 *
 * Node 관련 장애 감지:
 * - Node NotReady 상태
 * - Node Pressure 상태 (MemoryPressure, DiskPressure, PIDPressure)
 */
@Component
public class NodeNotReadyDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(NodeNotReadyDetector.class);

    // Pressure 관련 조건들
    private static final Set<String> PRESSURE_CONDITIONS = Set.of(
        "MemoryPressure", "DiskPressure", "PIDPressure", "NetworkUnavailable"
    );

    @Override
    public boolean canDetect(String resourceKind) {
        return "Node".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Node node = (Node) resource;
        List<FaultInfo> faults = new ArrayList<>();

        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return faults;
        }

        boolean isReady = false;
        List<NodeCondition> pressureConditions = new ArrayList<>();

        for (NodeCondition condition : node.getStatus().getConditions()) {
            // Ready 상태 확인
            if ("Ready".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                isReady = true;
            }

            // Pressure 조건 확인 (True인 경우 문제)
            if (PRESSURE_CONDITIONS.contains(condition.getType()) && "True".equals(condition.getStatus())) {
                pressureConditions.add(condition);
            }
        }

        // NotReady 상태 감지
        if (!isReady) {
            faults.add(createNotReadyFault(node));
        }

        // Pressure 상태 감지 (Ready이더라도 Pressure가 있으면 감지)
        if (!pressureConditions.isEmpty()) {
            faults.add(createPressureFault(node, pressureConditions));
        }

        return faults;
    }

    private FaultInfo createNotReadyFault(Node node) {
        return FaultInfo.builder()
                .faultType(FaultType.NODE_NOT_READY)
                .severity(Severity.CRITICAL)
                .resourceKind("Node")
                .resourceName(node.getMetadata().getName())
                .summary("노드가 Ready 상태가 아님")
                .description("노드가 정상적으로 작동하지 않아 Pod 스케줄링이 불가능합니다. " +
                        "kubelet 상태, 컨테이너 런타임, 네트워크 연결을 확인하세요.")
                .symptoms(extractNodeConditions(node))
                .context(Map.of(
                    "nodeName", node.getMetadata().getName(),
                    "issueCategory", "NODE_NOT_READY"
                ))
                .detectedAt(LocalDateTime.now())
                .build();
    }

    private FaultInfo createPressureFault(Node node, List<NodeCondition> pressureConditions) {
        List<String> symptoms = new ArrayList<>();
        StringBuilder descBuilder = new StringBuilder();
        descBuilder.append("노드에 리소스 압박이 발생했습니다.\n\n");

        String issueCategory = "NODE_PRESSURE";

        for (NodeCondition condition : pressureConditions) {
            String type = condition.getType();
            symptoms.add(type + ": True");

            switch (type) {
                case "MemoryPressure":
                    descBuilder.append("• 메모리 압박: 노드의 가용 메모리가 임계값 이하입니다.\n");
                    issueCategory = "MEMORY_PRESSURE";
                    break;
                case "DiskPressure":
                    descBuilder.append("• 디스크 압박: 노드의 디스크 공간이 부족합니다.\n");
                    issueCategory = "DISK_PRESSURE";
                    break;
                case "PIDPressure":
                    descBuilder.append("• PID 압박: 노드의 프로세스 수가 제한에 근접했습니다.\n");
                    issueCategory = "PID_PRESSURE";
                    break;
                case "NetworkUnavailable":
                    descBuilder.append("• 네트워크 사용 불가: 노드의 네트워크가 올바르게 구성되지 않았습니다.\n");
                    issueCategory = "NETWORK_UNAVAILABLE";
                    break;
            }

            if (condition.getMessage() != null) {
                symptoms.add("  원인: " + condition.getMessage());
            }
        }

        descBuilder.append("\n이 상태에서는 새 Pod 스케줄링이 제한되고, 기존 Pod이 축출될 수 있습니다.");

        return FaultInfo.builder()
                .faultType(FaultType.NODE_PRESSURE)
                .severity(Severity.HIGH)
                .resourceKind("Node")
                .resourceName(node.getMetadata().getName())
                .summary("노드에 리소스 압박 발생")
                .description(descBuilder.toString())
                .symptoms(symptoms)
                .context(Map.of(
                    "nodeName", node.getMetadata().getName(),
                    "issueCategory", issueCategory,
                    "pressureTypes", pressureConditions.stream()
                        .map(NodeCondition::getType)
                        .collect(Collectors.joining(", "))
                ))
                .detectedAt(LocalDateTime.now())
                .build();
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
