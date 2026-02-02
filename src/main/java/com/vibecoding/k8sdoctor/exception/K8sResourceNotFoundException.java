package com.vibecoding.k8sdoctor.exception;

/**
 * Kubernetes 리소스를 찾을 수 없을 때 발생하는 예외
 */
public class K8sResourceNotFoundException extends RuntimeException {

    public K8sResourceNotFoundException(String message) {
        super(message);
    }

    public K8sResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
