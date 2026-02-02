# PRD Step 1: 프로젝트 개요 및 기술 스택 정의

## 1. 프로젝트 개요

### 1.1 프로젝트 명
**K8s Doctor** - AI 기반 Kubernetes 자동 장애 진단 시스템

### 1.2 목적
- **MTTR(Mean Time To Recovery) 감소**: 장애 원인을 신속하게 파악하여 복구 시간 단축
- **장애 원인 자동 분석**: 수동 로그 분석 없이 AI가 자동으로 원인 파악
- **초급자 대응 가능**: Kubernetes 경험이 적은 운영자도 장애 대응 가능
- **안전한 운영**: Read-only 방식으로 클러스터에 영향 없음

### 1.3 타겟 사용자
- DevOps Engineer
- SRE (Site Reliability Engineer)
- Backend Developer
- Kubernetes 초급 운영자

## 2. 기술 스택

### 2.1 Backend
- **Framework**: Spring Boot 3.2.x
- **Java Version**: Java 17
- **Build Tool**: Maven
- **Kubernetes Client**: Fabric8 Kubernetes Client 6.x

### 2.2 AI/ML
- **AI Provider**: OpenRouter API
- **Model**: modelkey.txt 파일에서 동적으로 로드
- **Fallback Models**:
  - Text: `arcee-ai/trinity-large-preview:free`
  - Vision: `nvidia/nemotron-nano-12b-v2-vl:free`

### 2.3 Frontend
- **Framework**: Thymeleaf
- **UI Library**: Bootstrap 5
- **Icons**: Bootstrap Icons
- **Chart**: Chart.js (선택적)

### 2.4 Configuration & Security
- **Configuration**: application.properties + .env
- **K8s Access**: ServiceAccount (RBAC 기반, Read-only)
- **Environment Variables**: spring-dotenv

### 2.5 Optional (확장)
- **Cache**: Caffeine Cache (인메모리)
- **Monitoring**: Spring Boot Actuator
- **Logging**: SLF4J + Logback

## 3. 시스템 아키텍처

### 3.1 High-Level Architecture
```
┌─────────────────────────────────────┐
│         Web Browser (UI)            │
└──────────────┬──────────────────────┘
               │ HTTP
               ▼
┌─────────────────────────────────────┐
│       Spring Boot Application       │
│  ┌──────────────────────────────┐   │
│  │     Web Controller Layer     │   │
│  └─────────────┬────────────────┘   │
│                │                     │
│  ┌─────────────┴────────────────┐   │
│  │      Service Layer           │   │
│  │  - K8s Diagnostic Service    │   │
│  │  - Fault Classification      │   │
│  │  - AI Analysis Service       │   │
│  └──────┬──────────────┬────────┘   │
└─────────┼──────────────┼────────────┘
          │              │
          ▼              ▼
┌──────────────┐  ┌──────────────┐
│ Kubernetes   │  │  OpenRouter  │
│  API Server  │  │     API      │
└──────────────┘  └──────────────┘
```

### 3.2 주요 컴포넌트
1. **Web Controller**: 사용자 요청 처리 및 뷰 렌더링
2. **K8s Diagnostic Service**: 클러스터 리소스 조회 및 상태 체크
3. **Fault Classification Service**: 장애 유형 자동 분류
4. **AI Analysis Service**: OpenRouter API 호출 및 분석 결과 파싱
5. **Report Generator**: 진단 리포트 생성

## 4. 핵심 기능 요구사항

### 4.1 자동 진단 대상
| 리소스 타입 | 진단 항목 |
|------------|----------|
| **Pod** | Status, RestartCount, Logs, Events, Resource Usage |
| **Deployment** | Replicas, Available, Conditions, Events |
| **Node** | Status, Capacity, Allocatable, Conditions, Taints |
| **Namespace** | ResourceQuota, LimitRange, Pod Count |

### 4.2 장애 유형 분류
| 장애 유형 | 탐지 방법 |
|----------|----------|
| **CrashLoopBackOff** | Pod Status Reason |
| **ImagePullBackOff** | Pod Status Reason |
| **OOMKilled** | Container Terminated Reason |
| **Pending** | Pod Phase + Events |
| **Probe Failure** | Liveness/Readiness Probe Events |
| **Network Error** | Service/Ingress Events |
| **Config Error** | ConfigMap/Secret Volume Mount Errors |
| **PVC Error** | PersistentVolumeClaim Events |
| **Resource Quota** | ResourceQuota Exceeded Events |

### 4.3 AI 분석 출력
1. **원인 분석**: 장애가 발생한 근본 원인
2. **재현 조건**: 동일한 장애가 재발할 수 있는 조건
3. **해결 가이드**: 단계별 해결 방법 (1-5단계)
4. **YAML 예제**: 수정된 매니페스트 파일
5. **예방 방법**: 향후 동일 장애 방지 방법

## 5. 비기능 요구사항

### 5.1 성능
- 단일 Pod 진단: 5초 이내
- Namespace 전체 진단: 30초 이내
- AI 분석 응답: 15초 이내 (타임아웃)

### 5.2 보안
- **RBAC**: Read-only ClusterRole 사용
- **API Key**: 환경 변수로 관리, 절대 로그 노출 금지
- **Input Validation**: 모든 사용자 입력 검증

### 5.3 안정성
- AI API 실패 시 기본 진단 결과 제공
- K8s API 타임아웃: 30초
- 에러 발생 시 사용자 친화적 메시지 표시

### 5.4 확장성
- 새로운 장애 유형 추가 용이 (Strategy Pattern)
- AI 모델 교체 가능 (modelkey.txt)
- 멀티클러스터 지원 가능한 구조

## 6. 제약사항

### 6.1 기술적 제약
- OpenRouter API Rate Limit 준수
- Kubernetes API Server 부하 최소화 (캐싱 활용)
- 로그 조회: 최근 100줄 제한

### 6.2 운영 제약
- **Read-only**: 클러스터 리소스 변경 불가
- **On-demand**: 실시간 모니터링 아님 (사용자 요청 시 진단)
- **Single Tenant**: 초기 버전은 단일 클러스터

## 7. 성공 지표

### 7.1 정량적 지표
- MTTR 30% 이상 감소
- 초급 운영자 장애 해결률 50% 향상
- AI 분석 정확도 80% 이상

### 7.2 정성적 지표
- 사용자 만족도
- 학습 효과 (YAML 예제 활용)
- 장애 대응 자신감 향상

## 8. 프로젝트 구조

```
k8s-doctor/
├── src/
│   ├── main/
│   │   ├── java/com/vibecoding/k8sdoctor/
│   │   │   ├── K8sDoctorApplication.java
│   │   │   ├── config/
│   │   │   │   ├── K8sClientConfig.java
│   │   │   │   └── OpenRouterConfig.java
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── model/
│   │   │   └── util/
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── templates/
│   │       └── static/
│   └── test/
├── .env
├── .env.example
├── .gitignore
├── modelkey.txt
├── pom.xml
└── README.md
```

## 9. 다음 단계

Step 2에서는 Kubernetes 클러스터 연결 및 리소스 조회 기능을 상세히 정의합니다.
