package com.vibecoding.k8sdoctor.model;

/**
 * 장애 유형
 */
public enum FaultType {
    // Pod 장애
    CRASH_LOOP_BACK_OFF("CrashLoopBackOff", "컨테이너가 반복적으로 재시작됨", Severity.CRITICAL),
    IMAGE_PULL_BACK_OFF("ImagePullBackOff", "컨테이너 이미지를 가져올 수 없음", Severity.CRITICAL),
    OOM_KILLED("OOMKilled", "메모리 부족으로 컨테이너 종료됨", Severity.CRITICAL),
    PENDING("Pending", "Pod이 스케줄링되지 않음", Severity.HIGH),
    ERROR("Error", "일반적인 에러 상태", Severity.HIGH),

    // 컨테이너 생성 실패
    CREATE_CONTAINER_CONFIG_ERROR("CreateContainerConfigError", "ConfigMap/Secret 참조 오류로 컨테이너 생성 실패", Severity.CRITICAL),
    CREATE_CONTAINER_ERROR("CreateContainerError", "컨테이너 생성 실패 (command/args/volume 오류)", Severity.CRITICAL),

    // Probe 실패
    LIVENESS_PROBE_FAILED("LivenessProbeFailed", "Liveness Probe 실패", Severity.HIGH),
    READINESS_PROBE_FAILED("ReadinessProbeFailed", "Readiness Probe 실패", Severity.MEDIUM),
    STARTUP_PROBE_FAILED("StartupProbeFailed", "Startup Probe 실패", Severity.HIGH),

    // 삭제 관련
    TERMINATING_STUCK("TerminatingStuck", "Pod이 Terminating 상태에서 멈춤", Severity.HIGH),

    // 축출 관련
    EVICTED("Evicted", "노드 리소스 압박으로 Pod 축출됨", Severity.HIGH),

    // 리소스 관련
    // Note: CONFIG_ERROR는 CREATE_CONTAINER_CONFIG_ERROR와 중복 - 하위 호환성을 위해 유지
    CONFIG_ERROR("ConfigError", "ConfigMap/Secret 마운트 실패", Severity.HIGH),
    /** PVC 바인딩 실패 - PENDING 상태에서 PVC_BINDING 카테고리로도 감지됨 */
    PVC_ERROR("PVCError", "PersistentVolumeClaim 바인딩 실패", Severity.HIGH),
    NETWORK_ERROR("NetworkError", "네트워크 연결 실패", Severity.MEDIUM),
    VOLUME_MOUNT_ERROR("VolumeMountError", "볼륨 마운트 또는 권한 오류", Severity.HIGH),

    // 리소스 부족 - PENDING 상태에서 RESOURCE_SHORTAGE 카테고리로도 감지됨
    RESOURCE_QUOTA_EXCEEDED("ResourceQuotaExceeded", "리소스 쿼터 초과", Severity.HIGH),
    INSUFFICIENT_RESOURCES("InsufficientResources", "노드 리소스 부족", Severity.HIGH),

    // Node 장애
    NODE_NOT_READY("NodeNotReady", "노드가 Ready 상태가 아님", Severity.CRITICAL),
    NODE_PRESSURE("NodePressure", "노드에 리소스 압박", Severity.MEDIUM),

    // Deployment 장애
    DEPLOYMENT_UNAVAILABLE("DeploymentUnavailable", "Deployment의 Pod이 준비되지 않음", Severity.HIGH),

    // Job/CronJob 장애
    JOB_FAILED("JobFailed", "Job 실행 실패", Severity.HIGH),
    CRONJOB_FAILED("CronJobFailed", "CronJob 스케줄링 또는 실행 실패", Severity.HIGH),

    // 기타
    UNKNOWN("Unknown", "알 수 없는 장애", Severity.LOW);

    private final String code;
    private final String description;
    private final Severity severity;

    FaultType(String code, String description, Severity severity) {
        this.code = code;
        this.description = description;
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }
}
