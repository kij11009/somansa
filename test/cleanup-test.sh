#!/bin/bash

# 테스트 리소스 정리 스크립트

echo "========================================="
echo "테스트 리소스 정리 중..."
echo "========================================="

echo "Pod 삭제..."
kubectl delete pod crashloop-test --ignore-not-found=true
kubectl delete pod imagepull-test --ignore-not-found=true
kubectl delete pod oom-test --ignore-not-found=true
kubectl delete pod pending-test --ignore-not-found=true
kubectl delete pod readiness-probe-test --ignore-not-found=true
kubectl delete pod liveness-probe-test --ignore-not-found=true

echo "Deployment 삭제..."
kubectl delete deployment unavailable-deployment-test --ignore-not-found=true

echo ""
echo "정리 완료!"
echo ""
kubectl get pods | grep test || echo "테스트 리소스가 모두 삭제되었습니다."
