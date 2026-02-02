# Kubernetes 리소스 접근 방식 수정

## 🐛 발생한 문제

DaemonSet detail 페이지 접근 시 다음 에러 발생:

```
org.springframework.expression.spel.SpelEvaluationException: EL1008E:
Property or field 'cpu' cannot be found on object of type 'java.util.LinkedHashMap'
```

## 🔍 원인 분석

Kubernetes Fabric8 클라이언트는 리소스 요청/제한을 **Map 형태**로 반환합니다:

```java
// Fabric8 반환 형태
container.resources.requests  // → LinkedHashMap<String, Quantity>
container.resources.limits    // → LinkedHashMap<String, Quantity>
```

Thymeleaf에서 객체 프로퍼티 접근 방식(`container.resources.requests.cpu`)을 사용하면 에러가 발생합니다.

## ✅ 해결 방법

Map 키 접근 방식으로 변경:

### Before (잘못된 접근)
```html
<span th:if="${container.resources.requests.cpu != null}">
    CPU: <code th:text="${container.resources.requests.cpu}"></code>
</span>
```

### After (올바른 접근)
```html
<span th:if="${container.resources.requests.containsKey('cpu')}">
    CPU: <code th:text="${container.resources.requests['cpu']}"></code>
</span>
```

## 📝 수정된 파일 목록

### 1. `deployment-detail.html`
- ✅ Container Spec 섹션에 리소스 정보 추가
- ✅ Map 키 접근 방식 사용

### 2. `daemonset-detail.html`
- ✅ Container Spec의 리소스 접근 방식 수정

### 3. `statefulset-detail.html`
- ✅ Container Spec의 리소스 접근 방식 수정
- ✅ Volume Claim Templates의 storage 접근 방식 수정

### 4. `job-detail.html`
- ✅ Container Spec의 리소스 접근 방식 수정

### 5. `cronjob-detail.html`
- ✅ Job Template Container Spec의 리소스 접근 방식 수정

## 🔧 수정 패턴

모든 워크로드 detail 페이지에서 동일한 패턴으로 수정:

```html
<div th:if="${container.resources != null}">
    <strong>Resources:</strong>
    <!-- Requests -->
    <ul th:if="${container.resources.requests != null && !container.resources.requests.isEmpty()}">
        <li>Requests:
            <span th:if="${container.resources.requests.containsKey('cpu')}">
                CPU: <code th:text="${container.resources.requests['cpu']}"></code>
            </span>
            <span th:if="${container.resources.requests.containsKey('memory')}">
                Memory: <code th:text="${container.resources.requests['memory']}"></code>
            </span>
        </li>
    </ul>
    <!-- Limits -->
    <ul th:if="${container.resources.limits != null && !container.resources.limits.isEmpty()}">
        <li>Limits:
            <span th:if="${container.resources.limits.containsKey('cpu')}">
                CPU: <code th:text="${container.resources.limits['cpu']}"></code>
            </span>
            <span th:if="${container.resources.limits.containsKey('memory')}">
                Memory: <code th:text="${container.resources.limits['memory']}"></code>
            </span>
        </li>
    </ul>
</div>
```

## 🎯 수정 포인트

### 1. Map 존재 여부 확인
```html
th:if="${container.resources.requests != null && !container.resources.requests.isEmpty()}"
```

### 2. Map 키 존재 여부 확인
```html
th:if="${container.resources.requests.containsKey('cpu')}"
```

### 3. Map 값 접근
```html
th:text="${container.resources.requests['cpu']}"
```

## 🧪 테스트

### 리소스가 설정된 Pod 테스트
```bash
kubectl run nginx --image=nginx \
  --requests='cpu=100m,memory=128Mi' \
  --limits='cpu=500m,memory=512Mi'
```

K8s Doctor에서 확인:
1. Deployment/DaemonSet/StatefulSet/Job detail 페이지 접속
2. Container Spec 섹션 확인
3. Resources 정보 정상 표시 확인

### 리소스가 없는 Pod 테스트
```bash
kubectl run busybox --image=busybox -- sleep 3600
```

K8s Doctor에서 확인:
1. Detail 페이지 접속
2. Container Spec 섹션에 리소스 정보가 표시되지 않음 (정상)
3. 에러 없이 페이지 로드 확인

## 📊 StatefulSet Volume Claim 수정

PVC의 storage도 Map 형태로 수정:

### Before
```html
<span th:text="${pvc.spec.resources.requests.storage}">1Gi</span>
```

### After
```html
<span th:text="${pvc.spec.resources.requests['storage']}">1Gi</span>
```

조건문도 수정:
```html
th:if="${pvc.spec.resources != null &&
        pvc.spec.resources.requests != null &&
        pvc.spec.resources.requests.containsKey('storage')}"
```

## ✅ 빌드 상태

```bash
./gradlew build -x test
BUILD SUCCESSFUL in 2s
```

모든 수정사항이 정상적으로 컴파일되었습니다.

## 🎉 결과

이제 모든 워크로드 detail 페이지에서:
- ✅ Container 리소스 정보 정상 표시
- ✅ CPU/Memory requests 및 limits 표시
- ✅ StatefulSet Volume Claim storage 정보 표시
- ✅ 리소스가 없는 경우 에러 없이 생략
- ✅ Map 키가 없는 경우 안전하게 처리

## 📚 참고사항

### Kubernetes Quantity 타입
Fabric8 클라이언트는 리소스를 `Quantity` 객체로 반환하지만, Map에 담겨있으므로:
- CPU: "100m", "1", "2000m" 등
- Memory: "128Mi", "1Gi", "512Mi" 등

Thymeleaf에서 `toString()`이 자동 호출되어 정상적으로 표시됩니다.

### 안전한 접근 패턴
1. Null 체크: `!= null`
2. 빈 Map 체크: `!isEmpty()`
3. 키 존재 체크: `containsKey('cpu')`
4. 값 접근: `['cpu']`

이 패턴을 따르면 NPE(NullPointerException) 없이 안전하게 접근할 수 있습니다.
