# K8s Doctor

AI-powered Kubernetes Diagnostics Tool

## 개요

K8s Doctor는 Kubernetes 클러스터의 장애를 AI가 자동으로 진단하고 해결 가이드를 제공하는 도구입니다.

## 주요 기능

- 🔍 **자동 진단**: Pod, Deployment, StatefulSet, Job, CronJob, Node, Namespace 자동 진단
- 🤖 **AI 분석**: OpenRouter API를 활용한 장애 원인 분석 및 해결 가이드
- 🛡️ **안전한 운영**: Read-only 권한으로 클러스터에 영향 없음
- 👥 **초급자 친화적**: 쉬운 UI와 단계별 해결 가이드
- 📊 **워크로드 리소스 지원**: Deployments, StatefulSets, DaemonSets, Jobs, CronJobs

## 기술 스택

- **Backend**: Spring Boot 3.2, Java 17
- **Build Tool**: Gradle 8.11
- **Kubernetes**: Fabric8 Kubernetes Client 6.10.0
- **AI**: OpenRouter API
- **Frontend**: Thymeleaf, Bootstrap 5
- **Cache**: Caffeine Cache

## 시작하기

### 사전 요구사항

- Java 17 이상
- Gradle 8.11 이상 (또는 포함된 Gradle Wrapper 사용)
- OpenRouter API Key
- Kubernetes 클러스터 (선택사항, 없어도 실행 가능)

### 설치 및 실행

1. **저장소 클론**
```bash
git clone <repository-url>
cd k8s-doctor
```

2. **환경 변수 설정**
```bash
# .env 파일에 OpenRouter API 키 설정
OPENROUTER_API_KEY=sk-or-v1-your-api-key-here
```

3. **애플리케이션 실행** (빌드 자동 수행)
```bash
# Windows
gradlew.bat bootRun

# Linux/Mac
./gradlew bootRun
```

**중요**: .env 파일이 자동으로 로드되므로 별도의 환경변수 설정 없이 바로 실행 가능합니다!

4. **브라우저에서 접속**
```
http://localhost:8080
```

5. **클러스터 등록**

K8s Doctor는 Service Account Token 방식으로 모든 Kubernetes 클러스터를 지원합니다.

```bash
# 1. Service Account 생성
kubectl create serviceaccount k8s-doctor-readonly -n default

# 2. 권한 부여
kubectl apply -f k8s/k8s-doctor-clusterrole.yaml
kubectl create clusterrolebinding k8s-doctor-readonly-binding \
  --clusterrole=k8s-doctor-reader \
  --serviceaccount=default:k8s-doctor-readonly

# 3. 영구 토큰 생성 (만료 없음!)
kubectl apply -f k8s/k8s-doctor-token-secret.yaml

# 4. 토큰 추출 (PowerShell)
$token = kubectl get secret k8s-doctor-readonly-token -n default -o jsonpath='{.data.token}'
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($token))

# 5. API Server URL 확인
kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'
```

생성된 토큰과 API Server URL을 K8s Doctor UI의 "Register New Cluster"에서 입력하세요.

> 📖 상세 가이드: [setup/QUICK_START.md](setup/QUICK_START.md) | [setup/README.md](setup/README.md)

## 환경 변수

- `.env` 파일의 `OPENROUTER_API_KEY`: OpenRouter API 키 (필수)
- `modelkey.txt`: 사용할 AI 모델 지정 (기본값: google/gemma-3-27b-it:free)

**.env 파일 자동 로딩**: 애플리케이션 시작 시 자동으로 .env 파일을 읽어서 환경변수로 설정합니다.

## 개발 단계

- [x] **Step 1**: 프로젝트 개요 및 기술 스택 정의 ✅
- [x] **Step 2**: Kubernetes 클러스터 연결 및 리소스 조회 ✅
- [x] **Multi-Cluster**: 멀티 클러스터 지원 ✅
- [ ] **Step 3**: 장애 탐지 및 분류 엔진
- [ ] **Step 4**: AI 분석 엔진
- [ ] **Step 5**: 웹 UI 및 리포트 생성
- [ ] **Step 6**: 고급 멀티클러스터 기능 (DB 영속성, 모니터링)
- [ ] **Step 7**: 테스트, 모니터링, 배포

### 완료된 기능

#### Step 1: 프로젝트 기반
- ✅ Spring Boot 3.2.1 + Java 17 + Gradle 8.11
- ✅ OpenRouter API 통합
- ✅ Kubernetes Fabric8 Client
- ✅ Thymeleaf + Bootstrap 5 UI
- ✅ Caffeine 캐싱
- ✅ .env 파일 자동 로딩

#### Step 2: 리소스 조회
- ✅ K8s 리소스 조회 서비스 (Pod, Deployment, StatefulSet, DaemonSet, Job, CronJob, Node, Namespace)
- ✅ Pod 로그 및 이벤트 조회
- ✅ Job 로그 조회 (최근 실행 Pod)
- ✅ CronJob 히스토리 조회 (최근 10개 Job)
- ✅ 에러 처리 및 Exception Handler
- ✅ 캐싱 전략 (5분 TTL)
- ✅ RBAC 권한 설정 (Read-only)

#### Multi-Cluster 지원
- ✅ 클러스터 등록 (Service Account Token 방식 - 모든 K8s 클러스터 지원)
- ✅ 클러스터 목록 및 관리 (조회, 삭제, 연결 테스트)
- ✅ 클러스터별 리소스 브라우징
- ✅ 인메모리 클러스터 저장소
- ✅ 클러스터 상태 모니터링 (연결/에러)

## 프로젝트 구조

```
k8s-doctor/
├── src/
│   ├── main/
│   │   ├── java/com/vibecoding/k8sdoctor/
│   │   │   ├── K8sDoctorApplication.java
│   │   │   ├── config/         # 설정 (K8s, OpenRouter, Cache, Web)
│   │   │   ├── controller/     # 컨트롤러 (Home, Resource)
│   │   │   ├── service/        # 서비스 (K8sResourceService)
│   │   │   ├── model/          # DTO (ResourceInfo, PodDiagnosticInfo, etc.)
│   │   │   └── exception/      # 예외 처리
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── templates/      # Thymeleaf 템플릿
│   │       │   ├── index.html
│   │       │   ├── error/
│   │       │   └── resources/  # 리소스 조회 페이지
│   │       └── static/
│   └── test/
├── k8s/                    # Kubernetes RBAC 설정
│   └── rbac.yaml
├── prd/                    # PRD 문서
├── .env                    # 환경 변수 (git ignored)
├── .env.example            # 환경 변수 템플릿
├── modelkey.txt            # AI 모델 설정
├── build.gradle            # Gradle 빌드 설정
├── settings.gradle         # Gradle 설정
├── gradlew                 # Gradle Wrapper (Unix)
├── gradlew.bat             # Gradle Wrapper (Windows)
└── README.md
```

## 주요 엔드포인트

### 클러스터 관리
- `GET /clusters` - 클러스터 목록
- `GET /clusters/new` - 클러스터 등록 페이지
- `POST /clusters` - 클러스터 등록
- `GET /clusters/{id}` - 클러스터 상세
- `POST /clusters/{id}/test` - 클러스터 연결 테스트
- `POST /clusters/{id}/delete` - 클러스터 삭제

### 리소스 조회
- `GET /clusters/{id}/resources/namespaces` - 네임스페이스 목록
- `GET /clusters/{id}/resources/pods` - Pod 목록
- `GET /clusters/{id}/resources/pods/{namespace}/{name}` - Pod 상세 (로그, 이벤트)
- `GET /clusters/{id}/resources/deployments` - Deployment 목록
- `GET /clusters/{id}/resources/deployments/{namespace}/{name}` - Deployment 상세
- `GET /clusters/{id}/resources/statefulsets` - StatefulSet 목록 ⭐ NEW
- `GET /clusters/{id}/resources/statefulsets/{namespace}/{name}` - StatefulSet 상세 ⭐ NEW
- `GET /clusters/{id}/resources/daemonsets` - DaemonSet 목록
- `GET /clusters/{id}/resources/jobs` - Job 목록 ⭐ NEW
- `GET /clusters/{id}/resources/jobs/{namespace}/{name}` - Job 상세 (로그 포함) ⭐ NEW
- `GET /clusters/{id}/resources/cronjobs` - CronJob 목록 ⭐ NEW
- `GET /clusters/{id}/resources/cronjobs/{namespace}/{name}` - CronJob 상세 (Job 히스토리) ⭐ NEW
- `GET /clusters/{id}/resources/nodes` - Node 목록
- `GET /clusters/{id}/resources/nodes/{name}` - Node 상세

### 모니터링
- `GET /actuator/health` - 헬스체크
- `GET /actuator/info` - 애플리케이션 정보
- `GET /actuator/metrics` - 메트릭

## 라이선스

MIT License

## 문의

Issues 탭을 통해 버그 리포트나 기능 요청을 해주세요.
