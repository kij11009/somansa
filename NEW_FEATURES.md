# K8s Doctor - New Workload Resources Features

## 🎯 What's New

Three new workload resource types have been added to K8s Doctor:

### 1. StatefulSets 📊
**Purpose**: Manage stateful applications with persistent storage and ordered deployment

**List View**:
- Shows Ready/Total replicas (e.g., 3/3)
- Current and Updated replica counts
- Status badges (green = all ready, yellow = not ready)

**Detail View**:
- Service name and replica status
- Sequential pod list (pod-0, pod-1, pod-2...)
- Volume claim templates information
- Events and issues

**Access**:
- Cluster detail → "StatefulSets" button
- Namespaces → "StatefulSets" button per namespace
- URL: `/clusters/{id}/resources/statefulsets`

---

### 2. Jobs 💼
**Purpose**: Run one-time batch jobs to completion

**List View**:
- Completion status (e.g., 1/1 completed)
- Duration and timing
- Status badges:
  - ✅ Complete (green)
  - ❌ Failed (red)
  - ⟳ Running (blue)

**Detail View**:
- Completion progress bar
- Succeeded/Failed/Active pod counts
- Backoff limit (retry attempts)
- **Pod logs** (last 100 lines from most recent pod)
- Start and completion times
- Events

**Access**:
- Cluster detail → "Jobs" button
- Namespaces → "Jobs" button per namespace
- URL: `/clusters/{id}/resources/jobs`

---

### 3. CronJobs ⏰
**Purpose**: Schedule periodic jobs using cron expressions

**List View**:
- Cron schedule (e.g., `*/5 * * * *`)
- Suspend status (⏸️ Yes / ▶️ No)
- Active job count
- Last schedule time

**Detail View**:
- Schedule and suspend status
- Last schedule and last successful time
- Concurrency policy
- **Job history** (last 10 jobs created by this CronJob)
- Successful/Failed jobs history limits
- Events

**Access**:
- Cluster detail → "CronJobs" button
- Namespaces → "CronJobs" button per namespace
- URL: `/clusters/{id}/resources/cronjobs`

---

## 🖼️ UI Overview

### Cluster Detail Page - Quick Actions

```
┌─────────────────────────────────────────────────────────────┐
│  Quick Actions                                              │
├─────────────────────────────────────────────────────────────┤
│  [Namespaces]  [Pods]  [Deployments]  [StatefulSets]      │
│  [DaemonSets]  [Jobs]  [CronJobs]    [Nodes]               │
│  [Diagnose Cluster]                                         │
└─────────────────────────────────────────────────────────────┘
```

### Namespaces Page - Actions Per Namespace

```
┌─────────────────────────────────────────────────────────────┐
│ Name      Status   Created    Actions                       │
├─────────────────────────────────────────────────────────────┤
│ default   Active   2024-01-01 [Pods] [Deployments]         │
│                                [StatefulSets] [DaemonSets]  │
│                                [Jobs] [CronJobs]            │
└─────────────────────────────────────────────────────────────┘
```

---

## 📋 Example Use Cases

### StatefulSets
**Use for**: Databases (MySQL, PostgreSQL), Message queues (Kafka, RabbitMQ), Distributed systems (Elasticsearch, Cassandra)

**Example**:
```
Name: mysql-cluster
Ready: 3/3 ✅
Pods: mysql-cluster-0, mysql-cluster-1, mysql-cluster-2
```

### Jobs
**Use for**: Database migrations, Batch processing, Data exports, One-time tasks

**Example**:
```
Name: db-migration
Status: Complete ✅
Completions: 1/1
Duration: 2m30s
Logs: [Migration completed successfully]
```

### CronJobs
**Use for**: Scheduled backups, Periodic cleanup, Report generation, Health checks

**Example**:
```
Name: backup-daily
Schedule: 0 2 * * * (Daily at 2:00 AM)
Last Schedule: 2024-01-31 02:00:00
Job History:
  - backup-daily-28472734 ✅ Complete
  - backup-daily-28472733 ✅ Complete
  - backup-daily-28472732 ✅ Complete
```

---

## 🎨 Design Features

### Status Badges
- **Green** (bg-success): Running, Ready, Complete, Active
- **Yellow** (bg-warning): Warning, Not Ready, Pending, Suspended
- **Red** (bg-danger): Failed, Error
- **Blue** (bg-info): Running (Jobs), In Progress
- **Gray** (bg-secondary): Unknown, Inactive

### Icons
- **StatefulSets**: 📊 `bi-diagram-2` (connected nodes)
- **Jobs**: 💼 `bi-briefcase` (work/task)
- **CronJobs**: ⏰ `bi-clock-history` (scheduled time)

### Progress Visualization
Job completion progress bar:
```
Completions: 7/10
[████████████████░░░░░░░░] 70%
```

---

## 🚀 How to Use

### 1. Navigate to Cluster
Go to Clusters → Select your cluster

### 2. View Workload Resources
Click on any of the new Quick Action buttons:
- **StatefulSets** - View stateful applications
- **Jobs** - View one-time batch jobs
- **CronJobs** - View scheduled jobs

### 3. Filter by Namespace
Click on "Namespaces" → Select a namespace → Click resource type button

### 4. View Details
Click "Detail" button on any resource to see:
- Full status information
- Related pods
- Logs (for Jobs)
- Job history (for CronJobs)
- Events and issues

---

## 🧪 Testing Commands

### Create Test StatefulSet
```bash
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: web
spec:
  serviceName: "nginx"
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx
        ports:
        - containerPort: 80
EOF
```

### Create Test Job
```bash
kubectl create job hello --image=busybox -- echo "Hello World"
```

### Create Test CronJob
```bash
kubectl create cronjob hello --image=busybox \
  --schedule="*/1 * * * *" -- echo "Hello from CronJob"
```

### View Resources in K8s Doctor
1. Refresh your cluster
2. Navigate to the resource type
3. See your test resources appear in the list
4. Click "Detail" to see full information

---

## 📊 Resource Information Displayed

### StatefulSet Detail
- Namespace
- Ready/Total replicas
- Current replicas
- Updated replicas
- Service name
- Pod list (sequential naming)
- Events

### Job Detail
- Namespace
- Completions (succeeded/total)
- Active pods
- Failed attempts
- Backoff limit
- Start time
- Completion time
- **Pod logs** (recent output)
- Pod list
- Events

### CronJob Detail
- Namespace
- Cron schedule
- Suspend status
- Active jobs count
- Last schedule time
- Last successful time
- Concurrency policy
- History limits (successful/failed)
- **Job history** (last 10 jobs)
- Events

---

## 🔍 Troubleshooting Guide

### StatefulSets Not Ready?
**Check**:
- PVC binding status
- Pod events
- Storage class availability
- Sequential pod startup

### Jobs Failing?
**Check**:
- Pod logs (visible in detail view)
- Backoff limit reached
- Image pull errors in events
- Resource limits

### CronJobs Not Running?
**Check**:
- Suspend status (should be "No")
- Schedule syntax (cron expression)
- Last schedule time
- Job history for failures

---

## 🎯 Benefits

### Better Visibility
- See all workload types in one place
- Quick status overview
- Easy namespace filtering

### Faster Troubleshooting
- Direct access to logs (Jobs)
- Event history
- Status badges for quick identification

### Complete Workload Coverage
- Deployments ✅
- StatefulSets ✅ (NEW)
- DaemonSets ✅
- Jobs ✅ (NEW)
- CronJobs ✅ (NEW)
- ReplicaSets ✅

---

## 📝 Notes

### Read-Only Access
All operations are **read-only** following Kubernetes RBAC best practices. No modifications to cluster resources.

### Performance
- Lists cached for 5 minutes (Caffeine cache)
- Events limited to 20 most recent
- Job history limited to 10 most recent
- Logs limited to 100 lines

### Compatibility
- Requires Kubernetes 1.21+ (batch/v1 API)
- Works with all Kubernetes distributions
- Tested with Fabric8 client 6.10.0

---

## 🎓 Learning Resources

### Kubernetes Documentation
- [StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/)
- [CronJobs](https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/)

### Cron Schedule Examples
```
*/5 * * * *    - Every 5 minutes
0 */2 * * *    - Every 2 hours
0 0 * * *      - Daily at midnight
0 2 * * 1      - Every Monday at 2 AM
0 0 1 * *      - First day of every month
```

---

## ✅ Feature Checklist

- [x] StatefulSets list view
- [x] StatefulSets detail view
- [x] Jobs list view
- [x] Jobs detail view with logs
- [x] CronJobs list view
- [x] CronJobs detail view with job history
- [x] Quick Actions buttons on cluster page
- [x] Namespace action buttons
- [x] Status badges and icons
- [x] Events tracking
- [x] Error handling
- [x] Responsive design
- [x] Breadcrumb navigation

---

## 🚀 Get Started

1. **Start the application**: `./gradlew bootRun`
2. **Open browser**: http://localhost:8080
3. **Register a cluster** with StatefulSets, Jobs, or CronJobs
4. **Explore the new features**!

Enjoy the enhanced Kubernetes diagnostics experience! 🎉
