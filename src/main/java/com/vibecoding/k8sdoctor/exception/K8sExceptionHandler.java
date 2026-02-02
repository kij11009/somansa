package com.vibecoding.k8sdoctor.exception;

import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Kubernetes 관련 예외 처리 핸들러
 */

@ControllerAdvice
public class K8sExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(K8sExceptionHandler.class);

    @ExceptionHandler(K8sResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleResourceNotFound(K8sResourceNotFoundException ex, Model model) {
        log.error("Resource not found: {}", ex.getMessage());
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("status", 404);
        model.addAttribute("title", "리소스를 찾을 수 없습니다");
        return "error/error";
    }

    @ExceptionHandler(K8sApiException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleK8sApiException(K8sApiException ex, Model model) {
        log.error("Kubernetes API error: {}", ex.getMessage(), ex);
        model.addAttribute("error", "Kubernetes API 호출 실패: " + ex.getMessage());
        model.addAttribute("status", 500);
        model.addAttribute("title", "API 호출 실패");
        return "error/error";
    }

    @ExceptionHandler(KubernetesClientException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String handleK8sClientException(KubernetesClientException ex, Model model) {
        log.error("Kubernetes client error: {}", ex.getMessage(), ex);

        String errorMessage;
        if (ex.getCode() == 401 || ex.getCode() == 403) {
            errorMessage = "Kubernetes 클러스터 접근 권한이 없습니다.";
        } else if (ex.getCode() == 404) {
            errorMessage = "요청한 리소스를 찾을 수 없습니다.";
        } else {
            errorMessage = "Kubernetes 클러스터에 연결할 수 없습니다: " + ex.getMessage();
        }

        model.addAttribute("error", errorMessage);
        model.addAttribute("status", ex.getCode() != 0 ? ex.getCode() : 503);
        model.addAttribute("title", "클러스터 연결 실패");
        return "error/error";
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request, Model model) {
        String uri = request.getRequestURI();

        // favicon.ico 요청은 조용히 무시
        if (uri != null && uri.contains("favicon.ico")) {
            return null; // 404 응답만 반환, 에러 페이지 렌더링 안 함
        }

        log.warn("Static resource not found: {}", uri);
        model.addAttribute("error", "요청한 리소스를 찾을 수 없습니다: " + uri);
        model.addAttribute("status", 404);
        model.addAttribute("title", "리소스를 찾을 수 없습니다");
        return "error/error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGenericException(Exception ex, HttpServletRequest request, Model model) {
        String uri = request.getRequestURI();

        // favicon.ico 요청은 조용히 무시
        if (uri != null && uri.contains("favicon.ico")) {
            return null;
        }

        log.error("Unexpected error: {}", ex.getMessage(), ex);
        model.addAttribute("error", "예기치 않은 오류가 발생했습니다: " + ex.getMessage());
        model.addAttribute("status", 500);
        model.addAttribute("title", "서버 오류");
        return "error/error";
    }
}
