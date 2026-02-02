package com.vibecoding.k8sdoctor.exception;

/**
 * Kubernetes API 호출 중 발생하는 예외
 */
public class K8sApiException extends RuntimeException {

    public K8sApiException(String message) {
        super(message);
    }

    public K8sApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
