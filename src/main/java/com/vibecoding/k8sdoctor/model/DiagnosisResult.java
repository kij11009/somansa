package com.vibecoding.k8sdoctor.model;

import java.util.List;

/**
 * AI 진단 결과
 */
public class DiagnosisResult {
    private FaultInfo fault;              // 원본 장애 정보
    private List<FaultInfo> relatedFaults; // 관련된 다른 장애들 (통합됨)

    // AI 분석 결과
    private String rootCause;             // 근본 원인
    private String diagnosis;             // 진단 내용
    private List<String> solutions;       // 해결 방법 (단계별)
    private List<String> preventions;     // 재발 방지 방법
    private String additionalInfo;        // 추가 정보

    public DiagnosisResult() {
    }

    public DiagnosisResult(FaultInfo fault, List<FaultInfo> relatedFaults, String rootCause,
                          String diagnosis, List<String> solutions, List<String> preventions, String additionalInfo) {
        this.fault = fault;
        this.relatedFaults = relatedFaults;
        this.rootCause = rootCause;
        this.diagnosis = diagnosis;
        this.solutions = solutions;
        this.preventions = preventions;
        this.additionalInfo = additionalInfo;
    }

    public static DiagnosisResultBuilder builder() {
        return new DiagnosisResultBuilder();
    }

    public FaultInfo getFault() {
        return fault;
    }

    public void setFault(FaultInfo fault) {
        this.fault = fault;
    }

    public List<FaultInfo> getRelatedFaults() {
        return relatedFaults;
    }

    public void setRelatedFaults(List<FaultInfo> relatedFaults) {
        this.relatedFaults = relatedFaults;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(String diagnosis) {
        this.diagnosis = diagnosis;
    }

    public List<String> getSolutions() {
        return solutions;
    }

    public void setSolutions(List<String> solutions) {
        this.solutions = solutions;
    }

    public List<String> getPreventions() {
        return preventions;
    }

    public void setPreventions(List<String> preventions) {
        this.preventions = preventions;
    }

    public String getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(String additionalInfo) {
        this.additionalInfo = additionalInfo;
    }

    public static class DiagnosisResultBuilder {
        private FaultInfo fault;
        private List<FaultInfo> relatedFaults;
        private String rootCause;
        private String diagnosis;
        private List<String> solutions;
        private List<String> preventions;
        private String additionalInfo;

        public DiagnosisResultBuilder fault(FaultInfo fault) {
            this.fault = fault;
            return this;
        }

        public DiagnosisResultBuilder relatedFaults(List<FaultInfo> relatedFaults) {
            this.relatedFaults = relatedFaults;
            return this;
        }

        public DiagnosisResultBuilder rootCause(String rootCause) {
            this.rootCause = rootCause;
            return this;
        }

        public DiagnosisResultBuilder diagnosis(String diagnosis) {
            this.diagnosis = diagnosis;
            return this;
        }

        public DiagnosisResultBuilder solutions(List<String> solutions) {
            this.solutions = solutions;
            return this;
        }

        public DiagnosisResultBuilder preventions(List<String> preventions) {
            this.preventions = preventions;
            return this;
        }

        public DiagnosisResultBuilder additionalInfo(String additionalInfo) {
            this.additionalInfo = additionalInfo;
            return this;
        }

        public DiagnosisResult build() {
            return new DiagnosisResult(fault, relatedFaults, rootCause, diagnosis, solutions, preventions, additionalInfo);
        }
    }
}
