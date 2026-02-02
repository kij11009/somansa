package com.vibecoding.k8sdoctor.service;

import com.vibecoding.k8sdoctor.detector.FaultDetector;
import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.Severity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 장애 분류 서비스
 */
@Service
@RequiredArgsConstructor
public class FaultClassificationService {

    private static final Logger log = LoggerFactory.getLogger(FaultClassificationService.class);

    private final List<FaultDetector> detectors;

    /**
     * 리소스에서 장애를 탐지
     */
    public List<FaultInfo> detectFaults(String clusterId, String namespace, String resourceKind, Object resource) {
        log.debug("Detecting faults for {} in namespace {}", resourceKind, namespace);

        return detectors.stream()
                .filter(detector -> detector.canDetect(resourceKind))
                .flatMap(detector -> {
                    try {
                        return detector.detect(clusterId, namespace, resource).stream();
                    } catch (Exception e) {
                        log.warn("Fault detector {} failed: {}", detector.getClass().getSimpleName(), e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 심각도별로 장애를 그룹핑
     */
    public Map<Severity, List<FaultInfo>> groupBySeverity(List<FaultInfo> faults) {
        return faults.stream()
                .collect(Collectors.groupingBy(FaultInfo::getSeverity));
    }

    /**
     * 최소 심각도 이상의 장애만 필터링
     */
    public List<FaultInfo> filterBySeverity(List<FaultInfo> faults, Severity minSeverity) {
        return faults.stream()
                .filter(f -> f.getSeverity().ordinal() <= minSeverity.ordinal())
                .sorted((a, b) -> Integer.compare(a.getSeverity().ordinal(), b.getSeverity().ordinal()))
                .collect(Collectors.toList());
    }

    /**
     * 장애 통계 정보
     */
    public FaultStatistics getStatistics(List<FaultInfo> faults) {
        Map<Severity, Long> bySeverity = faults.stream()
                .collect(Collectors.groupingBy(FaultInfo::getSeverity, Collectors.counting()));

        return FaultStatistics.builder()
                .total(faults.size())
                .critical(bySeverity.getOrDefault(Severity.CRITICAL, 0L).intValue())
                .high(bySeverity.getOrDefault(Severity.HIGH, 0L).intValue())
                .medium(bySeverity.getOrDefault(Severity.MEDIUM, 0L).intValue())
                .low(bySeverity.getOrDefault(Severity.LOW, 0L).intValue())
                .build();
    }

    /**
     * 장애 통계 정보
     */
    public static class FaultStatistics {
        private int total;
        private int critical;
        private int high;
        private int medium;
        private int low;

        public FaultStatistics() {
        }

        public FaultStatistics(int total, int critical, int high, int medium, int low) {
            this.total = total;
            this.critical = critical;
            this.high = high;
            this.medium = medium;
            this.low = low;
        }

        public static FaultStatisticsBuilder builder() {
            return new FaultStatisticsBuilder();
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getCritical() {
            return critical;
        }

        public void setCritical(int critical) {
            this.critical = critical;
        }

        public int getHigh() {
            return high;
        }

        public void setHigh(int high) {
            this.high = high;
        }

        public int getMedium() {
            return medium;
        }

        public void setMedium(int medium) {
            this.medium = medium;
        }

        public int getLow() {
            return low;
        }

        public void setLow(int low) {
            this.low = low;
        }

        public static class FaultStatisticsBuilder {
            private int total;
            private int critical;
            private int high;
            private int medium;
            private int low;

            public FaultStatisticsBuilder total(int total) {
                this.total = total;
                return this;
            }

            public FaultStatisticsBuilder critical(int critical) {
                this.critical = critical;
                return this;
            }

            public FaultStatisticsBuilder high(int high) {
                this.high = high;
                return this;
            }

            public FaultStatisticsBuilder medium(int medium) {
                this.medium = medium;
                return this;
            }

            public FaultStatisticsBuilder low(int low) {
                this.low = low;
                return this;
            }

            public FaultStatistics build() {
                return new FaultStatistics(total, critical, high, medium, low);
            }
        }
    }
}
