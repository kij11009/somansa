package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Job 실패 탐지기
 * - Job의 conditions에서 Failed 확인
 * - backoffLimitExceeded, DeadlineExceeded 등 감지
 * - 완료되지 않은 Job 중 실패한 Pod가 있는 경우 감지
 */
@Component
public class JobFailedDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(JobFailedDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "Job".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        Job job = (Job) resource;
        List<FaultInfo> faults = new ArrayList<>();

        JobStatus status = job.getStatus();
        if (status == null) {
            return faults;
        }

        // 이미 성공 완료된 Job은 건너뜀
        if (isCompleted(status)) {
            return faults;
        }

        // Failed condition 확인
        String failureReason = "";
        String failureMessage = "";
        boolean hasFailed = false;

        if (status.getConditions() != null) {
            for (JobCondition condition : status.getConditions()) {
                if ("Failed".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                    hasFailed = true;
                    failureReason = condition.getReason() != null ? condition.getReason() : "";
                    failureMessage = condition.getMessage() != null ? condition.getMessage() : "";
                    break;
                }
            }
        }

        // Failed condition이 없어도, failed pod가 있으면 감지
        int failedCount = status.getFailed() != null ? status.getFailed() : 0;
        int succeededCount = status.getSucceeded() != null ? status.getSucceeded() : 0;

        if (hasFailed || failedCount > 0) {
            faults.add(createFaultInfo(job, failureReason, failureMessage, failedCount, succeededCount));
        }

        return faults;
    }

    private boolean isCompleted(JobStatus status) {
        if (status.getConditions() != null) {
            for (JobCondition condition : status.getConditions()) {
                if ("Complete".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                    return true;
                }
            }
        }
        return false;
    }

    private FaultInfo createFaultInfo(Job job, String failureReason, String failureMessage,
                                       int failedCount, int succeededCount) {
        String jobName = job.getMetadata().getName();
        String ns = job.getMetadata().getNamespace();

        // Job spec에서 정보 추출
        Integer backoffLimit = job.getSpec().getBackoffLimit();
        Integer completions = job.getSpec().getCompletions();
        Integer parallelism = job.getSpec().getParallelism();
        Long activeDeadlineSeconds = job.getSpec().getActiveDeadlineSeconds();
        String restartPolicy = "";
        if (job.getSpec().getTemplate() != null && job.getSpec().getTemplate().getSpec() != null) {
            restartPolicy = job.getSpec().getTemplate().getSpec().getRestartPolicy() != null ?
                    job.getSpec().getTemplate().getSpec().getRestartPolicy() : "";
        }

        // CronJob owner 확인
        String ownerKind = "Job";
        String ownerName = jobName;
        if (job.getMetadata().getOwnerReferences() != null && !job.getMetadata().getOwnerReferences().isEmpty()) {
            var owner = job.getMetadata().getOwnerReferences().get(0);
            ownerKind = owner.getKind();
            ownerName = owner.getName();
        }

        // 이슈 카테고리 분류
        String issueCategory = classifyIssue(failureReason, failureMessage);

        // 컨테이너 이미지 추출
        String image = "";
        if (job.getSpec().getTemplate() != null && job.getSpec().getTemplate().getSpec() != null
                && job.getSpec().getTemplate().getSpec().getContainers() != null
                && !job.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            image = job.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
        }

        Map<String, Object> context = new HashMap<>();
        context.put("ownerKind", ownerKind);
        context.put("ownerName", ownerName);
        context.put("failedCount", failedCount);
        context.put("succeededCount", succeededCount);
        context.put("issueCategory", issueCategory);
        if (!image.isEmpty()) context.put("image", image);
        if (backoffLimit != null) context.put("backoffLimit", backoffLimit);
        if (completions != null) context.put("completions", completions);
        if (parallelism != null) context.put("parallelism", parallelism);
        if (activeDeadlineSeconds != null) context.put("activeDeadlineSeconds", activeDeadlineSeconds);
        if (!restartPolicy.isEmpty()) context.put("restartPolicy", restartPolicy);
        if (!failureReason.isEmpty()) context.put("failureReason", failureReason);
        if (!failureMessage.isEmpty()) context.put("failureMessage", failureMessage);

        String summary;
        if (!failureReason.isEmpty()) {
            summary = String.format("Job '%s' 실패 (%s) - 실패 %d회", jobName, failureReason, failedCount);
        } else {
            summary = String.format("Job '%s' 실패 - 실패 %d회", jobName, failedCount);
        }

        List<String> symptoms = new ArrayList<>();
        symptoms.add(String.format("실패한 Pod 수: %d", failedCount));
        if (succeededCount > 0) {
            symptoms.add(String.format("성공한 Pod 수: %d", succeededCount));
        }
        if (!failureReason.isEmpty()) {
            symptoms.add(String.format("실패 원인: %s", failureReason));
        }
        if (!failureMessage.isEmpty()) {
            symptoms.add(String.format("메시지: %s", failureMessage));
        }
        if (backoffLimit != null) {
            symptoms.add(String.format("backoffLimit: %d", backoffLimit));
        }

        return FaultInfo.builder()
                .faultType(FaultType.JOB_FAILED)
                .severity(Severity.HIGH)
                .resourceKind("Job")
                .namespace(ns)
                .resourceName(jobName)
                .summary(summary)
                .description("Job이 실행에 실패했습니다. Pod 로그를 확인하여 실패 원인을 파악해야 합니다.")
                .symptoms(symptoms)
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    private String classifyIssue(String reason, String message) {
        String lowerReason = reason.toLowerCase();
        String lowerMessage = message.toLowerCase();

        if (lowerReason.contains("backofflimitexceeded") || lowerMessage.contains("backoff limit")) {
            return "BACKOFF_LIMIT_EXCEEDED";
        }
        if (lowerReason.contains("deadlineexceeded") || lowerMessage.contains("deadline")) {
            return "DEADLINE_EXCEEDED";
        }
        if (lowerMessage.contains("oom") || lowerMessage.contains("memory")) {
            return "OOM";
        }
        if (lowerMessage.contains("image") || lowerMessage.contains("pull")) {
            return "IMAGE_ERROR";
        }
        return "EXECUTION_FAILED";
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.JOB_FAILED;
    }
}
