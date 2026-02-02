# Kubernetes Configuration Files

K8s Doctor 클러스터 등록에 필요한 Kubernetes YAML 파일들입니다.

## 📁 파일 목록

### k8s-doctor-clusterrole.yaml ⭐
K8s Doctor에 필요한 **read-only 권한**을 정의하는 사용자 정의 ClusterRole입니다.

**포함된 권한:**
- Core 리소스: nodes, namespaces, pods, services, events, configmaps 등
- Apps 리소스: deployments, replicasets, statefulsets, daemonsets
- Batch 리소스: jobs, cronjobs
- Networking: ingresses, networkpolicies
- Storage: storageclasses, persistentvolumes, persistentvolumeclaims

**사용법:**
```bash
kubectl apply -f k8s/k8s-doctor-clusterrole.yaml
```

**생성되는 ClusterRole 이름:** `k8s-doctor-reader`

---

### k8s-doctor-token-secret.yaml ⭐
Service Account용 **영구 토큰**을 생성하는 Secret입니다.

**특징:**
- 만료되지 않는 토큰 생성
- 한 번 생성하면 계속 사용 가능
- Kubernetes 1.24+ 에서 필요한 방식

**사용법:**
```bash
kubectl apply -f k8s/k8s-doctor-token-secret.yaml
```

**토큰 추출:**
```bash
# Base64 인코딩된 토큰 얻기
kubectl get secret k8s-doctor-readonly-token -n default -o jsonpath='{.data.token}'

# PowerShell에서 디코딩
$token = kubectl get secret k8s-doctor-readonly-token -n default -o jsonpath='{.data.token}'
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($token))

# Bash에서 디코딩
kubectl get secret k8s-doctor-readonly-token -n default -o jsonpath='{.data.token}' | base64 -d
```

---

## 🚀 전체 설정 순서

### 1. Service Account 생성
```bash
kubectl create serviceaccount k8s-doctor-readonly -n default
```

### 2. ClusterRole 적용
```bash
kubectl apply -f k8s/k8s-doctor-clusterrole.yaml
```

### 3. ClusterRoleBinding 생성
```bash
kubectl create clusterrolebinding k8s-doctor-readonly-binding \
  --clusterrole=k8s-doctor-reader \
  --serviceaccount=default:k8s-doctor-readonly
```

### 4. 영구 토큰 생성
```bash
kubectl apply -f k8s/k8s-doctor-token-secret.yaml
```

### 5. 토큰 추출
```powershell
# PowerShell
$token = kubectl get secret k8s-doctor-readonly-token -n default -o jsonpath='{.data.token}'
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($token))
```

### 6. API Server URL 확인
```bash
kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'
```

### 7. K8s Doctor에 등록
- http://localhost:8080
- "Clusters" → "Register New Cluster"
- API Server URL과 토큰 입력

---

## 📝 참고

- **빠른 시작 가이드**: [../setup/QUICK_START.md](../setup/QUICK_START.md)
- **상세 가이드**: [../setup/docs/service-account-setup.md](../setup/docs/service-account-setup.md)
- **Setup 가이드**: [../setup/README.md](../setup/README.md)

---

## ⚠️ 중요 사항

### 왜 사용자 정의 ClusterRole을 사용하나요?
기본 `view` ClusterRole은 일부 클라우드 프로바이더(특히 EKS)에서 **nodes를 조회할 권한이 없습니다**.
K8s Doctor는 클러스터 상태를 진단하기 위해 nodes 정보가 필요하므로, `k8s-doctor-reader` ClusterRole을 직접 정의했습니다.

### 보안
- **Read-only 권한만** 부여됩니다
- 클러스터의 어떤 리소스도 **수정/삭제할 수 없습니다**
- 안전하게 프로덕션 클러스터에 사용 가능합니다

### 모든 클러스터 지원
이 방식은 다음 모든 클러스터에서 동일하게 작동합니다:
- ✅ AWS EKS
- ✅ GCP GKE
- ✅ Azure AKS
- ✅ 바닐라 Kubernetes
- ✅ OpenShift
- ✅ 기타 모든 Kubernetes 호환 클러스터
