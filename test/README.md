# K8s Doctor 워크로드 리소스 테스트

이 디렉토리는 K8s Doctor에서 워크로드 리소스(StatefulSet, Job, CronJob)를 테스트하기 위한 YAML 파일들을 포함합니다.

## 📋 포함된 테스트 파일

### 1. StatefulSet 테스트 (`statefulset-test.yaml`)
- **web**: Nginx StatefulSet (3 replicas)
- PVC를 사용한 영구 스토리지
- Headless Service 포함
- 리소스 제한 설정

### 2. Job 테스트 (`job-test.yaml`)
- **hello-job**: 성공하는 간단한 Job
- **failing-job**: 의도적으로 실패하는 Job (재시도 테스트)
- **parallel-job**: 병렬 실행 Job (5개 완료, 2개 동시 실행)
- **pi-calculation**: Pi 계산 Job (공식 K8s 예제)

### 3. CronJob 테스트 (`cronjob-test.yaml`)
- **hello-every-minute**: 매 분마다 실행
- **suspended-cronjob**: 일시 중지된 CronJob
- **hourly-backup**: 매시간 백업 시뮬레이션
- **daily-cleanup**: 매일 새벽 2시 정리 작업
- **weekly-report**: 매주 월요일 오전 9시 리포트
- **monthly-stats**: 매달 1일 통계 작업

## 🚀 빠른 시작

### 전체 리소스 배포
```bash
# 모든 테스트 리소스 한번에 배포
kubectl apply -f test/

# 또는 개별 배포
kubectl apply -f test/statefulset-test.yaml
kubectl apply -f test/job-test.yaml
kubectl apply -f test/cronjob-test.yaml
```

### K8s Doctor에서 확인
1. K8s Doctor 실행: `./gradlew bootRun`
2. 브라우저에서 http://localhost:8080 접속
3. 클러스터 선택
4. 각 리소스 타입 확인

## 🧹 정리

```bash
# 모든 테스트 리소스 삭제
kubectl delete -f test/

# StatefulSet PVC 수동 삭제
kubectl delete pvc www-web-0 www-web-1 www-web-2
```

상세한 사용법은 각 YAML 파일의 주석을 참고하세요.
