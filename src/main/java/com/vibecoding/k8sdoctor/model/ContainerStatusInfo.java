package com.vibecoding.k8sdoctor.model;

import lombok.Builder;
import lombok.Data;

/**
 * 컨테이너 상태 정보 DTO
 */
@Data
@Builder
public class ContainerStatusInfo {
    private String name;
    private String image;
    private Boolean ready;
    private Integer restartCount;
    private String state;             // Running, Waiting, Terminated
    private String stateReason;       // CrashLoopBackOff, OOMKilled, etc.
    private String stateMessage;
    private Integer exitCode;
}
