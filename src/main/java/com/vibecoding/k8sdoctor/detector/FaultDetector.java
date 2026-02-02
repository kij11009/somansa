package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;

import java.util.List;

/**
 * 장애 탐지기 인터페이스
 */
public interface FaultDetector {
    /**
     * 이 탐지기가 해당 리소스를 탐지할 수 있는지 확인
     */
    boolean canDetect(String resourceKind);

    /**
     * 리소스에서 장애를 탐지
     */
    List<FaultInfo> detect(String clusterId, String namespace, Object resource);

    /**
     * 이 탐지기가 탐지하는 장애 유형
     */
    FaultType getFaultType();
}
