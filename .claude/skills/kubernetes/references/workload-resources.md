# Workload Resources Reference

Deep-dive patterns for Deployments, StatefulSets, init containers, probes,
pod security, and rolling update strategies. Read SKILL.md first for
fundamentals.

## Table of Contents

1. [Deployment Patterns](#deployment-patterns)
2. [StatefulSet vs Deployment](#statefulset-vs-deployment)
3. [Init Container Patterns](#init-container-patterns)
4. [Health Probe Deep Dive](#health-probe-deep-dive)
5. [Pod Security Context](#pod-security-context)
6. [Rolling Update Strategies](#rolling-update-strategies)
7. [Common Pitfalls](#common-pitfalls)

---

## Deployment Patterns

### Stateless Application Server

Stateless workloads that can scale horizontally. All state lives in external
services (database, cache).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-api
  namespace: my-project
  labels:
    app: my-api
    component: backend
    part-of: my-project
spec:
  replicas: 1
  revisionHistoryLimit: 5          # Keep 5 old ReplicaSets for rollback
  selector:
    matchLabels:
      app: my-api
  template:
    metadata:
      labels:
        app: my-api
        component: backend
        part-of: my-project
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: my-api
          image: my-api:latest
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8000
              protocol: TCP
          livenessProbe:
            httpGet:
              path: /healthz
              port: http
            initialDelaySeconds: 10
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /ready
              port: http
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 2
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          envFrom:
            - configMapRef:
                name: app-config
            - secretRef:
                name: app-secrets
      restartPolicy: Always
```

### Single-Replica Database

Single-replica database with persistent storage. Uses Deployment (not
StatefulSet) when you need exactly one replica and don't require stable
network identities.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: my-project
spec:
  replicas: 1                        # Never scale databases without consensus protocol
  strategy:
    type: Recreate                   # Kill old before creating new — prevents dual-write
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
        component: database
        part-of: my-project
    spec:
      containers:
        - name: postgres
          image: postgres:16
          ports:
            - name: postgres
              containerPort: 5432
          env:
            - name: POSTGRES_DB
              value: mydb
            - name: POSTGRES_USER
              value: postgres
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: app-secrets
                  key: DATABASE_PASSWORD
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: db-storage
              mountPath: /var/lib/postgresql/data
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "postgres"]
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 5
          livenessProbe:
            exec:
              command: ["pg_isready", "-U", "postgres"]
            initialDelaySeconds: 30
            periodSeconds: 30
            failureThreshold: 3
      volumes:
        - name: db-storage
          persistentVolumeClaim:
            claimName: db-pvc
```

**PGDATA subdirectory**: PostgreSQL requires an empty directory to initialize.
When using PVCs, set `PGDATA` to a subdirectory (e.g., `/data/pgdata`) to
avoid conflicts with the PVC's `lost+found` directory.

### Single-Replica Cache

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: my-project
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
        component: cache
        part-of: my-project
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          command:
            - redis-server
            - "--appendonly"
            - "yes"
            - "--appendfsync"
            - "everysec"
          ports:
            - name: redis
              containerPort: 6379
          volumeMounts:
            - name: cache-storage
              mountPath: /data
          resources:
            requests:
              cpu: 50m
              memory: 128Mi
            limits:
              cpu: 200m
              memory: 256Mi
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
          livenessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 15
            periodSeconds: 20
            failureThreshold: 3
      volumes:
        - name: cache-storage
          persistentVolumeClaim:
            claimName: cache-pvc
```

---

## StatefulSet vs Deployment

| Feature | Deployment | StatefulSet |
|---------|-----------|-------------|
| Pod identity | Random names (api-7f8b9-x4k2z) | Stable ordinals (db-0, db-1) |
| Scaling | Parallel create/delete | Sequential (0, 1, 2...) |
| Storage | Shared PVC or no PVC | Per-pod PVC via volumeClaimTemplates |
| DNS | Through Service only | `pod-name.service-name.namespace` |
| Use case | Stateless apps, APIs, frontends | Databases, consensus clusters (etcd, Kafka) |

**When to use StatefulSet:**
- You need stable network identities (pod-0, pod-1) for clustering
- You need per-pod persistent storage (each replica gets its own PVC)
- You need ordered, graceful scaling (create pod-0 before pod-1)

**When Deployment is enough:**
- Single-replica databases (no clustering needed)
- Caches with a single shared PVC
- Any stateless workload

For most single-instance databases in dev/staging, Deployment with `strategy:
Recreate` and a PVC is simpler and sufficient.

---

## Init Container Patterns

### Database Migration

The most common pattern: run schema migrations before the app starts.

```yaml
spec:
  initContainers:
    - name: run-migrations
      image: my-api:latest
      command:
        - bash
        - -c
        - |
          cd /app
          bash scripts/migrate.sh
      envFrom:
        - configMapRef:
            name: app-config
        - secretRef:
            name: app-secrets
```

### Wait for Dependency

Block startup until a dependency is reachable. Useful when K8s has no native
service dependency ordering (unlike Docker Compose `depends_on`).

```yaml
initContainers:
  - name: wait-for-db
    image: busybox:1.36
    command:
      - sh
      - -c
      - |
        until nc -z postgres 5432; do
          echo "Waiting for database..."
          sleep 2
        done
        echo "Database is ready"
```

### Multiple Init Containers

Init containers run sequentially in order. Use for multi-step initialization:

```yaml
initContainers:
  - name: wait-for-db
    image: busybox:1.36
    command: ["sh", "-c", "until nc -z postgres 5432; do sleep 2; done"]
  - name: run-migrations
    image: my-api:latest
    command: ["bash", "scripts/migrate.sh"]
    envFrom:
      - configMapRef:
          name: app-config
      - secretRef:
          name: app-secrets
```

---

## Health Probe Deep Dive

### Probe Types

| Probe | Purpose | On Failure | Check What |
|-------|---------|------------|------------|
| Liveness | Is the process alive? | Restart pod | Process health only |
| Readiness | Can it serve traffic? | Remove from Service | Dependencies (DB, cache) |
| Startup | Has it finished starting? | Block liveness/readiness | Same as liveness |

### Probe Mechanisms

```yaml
# HTTP GET — most common for web services
httpGet:
  path: /healthz
  port: 8000
  httpHeaders:                    # Optional custom headers
    - name: X-Health-Check
      value: "kubernetes"

# TCP Socket — for services without HTTP (databases, caches)
tcpSocket:
  port: 5432

# Exec — run a command inside the container
exec:
  command: ["pg_isready", "-U", "postgres"]
```

### Timing Parameters

```yaml
initialDelaySeconds: 10    # Seconds before first probe after container starts
periodSeconds: 10          # How often to probe
timeoutSeconds: 5          # Seconds to wait for a response
successThreshold: 1        # Consecutive successes to mark healthy
failureThreshold: 3        # Consecutive failures before action
```

**Calculating max detection time:**
- Liveness: `periodSeconds × failureThreshold` = 10 × 3 = 30s
- Readiness: `periodSeconds × failureThreshold` = 5 × 2 = 10s
- Full recovery: detection + restart + `initialDelaySeconds` + readiness pass

### Anti-Patterns

**Liveness probe checks dependencies:**
```yaml
# BAD — database outage restarts all API pods simultaneously
livenessProbe:
  httpGet:
    path: /ready    # This checks DB + cache!
```
If the database goes down, all API pods restart at once. When it recovers,
all pods start simultaneously and overwhelm it with connections.

**No initialDelaySeconds:**
```yaml
# BAD — probing before the app is listening
livenessProbe:
  httpGet:
    path: /healthz
    port: 8000
  # Missing initialDelaySeconds → immediate probe → CrashLoopBackOff
```

**Slow probe endpoint:**
```yaml
# BAD — endpoint does heavy computation
livenessProbe:
  httpGet:
    path: /health    # Runs analytics queries and aggregations
  timeoutSeconds: 30  # Long timeout = wrong fix
```

---

## Pod Security Context

Run containers as non-root with minimal capabilities:

```yaml
spec:
  securityContext:                  # Pod-level
    runAsNonRoot: true
    fsGroup: 1000
  containers:
    - name: app
      securityContext:             # Container-level
        runAsUser: 1000
        runAsGroup: 1000
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
```

**ReadOnlyRootFilesystem caveat**: if your app writes temp files, mount a
writable `emptyDir`:

```yaml
volumeMounts:
  - name: tmp
    mountPath: /tmp
volumes:
  - name: tmp
    emptyDir: {}
```

---

## Rolling Update Strategies

### Zero-Downtime Deploy

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1              # Create 1 extra pod during update
      maxUnavailable: 0        # Always maintain full capacity
```

Flow: new pod created → readiness passes → old pod terminated.

### Fast Deploy (Tolerate Brief Capacity Reduction)

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2
      maxUnavailable: 1
```

### Database Deploy (No Dual-Write)

```yaml
spec:
  strategy:
    type: Recreate             # Kill old pod, then create new
```

For single-instance databases, `Recreate` prevents two instances writing to
the same storage simultaneously. Accept brief downtime.

---

## Common Pitfalls

### CrashLoopBackOff

**Symptoms**: Pod starts, crashes, restarts with exponential backoff.

**Common causes:**
1. Missing environment variable → app fails to start
2. Missing secret key → crash on config read
3. Liveness probe fires before app is ready → restart loop
4. OOMKill → memory limit too low

**Debug:**
```bash
kubectl -n <ns> logs <pod> --previous    # Logs from crashed container
kubectl -n <ns> describe pod <pod>       # Events: OOMKilled, probe failures
```

### ImagePullBackOff

**Symptoms**: Pod stuck in `ImagePullBackOff` or `ErrImagePull`.

**Common causes:**
1. Image doesn't exist in registry
2. `imagePullPolicy: Always` on a local-only image
3. Typo in image name or tag

**Fix for local images:**
```yaml
imagePullPolicy: IfNotPresent    # Use local image if it exists
imagePullPolicy: Never           # Never pull — fail if not local
```

### Pod Stuck in Pending

**Symptoms**: Pod stays in `Pending`, never scheduled.

**Common causes:**
1. Insufficient cluster resources (requests exceed available)
2. PVC not bound (storage class doesn't exist)
3. Node selector or affinity doesn't match any node

**Debug:**
```bash
kubectl -n <ns> describe pod <pod>    # Events: scheduling failures
kubectl get pvc -n <ns>               # Check PVCs are Bound
kubectl describe nodes                # Check allocatable resources
```
