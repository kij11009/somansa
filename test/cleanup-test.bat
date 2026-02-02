@echo off
echo =========================================
echo Cleaning up test resources...
echo =========================================

echo Deleting pods...
kubectl delete pod crashloop-test --ignore-not-found=true
kubectl delete pod imagepull-test --ignore-not-found=true
kubectl delete pod oom-test --ignore-not-found=true
kubectl delete pod pending-test --ignore-not-found=true
kubectl delete pod readiness-probe-test --ignore-not-found=true
kubectl delete pod liveness-probe-test --ignore-not-found=true

echo Deleting deployments...
kubectl delete deployment unavailable-deployment-test --ignore-not-found=true

echo.
echo Cleanup completed!
echo.
kubectl get pods
pause
