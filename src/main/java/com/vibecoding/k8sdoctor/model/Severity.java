package com.vibecoding.k8sdoctor.model;

/**
 * 장애 심각도
 */
public enum Severity {
    CRITICAL("심각", "즉시 조치 필요", "#dc3545"),
    HIGH("높음", "빠른 조치 권장", "#fd7e14"),
    MEDIUM("중간", "모니터링 필요", "#ffc107"),
    LOW("낮음", "참고용", "#6c757d");

    private final String displayName;
    private final String description;
    private final String color;

    Severity(String displayName, String description, String color) {
        this.displayName = displayName;
        this.description = description;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getColor() {
        return color;
    }
}
