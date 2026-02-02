package com.vibecoding.k8sdoctor.controller;

import com.vibecoding.k8sdoctor.model.ClusterConfig;
import com.vibecoding.k8sdoctor.model.ClusterInfo;
import com.vibecoding.k8sdoctor.service.ClusterService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 클러스터 관리 컨트롤러
 */
@Controller
@RequestMapping("/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private static final Logger log = LoggerFactory.getLogger(ClusterController.class);

    private final ClusterService clusterService;

    /**
     * 클러스터 목록
     */
    @GetMapping
    public String listClusters(Model model) {
        log.info("Listing clusters");

        // 페이지 로드 시 자동 갱신 (1분 이상 경과한 클러스터만)
        clusterService.refreshAllClustersIfNeeded();

        List<ClusterInfo> clusters = clusterService.listClusters();
        model.addAttribute("clusters", clusters);
        model.addAttribute("title", "Cluster Management");
        return "clusters/list";
    }

    /**
     * 클러스터 등록 페이지
     */
    @GetMapping("/new")
    public String newClusterForm(Model model) {
        model.addAttribute("title", "Register New Cluster");
        return "clusters/new";
    }

    /**
     * 클러스터 등록 처리
     */
    @PostMapping
    public String registerCluster(
        @RequestParam String name,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) String apiServerUrl,
        @RequestParam(required = false) String token,
        RedirectAttributes redirectAttributes
    ) {
        log.info("Registering new cluster: {}", name);

        try {
            // Validate required fields
            if (apiServerUrl == null || apiServerUrl.isBlank()) {
                throw new IllegalArgumentException("API Server URL is required");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Service Account Token is required");
            }

            // ClusterConfig 생성
            ClusterConfig config = ClusterConfig.builder()
                .name(name)
                .description(description)
                .apiServerUrl(apiServerUrl)
                .token(token)
                .build();

            // 클러스터 등록
            ClusterInfo clusterInfo = clusterService.registerCluster(config);

            redirectAttributes.addFlashAttribute("success",
                "클러스터가 성공적으로 등록되었습니다: " + clusterInfo.getName());

            return "redirect:/clusters";

        } catch (Exception e) {
            log.error("Failed to register cluster", e);
            redirectAttributes.addFlashAttribute("error",
                "클러스터 등록 실패: " + e.getMessage());
            return "redirect:/clusters/new";
        }
    }

    /**
     * 클러스터 상세 정보
     */
    @GetMapping("/{clusterId}")
    public String getClusterDetail(
        @PathVariable String clusterId,
        Model model
    ) {
        log.info("Getting cluster detail: {}", clusterId);

        // 페이지 로드 시 자동 갱신 (1분 이상 경과했으면)
        clusterService.refreshClusterIfNeeded(clusterId);

        ClusterInfo cluster = clusterService.getCluster(clusterId)
            .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));

        model.addAttribute("cluster", cluster);
        model.addAttribute("title", "Cluster Detail - " + cluster.getName());
        return "clusters/detail";
    }

    /**
     * 클러스터 삭제
     */
    @PostMapping("/{clusterId}/delete")
    public String deleteCluster(
        @PathVariable String clusterId,
        RedirectAttributes redirectAttributes
    ) {
        log.info("Deleting cluster: {}", clusterId);

        try {
            clusterService.deleteCluster(clusterId);
            redirectAttributes.addFlashAttribute("success", "클러스터가 삭제되었습니다.");
        } catch (Exception e) {
            log.error("Failed to delete cluster: {}", clusterId, e);
            redirectAttributes.addFlashAttribute("error", "클러스터 삭제 실패: " + e.getMessage());
        }

        return "redirect:/clusters";
    }

    /**
     * 클러스터 연결 테스트
     */
    @PostMapping("/{clusterId}/test")
    public String testConnection(
        @PathVariable String clusterId,
        RedirectAttributes redirectAttributes
    ) {
        log.info("Testing cluster connection: {}", clusterId);

        try {
            ClusterInfo cluster = clusterService.testConnection(clusterId);
            redirectAttributes.addFlashAttribute("success",
                "연결 테스트 성공: " + cluster.getName());
        } catch (Exception e) {
            log.error("Connection test failed: {}", clusterId, e);
            redirectAttributes.addFlashAttribute("error",
                "연결 테스트 실패: " + e.getMessage());
        }

        return "redirect:/clusters/" + clusterId;
    }

    /**
     * 클러스터 정보 갱신
     */
    @PostMapping("/{clusterId}/refresh")
    public String refreshCluster(
        @PathVariable String clusterId,
        RedirectAttributes redirectAttributes
    ) {
        log.info("Refreshing cluster info: {}", clusterId);

        try {
            clusterService.refreshClusterInfo(clusterId);
            redirectAttributes.addFlashAttribute("success", "클러스터 정보가 갱신되었습니다.");
        } catch (Exception e) {
            log.error("Failed to refresh cluster info: {}", clusterId, e);
            redirectAttributes.addFlashAttribute("error", "정보 갱신 실패: " + e.getMessage());
        }

        return "redirect:/clusters/" + clusterId;
    }
}
