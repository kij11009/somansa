# 워크로드 리소스 테스트 파일 생성 완료

## ✅ 생성된 파일

### 📁 test/ 디렉토리
```
test/
├── README.md                  # 테스트 가이드
├── statefulset-test.yaml     # StatefulSet 테스트 (1개)
├── job-test.yaml              # Job 테스트 (4개)
└── cronjob-test.yaml          # CronJob 테스트 (6개)
```

---

## 📋 StatefulSet 테스트 (1개)

### `statefulset-test.yaml`

**web** - Nginx StatefulSet
- Replicas: 3
- Service: nginx-statefulset (Headless)
- PVC: 1Gi per pod
- Container: nginx:1.21
- Resources:
  - Requests: CPU 100m, Memory 128Mi
  - Limits: CPU 500m, Memory 512Mi

**특징**:
- ✅ 순차적 Pod 생성 (web-0 → web-1 → web-2)
- ✅ 각 Pod마다 별도 PVC (www-web-0, www-web-1, www-web-2)
- ✅ Headless Service로 안정적인 네트워크 ID

---

## 💼 Job 테스트 (4개)

### `job-test.yaml`

#### 1. **hello-job** - 성공하는 Job
- Completions: 1
- Image: busybox:1.36
- 동작: Hello World 출력 후 10초 sleep

#### 2. **failing-job** - 실패하는 Job
- Completions: 1
- Backoff Limit: 3
- 동작: 의도적으로 exit 1 (재시도 테스트)

#### 3. **parallel-job** - 병렬 실행 Job
- Completions: 5
- Parallelism: 2 (동시 2개 실행)
- 동작: 랜덤 시간 sleep (10-30초)

#### 4. **pi-calculation** - Pi 계산 Job
- Image: perl:5.34
- 동작: 2000자리 Pi 값 계산
- Resources: CPU 100m-500m, Memory 128Mi-256Mi

---

## ⏰ CronJob 테스트 (6개)

### `cronjob-test.yaml`

#### 1. **hello-every-minute**
- Schedule: `*/1 * * * *` (매 분)
- History Limit: Success 3, Failed 1
- 동작: Hello 메시지 출력

#### 2. **suspended-cronjob**
- Schedule: `*/5 * * * *` (5분마다)
- **Suspend: true** (일시 중지)
- 동작: Job 생성 안됨

#### 3. **hourly-backup**
- Schedule: `0 * * * *` (매시간)
- Concurrency: Forbid (중복 실행 금지)
- TTL: 3600초 (1시간 후 Job 삭제)
- 동작: 백업 시뮬레이션

#### 4. **daily-cleanup**
- Schedule: `0 2 * * *` (매일 새벽 2시)
- Concurrency: Replace
- History Limit: Success 7, Failed 3
- 동작: 정리 작업 시뮬레이션

#### 5. **weekly-report**
- Schedule: `0 9 * * 1` (매주 월요일 오전 9시)
- History Limit: Success 10, Failed 5
- 동작: 리포트 생성 시뮬레이션

#### 6. **monthly-stats**
- Schedule: `0 0 1 * *` (매달 1일 자정)
- History Limit: Success 12, Failed 6
- 동작: 월간 통계 시뮬레이션

---

## 🚀 사용법

### 1. 모든 리소스 배포
```bash
kubectl apply -f test/
```

### 2. K8s Doctor에서 확인
```bash
# 애플리케이션 실행
./gradlew bootRun

# 브라우저에서 http://localhost:8080 접속
```

### 3. 각 리소스 확인
- **Clusters** → 클러스터 선택
- **StatefulSets** → `web` 확인
  - Ready: 3/3
  - Pods: web-0, web-1, web-2
  - Volume Claims: www-web-0, www-web-1, www-web-2
- **Jobs** → 4개 Job 확인
  - hello-job: Complete ✅
  - failing-job: Failed ❌
  - parallel-job: 진행 중 또는 Complete
  - pi-calculation: Complete (로그에서 Pi 값 확인)
- **CronJobs** → 6개 CronJob 확인
  - hello-every-minute: 매 분마다 Job 생성
  - suspended-cronjob: Suspend Yes
  - 나머지: 스케줄에 따라 실행

---

## 🔍 테스트 포인트

### StatefulSet
- ✅ Pod 순차 생성 확인 (`kubectl get pods -w`)
- ✅ PVC 자동 생성 (`kubectl get pvc`)
- ✅ Detail 페이지에서 Volume Claim Templates 확인
- ✅ Container Spec (이미지, 포트, 리소스) 확인

### Job
- ✅ 성공/실패 상태 배지
- ✅ Completions 진행률 프로그레스 바
- ✅ Pod Logs 섹션에서 실행 결과 확인
- ✅ Container Spec에서 Command/Args 확인
- ✅ Events에서 재시도 횟수 확인

### CronJob
- ✅ Schedule (Cron 표현식) 표시
- ✅ Suspend 상태 확인 (⏸️/▶️)
- ✅ Last Schedule Time
- ✅ Job History (최근 실행된 Job 목록)
- ✅ Job Template Container Spec

---

## 🧹 정리

### 모든 리소스 삭제
```bash
kubectl delete -f test/
```

### StatefulSet PVC 수동 삭제
```bash
# PVC는 자동 삭제되지 않음
kubectl delete pvc www-web-0 www-web-1 www-web-2
```

### 완료된 Job 정리
```bash
# 성공한 Job 삭제
kubectl delete jobs --field-selector status.successful=1

# 실패한 Job 삭제
kubectl delete jobs --field-selector status.failed=1
```

---

## 📊 예상 결과

### StatefulSet
```
NAME   READY   AGE
web    3/3     2m
```

### Jobs
```
NAME              COMPLETIONS   DURATION   AGE
hello-job         1/1           15s        2m
failing-job       0/1           35s        2m   (3번 재시도 후 실패)
parallel-job      5/5           2m         2m
pi-calculation    1/1           25s        2m
```

### CronJobs
```
NAME                  SCHEDULE        SUSPEND   ACTIVE   LAST SCHEDULE
hello-every-minute    */1 * * * *     False     1        45s
suspended-cronjob     */5 * * * *     True      0        <none>
hourly-backup         0 * * * *       False     0        <none>
daily-cleanup         0 2 * * *       False     0        <none>
weekly-report         0 9 * * 1       False     0        <none>
monthly-stats         0 0 1 * *       False     0        <none>
```

---

## 💡 팁

### CronJob 즉시 실행
```bash
# 스케줄 기다리지 않고 바로 실행
kubectl create job --from=cronjob/hello-every-minute test-run
```

### StatefulSet 스케일링
```bash
# Replica 증가
kubectl scale statefulset web --replicas=5

# K8s Doctor에서 web-3, web-4 생성 확인
```

### Job 재실행
```bash
# Job은 삭제 후 재생성해야 함
kubectl delete job hello-job
kubectl apply -f test/job-test.yaml
```

---

## 📚 Cron 스케줄 참고

```
*/1 * * * *     매 분마다
*/5 * * * *     5분마다
0 * * * *       매시간 정각
0 2 * * *       매일 새벽 2시
0 9 * * 1       매주 월요일 오전 9시
0 0 1 * *       매달 1일 자정
```

더 많은 예제: https://crontab.guru/
