@echo off
echo =========================================
echo K8s Doctor - Test Resource Creation
echo =========================================
echo.

echo 1. Creating CrashLoopBackOff test pod...
kubectl apply -f pods/01-crashloop-pod.yaml

echo 2. Creating ImagePullBackOff test pod...
kubectl apply -f pods/02-imagepull-pod.yaml

echo 3. Creating Pending test pod...
kubectl apply -f pods/04-pending-pod.yaml

echo 4. Creating Unavailable Deployment test...
kubectl apply -f deployments/01-unavailable-deployment.yaml

echo.
echo Test resources created successfully!
echo.
echo Waiting 30 seconds for pod status changes...
timeout /t 30 /nobreak

echo.
echo Current pod status:
echo =========================================
kubectl get pods

echo.
echo =========================================
echo Run diagnostics in K8s Doctor Web UI:
echo 1. Go to http://localhost:8080
echo 2. Select cluster
echo 3. Click 'Diagnose Cluster' button
echo.
echo After testing, cleanup with:
echo   test\cleanup-test.bat
echo =========================================
pause
