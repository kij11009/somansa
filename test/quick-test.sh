#!/bin/bash

# K8s Doctor 빠른 테스트 스크립트

echo "========================================="
echo "K8s Doctor - 장애 진단 테스트"
echo "========================================="
echo ""

# 테스트 Pod 배포
echo "1. CrashLoopBackOff 테스트 Pod 생성..."
kubectl apply -f test/pods/01-crashloop-pod.yaml

echo "2. ImagePullBackOff 테스트 Pod 생성..."
kubectl apply -f test/pods/02-imagepull-pod.yaml

echo "3. Pending 테스트 Pod 생성..."
kubectl apply -f test/pods/04-pending-pod.yaml

echo "4. Deployment Unavailable 테스트 생성..."
kubectl apply -f test/deployments/01-unavailable-deployment.yaml

echo ""
echo "테스트 리소스 생성 완료!"
echo ""
echo "30초 대기 중... (Pod 상태가 변경되기를 기다립니다)"
sleep 30

echo ""
echo "현재 Pod 상태:"
echo "========================================="
kubectl get pods | grep -E 'NAME|test'

echo ""
echo "========================================="
echo "K8s Doctor 웹 UI에서 진단을 실행하세요:"
echo "1. http://localhost:8080 접속"
echo "2. 클러스터 선택"
echo "3. 'Diagnose Cluster' 버튼 클릭"
echo ""
echo "테스트 종료 후 정리:"
echo "  ./test/cleanup-test.sh"
echo "========================================="
