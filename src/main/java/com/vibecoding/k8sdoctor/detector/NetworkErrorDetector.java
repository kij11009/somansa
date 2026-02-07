package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 네트워크 관련 장애 탐지기
 *
 * 네트워크 문제 감지:
 * - Pod 네트워크 연결 실패 (ContainersNotReady + NetworkNotReady)
 * - DNS 해석 실패
 * - Service 연결 실패
 * - CNI 플러그인 오류
 */
@Component
public class NetworkErrorDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(NetworkErrorDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Pod".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Pod pod = (Pod) resource;
        List<FaultInfo> faults = new ArrayList<>();

        if (pod.getStatus() == null) {
            return faults;
        }

        // Pod 조건에서 네트워크 문제 감지
        if (pod.getStatus().getConditions() != null) {
            boolean networkNotReady = false;
            boolean containersNotReady = false;
            String networkMessage = "";

            for (PodCondition condition : pod.getStatus().getConditions()) {
                // ContainersNotReady 조건 확인
                if ("ContainersReady".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                    containersNotReady = true;
                    if (condition.getMessage() != null) {
                        String msg = condition.getMessage().toLowerCase();
                        // 네트워크 관련 키워드 확인
                        if (msg.contains("network") || msg.contains("cni") ||
                            msg.contains("dns") || msg.contains("sandbox")) {
                            networkNotReady = true;
                            networkMessage = condition.getMessage();
                        }
                    }
                }

                // PodNetworkNotReady 조건 (일부 CNI에서 사용)
                if (condition.getReason() != null) {
                    String reason = condition.getReason().toLowerCase();
                    if (reason.contains("networknotready") || reason.contains("cni") ||
                        reason.contains("sandboxcreate")) {
                        networkNotReady = true;
                        networkMessage = condition.getMessage() != null ? condition.getMessage() : reason;
                    }
                }
            }

            if (networkNotReady) {
                faults.add(createFaultInfo(pod, networkMessage));
            }
        }

        // ContainerStatus에서 네트워크 관련 Waiting 상태 확인
        if (pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getState() != null && status.getState().getWaiting() != null) {
                    String reason = status.getState().getWaiting().getReason();
                    String message = status.getState().getWaiting().getMessage();

                    if (reason != null && message != null) {
                        String lowerReason = reason.toLowerCase();
                        String lowerMessage = message.toLowerCase();

                        // 네트워크/샌드박스 관련 에러
                        if (lowerReason.contains("network") || lowerReason.contains("cni") ||
                            lowerReason.contains("sandbox") ||
                            lowerMessage.contains("network") || lowerMessage.contains("cni") ||
                            lowerMessage.contains("failed to create pod sandbox")) {
                            faults.add(createFaultInfo(pod, message));
                        }
                    }
                }
            }
        }

        return faults;
    }

    private FaultInfo createFaultInfo(Pod pod, String errorMessage) {
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
        String issueCategory = classifyNetworkError(errorMessage);

        // Context 구성
        Map<String, Object> context = new HashMap<>();
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("issueCategory", issueCategory);
        context.put("errorMessage", errorMessage != null ? errorMessage : "");

        // 증상 목록
        List<String> symptoms = new ArrayList<>();
        symptoms.add("Pod 네트워크 연결 실패");

        switch (issueCategory) {
            case "CNI_ERROR":
                symptoms.add("CNI 플러그인 오류");
                symptoms.add("Pod 샌드박스 생성 실패");
                break;
            case "DNS_ERROR":
                symptoms.add("DNS 해석 실패");
                symptoms.add("서비스 이름 조회 불가");
                break;
            case "SANDBOX_ERROR":
                symptoms.add("Pod 샌드박스 생성 실패");
                break;
            case "NETWORK_POLICY_BLOCKED":
                symptoms.add("NetworkPolicy에 의해 차단됨");
                break;
            default:
                symptoms.add("네트워크 구성 오류");
        }

        String description = buildDescription(issueCategory, errorMessage);

        return FaultInfo.builder()
                .faultType(FaultType.NETWORK_ERROR)
                .severity(Severity.HIGH)
                .resourceKind("Pod")
                .namespace(pod.getMetadata().getNamespace())
                .resourceName(pod.getMetadata().getName())
                .summary("Pod 네트워크 오류 발생")
                .description(description)
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 네트워크 에러 카테고리 분류
     */
    private String classifyNetworkError(String message) {
        if (message == null || message.isEmpty()) {
            return "NETWORK_UNKNOWN";
        }

        String lowerMessage = message.toLowerCase();

        if (lowerMessage.contains("cni") || lowerMessage.contains("calico") ||
            lowerMessage.contains("flannel") || lowerMessage.contains("weave") ||
            lowerMessage.contains("cilium")) {
            return "CNI_ERROR";
        }

        if (lowerMessage.contains("dns") || lowerMessage.contains("resolve") ||
            lowerMessage.contains("nslookup") || lowerMessage.contains("coredns")) {
            return "DNS_ERROR";
        }

        if (lowerMessage.contains("sandbox") || lowerMessage.contains("pod network")) {
            return "SANDBOX_ERROR";
        }

        if (lowerMessage.contains("networkpolicy") || lowerMessage.contains("denied") ||
            lowerMessage.contains("blocked")) {
            return "NETWORK_POLICY_BLOCKED";
        }

        if (lowerMessage.contains("kube-proxy") || lowerMessage.contains("iptables") ||
            lowerMessage.contains("ipvs")) {
            return "KUBE_PROXY_ERROR";
        }

        return "NETWORK_UNKNOWN";
    }

    /**
     * 상세 설명 생성
     */
    private String buildDescription(String category, String errorMessage) {
        StringBuilder desc = new StringBuilder();

        switch (category) {
            case "CNI_ERROR":
                desc.append("CNI(Container Network Interface) 플러그인 오류가 발생했습니다. ");
                desc.append("Calico, Flannel, Weave 등 CNI 플러그인이 정상 동작하는지 확인하세요.");
                break;
            case "DNS_ERROR":
                desc.append("DNS 해석에 실패했습니다. ");
                desc.append("CoreDNS/kube-dns Pod이 정상 동작하는지 확인하세요.");
                break;
            case "SANDBOX_ERROR":
                desc.append("Pod 샌드박스(네트워크 네임스페이스) 생성에 실패했습니다. ");
                desc.append("CNI 플러그인 상태와 노드의 네트워크 설정을 확인하세요.");
                break;
            case "NETWORK_POLICY_BLOCKED":
                desc.append("NetworkPolicy에 의해 네트워크 트래픽이 차단되었습니다. ");
                desc.append("해당 네임스페이스의 NetworkPolicy 규칙을 확인하세요.");
                break;
            case "KUBE_PROXY_ERROR":
                desc.append("kube-proxy 또는 Service 라우팅에 문제가 있습니다. ");
                desc.append("kube-proxy Pod 상태와 iptables/ipvs 규칙을 확인하세요.");
                break;
            default:
                desc.append("Pod의 네트워크 연결에 문제가 발생했습니다.");
        }

        if (errorMessage != null && !errorMessage.isEmpty()) {
            desc.append("\n\n원본 에러: ").append(errorMessage);
        }

        return desc.toString();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.NETWORK_ERROR;
    }
}
