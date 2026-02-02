package com.vibecoding.k8sdoctor.controller;

import com.vibecoding.k8sdoctor.model.ClusterInfo;
import com.vibecoding.k8sdoctor.model.ClusterStatus;
import com.vibecoding.k8sdoctor.service.ClusterService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


@Controller
@RequiredArgsConstructor
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    private final ClusterService clusterService;

    @GetMapping("/")
    public String home(Model model) {
        log.info("Dashboard requested");

        // Get all clusters
        List<ClusterInfo> clusters = clusterService.listClusters();

        // Calculate statistics
        int totalClusters = clusters.size();
        int healthyClusters = (int) clusters.stream()
                .filter(c -> c.getStatus() == ClusterStatus.CONNECTED)
                .count();
        int totalPods = clusters.stream()
                .mapToInt(ClusterInfo::getPodCount)
                .sum();
        int totalNodes = clusters.stream()
                .mapToInt(ClusterInfo::getNodeCount)
                .sum();

        // Add to model
        model.addAttribute("title", "Dashboard - K8s Doctor");
        model.addAttribute("clusters", clusters);
        model.addAttribute("totalClusters", totalClusters);
        model.addAttribute("healthyClusters", healthyClusters);
        model.addAttribute("totalPods", totalPods);
        model.addAttribute("totalNodes", totalNodes);
        model.addAttribute("totalFaults", 0); // Will be populated when diagnostics run

        return "index";
    }
}
