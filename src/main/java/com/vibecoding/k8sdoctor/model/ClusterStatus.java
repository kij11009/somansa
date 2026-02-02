package com.vibecoding.k8sdoctor.model;

/**
 * 클러스터 연결 상태
 */
public enum ClusterStatus {
    CONNECTED,      // 연결됨
    DISCONNECTED,   // 연결 끊김
    ERROR,          // 에러
    CHECKING        // 확인 중
}
