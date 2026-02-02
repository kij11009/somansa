# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**K8s Doctor** - AI-powered Kubernetes diagnostics and troubleshooting tool with multi-cluster support.

The application helps DevOps engineers, SREs, and developers quickly diagnose and resolve Kubernetes cluster issues using AI-powered analysis.

## Current Development Status

### ✅ Completed Features (Step 1-2 + Multi-Cluster)

#### Step 1: Project Setup & Infrastructure
- Spring Boot 3.2.1 application with Java 17
- Gradle 8.11 build system
- OpenRouter API integration for AI analysis
- Kubernetes Fabric8 client (6.10.0)
- Thymeleaf + Bootstrap 5 UI
- Caffeine cache (5 min TTL)
- Actuator for monitoring
- **Automatic .env file loading** (dotenv-java)

#### Step 2: Kubernetes Resource Management
- Multi-cluster support with cluster registration
- Resource query services (Namespaces, Pods, Deployments, Nodes)
- Pod logs and events retrieval
- Exception handling with custom error pages
- Caching strategy for performance
- RBAC configuration (read-only access)

#### Multi-Cluster Features
- Cluster registration (kubeconfig upload or manual config)
- Cluster management (list, detail, delete, test connection)
- Per-cluster resource browsing
- In-memory cluster storage (session-based)

### 🚧 Pending Features

#### Step 3: Fault Detection & Classification Engine
- Automatic fault detection (CrashLoopBackOff, OOMKilled, etc.)
- Fault type classification
- Health scoring system

#### Step 4: AI Analysis Engine
- Root cause analysis using OpenRouter API
- Solution recommendations
- YAML fix examples
- Step-by-step resolution guides

#### Step 5: Web UI & Reporting
- Enhanced diagnostic dashboard
- PDF/HTML report generation
- Historical fault tracking

#### Step 6: Advanced Multi-Cluster (Optional)
- Database persistence for cluster configs
- Cluster health monitoring
- Cross-cluster comparisons

#### Step 7: Testing & Deployment
- Unit and integration tests
- CI/CD pipeline
- Docker containerization
- Kubernetes deployment manifests

## Architecture

### Technology Stack
- **Backend**: Spring Boot 3.2.1, Java 17
- **Build**: Gradle 8.11
- **Kubernetes**: Fabric8 Client 6.10.0
- **AI**: OpenRouter API (configurable model via modelkey.txt)
- **Frontend**: Thymeleaf, Bootstrap 5, Bootstrap Icons
- **Cache**: Caffeine (in-memory)
- **Monitoring**: Spring Boot Actuator

### Project Structure
```
k8s-doctor/
├── src/main/
│   ├── java/com/vibecoding/k8sdoctor/
│   │   ├── K8sDoctorApplication.java      # Main application with .env loading
│   │   ├── config/                        # Configuration classes
│   │   │   ├── CacheConfig.java          # Caffeine cache setup
│   │   │   ├── K8sClientConfig.java      # Default K8s client (legacy)
│   │   │   ├── OpenRouterConfig.java     # OpenRouter API config
│   │   │   └── WebConfig.java            # Web configuration
│   │   ├── controller/                    # MVC controllers
│   │   │   ├── ClusterController.java    # Cluster management
│   │   │   ├── HomeController.java       # Homepage
│   │   │   └── ResourceController.java   # K8s resource views
│   │   ├── service/                       # Business logic
│   │   │   ├── ClusterService.java       # Cluster operations
│   │   │   └── MultiClusterK8sService.java # Multi-cluster K8s queries
│   │   ├── repository/                    # Data access
│   │   │   └── ClusterRepository.java    # In-memory cluster storage
│   │   ├── model/                         # DTOs and domain models
│   │   │   ├── ClusterInfo.java          # Cluster metadata
│   │   │   ├── ClusterConfig.java        # Cluster connection config
│   │   │   ├── ClusterStatus.java        # Enum: CONNECTED/ERROR/etc
│   │   │   ├── PodDiagnosticInfo.java    # Pod diagnostic data
│   │   │   ├── ContainerStatusInfo.java  # Container status
│   │   │   ├── EventInfo.java            # K8s event info
│   │   │   └── ResourceInfo.java         # Generic resource info
│   │   └── exception/                     # Exception handling
│   │       ├── K8sApiException.java      # K8s API errors
│   │       ├── K8sResourceNotFoundException.java
│   │       └── K8sExceptionHandler.java  # Global exception handler
│   └── resources/
│       ├── application.properties         # App configuration
│       ├── templates/                     # Thymeleaf templates
│       │   ├── index.html                # Homepage
│       │   ├── clusters/                 # Cluster management views
│       │   │   ├── list.html            # Cluster list
│       │   │   ├── new.html             # Register cluster
│       │   │   └── detail.html          # Cluster detail
│       │   ├── resources/                # Resource views
│       │   │   ├── namespaces.html
│       │   │   ├── pods.html
│       │   │   ├── pod-detail.html
│       │   │   ├── deployments.html
│       │   │   └── nodes.html
│       │   └── error/
│       │       └── error.html            # Error page
│       └── static/                        # Static assets (if any)
├── k8s/                                   # Kubernetes manifests
│   └── rbac.yaml                         # Read-only ClusterRole
├── prd/                                   # Product requirements docs
│   ├── prd_step1.md through prd_step7.md
├── .env                                   # Environment variables (gitignored)
├── .env.example                          # Environment template
├── modelkey.txt                          # AI model selection
├── build.gradle                          # Gradle build configuration
├── settings.gradle                       # Gradle settings
└── README.md                             # Project documentation
```

## Configuration Files

### Environment Variables (.env)
```
OPENROUTER_API_KEY=sk-or-v1-your-api-key-here
```
The `.env` file is **automatically loaded** at application startup. No manual environment variable setup required.

### Model Selection (modelkey.txt)
Specifies which OpenRouter AI model to use:
```
google/gemma-3-27b-it:free
```

### Application Properties
Key configurations in `src/main/resources/application.properties`:
- Server port: 8080
- OpenRouter API URL and timeouts
- Kubernetes client timeouts
- Cache settings (Caffeine)
- Actuator endpoints

## Running the Application

### Prerequisites
- Java 17+
- Gradle 8.11+ (or use included wrapper)
- OpenRouter API key
- Kubernetes cluster (optional for testing)

### Quick Start
```bash
# 1. Set up .env file
echo "OPENROUTER_API_KEY=sk-or-v1-your-key" > .env

# 2. Run application (no build needed, auto .env loading)
gradlew.bat bootRun

# 3. Access application
# http://localhost:8080
```

### Workflow
1. **Register Clusters**: Upload kubeconfig or configure manually
2. **Select Cluster**: Choose from registered clusters
3. **Browse Resources**: View Namespaces, Pods, Deployments, Nodes
4. **View Details**: Check Pod logs, events, status
5. **Diagnose Issues**: (Step 3-4, coming soon)

## API Endpoints

### Cluster Management
- `GET /clusters` - List all clusters
- `GET /clusters/new` - Cluster registration form
- `POST /clusters` - Register new cluster
- `GET /clusters/{id}` - Cluster details
- `POST /clusters/{id}/delete` - Delete cluster
- `POST /clusters/{id}/test` - Test connection
- `POST /clusters/{id}/refresh` - Refresh cluster info

### Resource Browsing (per cluster)
- `GET /clusters/{id}/resources/namespaces` - List namespaces
- `GET /clusters/{id}/resources/pods` - List pods
- `GET /clusters/{id}/resources/pods/{ns}/{name}` - Pod detail (logs + events)
- `GET /clusters/{id}/resources/deployments` - List deployments
- `GET /clusters/{id}/resources/nodes` - List nodes

### Monitoring
- `GET /actuator/health` - Health check
- `GET /actuator/info` - Application info
- `GET /actuator/metrics` - Metrics

## Security Considerations

### API Key Management
- **Never commit** `.env` file to version control (already in `.gitignore`)
- Use environment variables in production
- Rotate API keys regularly

### Kubernetes Access
- Uses **read-only** RBAC permissions (see `k8s/rbac.yaml`)
- No write/delete operations on cluster resources
- Kubeconfig stored in-memory only (not persisted to disk)
- Supports both kubeconfig and token-based auth

### Known Limitations
- Cluster configurations stored in-memory (lost on restart)
- No user authentication (single-user application)
- TLS verification disabled for dev environments (trustCerts=true)

## Development Notes

### Adding New Features
1. Check PRD documents in `prd/` folder for requirements
2. Create model/DTO classes in `model/` package
3. Implement service logic in `service/` package
4. Add controller endpoints in `controller/` package
5. Create Thymeleaf templates in `resources/templates/`
6. Update CLAUDE.md and README.md

### Testing
```bash
# Build without tests
gradlew.bat build -x test

# Run with tests
gradlew.bat build
```

### Common Issues
1. **Java not found**: Set JAVA_HOME to JDK 17 installation
2. **Gradle version error**: Use Gradle 8.11+ (wrapper auto-downloads)
3. **API key error**: Ensure `.env` file exists with valid `OPENROUTER_API_KEY`
4. **Cluster connection fails**: Verify kubeconfig or API server URL

## Next Steps

See `prd/prd_step3.md` for fault detection engine requirements.

The next major feature is **automatic fault detection and classification** using Kubernetes event analysis and pod status checks.

## Important Reminders

- **Never commit** `.env` file or expose API keys
- **Read-only access** to Kubernetes clusters (no modifications)
- **In-memory storage** means cluster configs are lost on restart
- **Multi-cluster support** is fully functional but not persistent
- **.env auto-loading** works via dotenv-java library in K8sDoctorApplication.java
