package com.vibecoding.k8sdoctor.detector;

import com.vibecoding.k8sdoctor.model.FaultInfo;
import com.vibecoding.k8sdoctor.model.FaultType;
import com.vibecoding.k8sdoctor.model.Severity;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CronJob 실패 탐지기
 * - Suspended 상태 감지
 * - 마지막 스케줄 실행 실패 감지
 * - 너무 많은 active Job 감지
 */
@Component
public class CronJobFailedDetector implements FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(CronJobFailedDetector.class);

    @Override
    public boolean canDetect(String resourceKind) {
        return "CronJob".equals(resourceKind);
    }

    @Override
    public List<FaultInfo> detect(String clusterId, String namespace, Object resource) {
        CronJob cronJob = (CronJob) resource;
        List<FaultInfo> faults = new ArrayList<>();

        // Suspended 상태 체크
        if (Boolean.TRUE.equals(cronJob.getSpec().getSuspend())) {
            faults.add(createSuspendedFault(cronJob));
            return faults;
        }

        CronJobStatus status = cronJob.getStatus();
        if (status == null) {
            return faults;
        }

        // active Job이 너무 많은 경우 (concurrencyPolicy 위반 가능성)
        int activeCount = status.getActive() != null ? status.getActive().size() : 0;
        if (activeCount > 1 && "Forbid".equals(cronJob.getSpec().getConcurrencyPolicy())) {
            faults.add(createTooManyActiveFault(cronJob, activeCount));
        }

        // 마지막 스케줄 시간이 너무 오래된 경우 (스케줄 실패 가능성)
        if (isScheduleStale(cronJob)) {
            faults.add(createScheduleStaleFault(cronJob));
        }

        return faults;
    }

    private boolean isScheduleStale(CronJob cronJob) {
        CronJobStatus status = cronJob.getStatus();
        if (status == null || status.getLastScheduleTime() == null) {
            // 한번도 스케줄된 적 없으면, 생성 시간 기반으로 확인
            String creationTimestamp = cronJob.getMetadata().getCreationTimestamp();
            if (creationTimestamp != null) {
                try {
                    OffsetDateTime created = OffsetDateTime.parse(creationTimestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    // 생성 후 1시간 이상 지났는데 한번도 실행 안 됨
                    return created.plusHours(1).isBefore(OffsetDateTime.now());
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }

        try {
            String lastSchedule = status.getLastScheduleTime();
            OffsetDateTime lastRun = OffsetDateTime.parse(lastSchedule, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            // 마지막 실행이 24시간 이상 전이면 stale로 판단 (cron 스케줄에 따라 다를 수 있으나 경고 수준)
            return lastRun.plusHours(24).isBefore(OffsetDateTime.now());
        } catch (Exception e) {
            return false;
        }
    }

    private FaultInfo createSuspendedFault(CronJob cronJob) {
        String name = cronJob.getMetadata().getName();
        String ns = cronJob.getMetadata().getNamespace();
        String schedule = cronJob.getSpec().getSchedule();

        Map<String, Object> context = new HashMap<>();
        context.put("issueCategory", "SUSPENDED");
        context.put("schedule", schedule);
        context.put("ownerKind", "CronJob");
        context.put("ownerName", name);

        return FaultInfo.builder()
                .faultType(FaultType.CRONJOB_FAILED)
                .severity(Severity.MEDIUM)
                .resourceKind("CronJob")
                .namespace(ns)
                .resourceName(name)
                .summary(String.format("CronJob '%s' 일시중지됨 (schedule: %s)", name, schedule))
                .description("CronJob이 suspended 상태로 설정되어 스케줄된 작업이 실행되지 않습니다.")
                .symptoms(List.of(
                        "CronJob이 suspended=true 상태",
                        String.format("스케줄: %s", schedule),
                        "새로운 Job이 생성되지 않음"
                ))
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    private FaultInfo createTooManyActiveFault(CronJob cronJob, int activeCount) {
        String name = cronJob.getMetadata().getName();
        String ns = cronJob.getMetadata().getNamespace();

        Map<String, Object> context = new HashMap<>();
        context.put("issueCategory", "TOO_MANY_ACTIVE");
        context.put("activeCount", activeCount);
        context.put("concurrencyPolicy", cronJob.getSpec().getConcurrencyPolicy());
        context.put("schedule", cronJob.getSpec().getSchedule());
        context.put("ownerKind", "CronJob");
        context.put("ownerName", name);

        return FaultInfo.builder()
                .faultType(FaultType.CRONJOB_FAILED)
                .severity(Severity.HIGH)
                .resourceKind("CronJob")
                .namespace(ns)
                .resourceName(name)
                .summary(String.format("CronJob '%s' active Job %d개 동시 실행 중", name, activeCount))
                .description("CronJob에서 생성된 Job이 완료되지 않아 여러 개가 동시에 실행되고 있습니다.")
                .symptoms(List.of(
                        String.format("동시 실행 중인 Job: %d개", activeCount),
                        String.format("concurrencyPolicy: %s", cronJob.getSpec().getConcurrencyPolicy()),
                        "이전 Job이 완료되지 않고 누적 중"
                ))
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    private FaultInfo createScheduleStaleFault(CronJob cronJob) {
        String name = cronJob.getMetadata().getName();
        String ns = cronJob.getMetadata().getNamespace();
        String schedule = cronJob.getSpec().getSchedule();
        String lastSchedule = cronJob.getStatus() != null && cronJob.getStatus().getLastScheduleTime() != null ?
                cronJob.getStatus().getLastScheduleTime() : "없음";
        String lastSuccessful = cronJob.getStatus() != null && cronJob.getStatus().getLastSuccessfulTime() != null ?
                cronJob.getStatus().getLastSuccessfulTime() : "없음";

        Map<String, Object> context = new HashMap<>();
        context.put("issueCategory", "SCHEDULE_STALE");
        context.put("schedule", schedule);
        context.put("lastScheduleTime", lastSchedule);
        context.put("lastSuccessfulTime", lastSuccessful);
        context.put("ownerKind", "CronJob");
        context.put("ownerName", name);

        return FaultInfo.builder()
                .faultType(FaultType.CRONJOB_FAILED)
                .severity(Severity.MEDIUM)
                .resourceKind("CronJob")
                .namespace(ns)
                .resourceName(name)
                .summary(String.format("CronJob '%s' 스케줄 실행 지연 (마지막: %s)", name, lastSchedule))
                .description("CronJob의 마지막 스케줄 실행이 오래되었습니다. 스케줄 설정이나 클러스터 상태를 확인해야 합니다.")
                .symptoms(List.of(
                        String.format("스케줄: %s", schedule),
                        String.format("마지막 스케줄: %s", lastSchedule),
                        String.format("마지막 성공: %s", lastSuccessful)
                ))
                .context(context)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public FaultType getFaultType() {
        return FaultType.CRONJOB_FAILED;
    }
}
