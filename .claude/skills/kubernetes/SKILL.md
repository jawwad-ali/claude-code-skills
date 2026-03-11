---
name: kubernetes
description: >-
  This skill MUST be loaded when ANY Kubernetes manifest file (.yml/.yaml) under
  a k8s/ directory is being read, reviewed, edited, or created — including
  Deployments, Services, ConfigMaps, Secrets, PVCs, HPAs, Namespaces, StatefulSets,
  Ingresses, or any resource manifest. Also use when the user asks to "create a
  Kubernetes deployment", "write K8s manifests", "add health probes", "configure
  liveness and readiness probes", "create a PersistentVolumeClaim", "set up a
  ConfigMap or Secret", "add an HPA", "create a K8s namespace", "write a
  StatefulSet", "expose a service with NodePort or ClusterIP", "set resource
  limits and requests", "add init containers", "configure envFrom with ConfigMap
  and Secret", "create a horizontal pod autoscaler", "debug pod startup issues",
  "fix CrashLoopBackOff", "troubleshoot ImagePullBackOff", "set up persistent
  storage for databases", "configure Redis persistence in K8s", "deploy a
  PostgreSQL StatefulSet", "add rolling update strategy", or mentions Kubernetes,
  K8s, kubectl, pods, deployments, services, namespaces, probes, PVCs, HPA,
  ConfigMaps, Secrets, resource quotas, container orchestration, or cluster
  deployment patterns.
---

# Kubernetes Manifest Guide

Write production-grade Kubernetes manifests that are secure, observable, and
resilient by default. Every manifest should be deployable with a single
`kubectl apply -f k8s/` and produce a working system.

## Core Principles

1. **Declarative over imperative** — define desired state in YAML; let the
   controller loop converge. Never rely on manual `kubectl` sequences for
   production state.
2. **Namespace isolation** — every project gets its own namespace. All resources
   reference it explicitly so `kubectl apply` is idempotent and scoped.
3. **Least privilege** — containers run as non-root, Secrets hold credentials,
   ConfigMaps hold non-sensitive config. Never embed secrets in manifests
   committed to source control (use placeholders + `kubectl create secret`).
4. **Resource budgeting** — every container declares `requests` (scheduling
   guarantee) and `limits` (hard ceiling). This prevents noisy-neighbor
   starvation and enables HPA.
5. **Health-first deployments** — every long-running container has a liveness
   probe (restart on hang) and a readiness probe (stop routing on dependency
   failure). Probes must be lightweight and side-effect-free.
6. **Persistent state is explicit** — databases and caches use PVCs with named
   claims. Stateless services never mount persistent volumes.

---

## Manifest Fundamentals

### API Version & Kind

Always use the stable API version for each resource:

```yaml
# Workloads
apiVersion: apps/v1          # Deployment, StatefulSet, DaemonSet
# Core resources
apiVersion: v1               # Service, ConfigMap, Secret, Namespace, PVC
# Scaling
apiVersion: autoscaling/v2   # HorizontalPodAutoscaler
# Ingress
apiVersion: networking.k8s.io/v1  # Ingress
```

### Metadata Convention

Every resource needs consistent metadata for filtering and management:

```yaml
metadata:
  name: <resource-name>
  namespace: <project-namespace>    # Always explicit — never rely on context
  labels:
    app: <service-name>             # Selector target
    component: <role>               # backend, frontend, database, cache
    part-of: <project-name>         # Groups all project resources
```

### Label Selectors

Deployments and Services find pods via label selectors. The golden rule:
**`spec.selector.matchLabels` must be a subset of `spec.template.metadata.labels`**.

```yaml
# Deployment
spec:
  selector:
    matchLabels:
      app: my-api                # ← Must match pod template labels
  template:
    metadata:
      labels:
        app: my-api              # ← Matched by selector
        component: backend       # ← Extra labels are fine

# Service targeting the same pods
spec:
  selector:
    app: my-api                  # ← Same label selects the same pods
```

---

## Namespace

Isolate project resources. Create the namespace first — everything else
references it.

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: my-project
  labels:
    app: my-project
```

Apply order: namespace → config → storage → workloads → scaling.

---

## ConfigMap & Secret

### ConfigMap — Non-sensitive configuration

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: my-project
data:
  LOG_LEVEL: "info"
  DATABASE_HOST: "postgres"
  CACHE_URL: "redis://redis:6379"
```

### Secret — Sensitive credentials

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
  namespace: my-project
type: Opaque
data:
  DATABASE_PASSWORD: ""       # Base64: echo -n 'value' | base64
  API_KEY: ""                 # Never commit real values
```

**Mounting in pods** — use `envFrom` to inject all keys as environment variables:

```yaml
spec:
  containers:
    - name: app
      envFrom:
        - configMapRef:
            name: app-config
        - secretRef:
            name: app-secrets
```

For composite values (like a DATABASE_URL that embeds a password), use `env`
with variable substitution:

```yaml
env:
  - name: DATABASE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: app-secrets
        key: DATABASE_PASSWORD
  - name: DATABASE_URL
    value: "postgresql://user:$(DATABASE_PASSWORD)@postgres:5432/mydb"
```

K8s resolves `$(VAR_NAME)` from previously defined env vars in the same
container spec. Order matters — the referenced variable must appear first.

---

## PersistentVolumeClaim

PVCs decouple storage from pod lifecycle. Data survives pod restarts, image
pulls, and rescheduling.

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: db-pvc
  namespace: my-project
spec:
  accessModes:
    - ReadWriteOnce           # Single-node write (standard for databases)
  resources:
    requests:
      storage: 1Gi
```

**Access modes:**

| Mode | Use Case |
|------|----------|
| `ReadWriteOnce` | Single pod writes (databases, caches) |
| `ReadOnlyMany` | Multiple pods read (shared config, assets) |
| `ReadWriteMany` | Multiple pods write (shared files — needs NFS/Ceph) |

Mount in a deployment:

```yaml
spec:
  containers:
    - volumeMounts:
        - name: db-storage
          mountPath: /var/lib/postgresql/data
  volumes:
    - name: db-storage
      persistentVolumeClaim:
        claimName: db-pvc
```

---

## Deployments

### Resource Requests & Limits

Every container must declare resources. Without them, HPA cannot calculate
utilization and the scheduler cannot make informed placement decisions.

```yaml
resources:
  requests:              # Guaranteed minimum — scheduler reserves this
    cpu: 100m            # 100 millicores = 0.1 CPU core
    memory: 256Mi        # 256 MiB RAM
  limits:                # Hard ceiling — OOMKill if memory exceeded
    cpu: 500m
    memory: 512Mi
```

**Sizing guidelines:**

| Service Type | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-------------|-------------|-----------|----------------|--------------|
| API / backend | 100m–250m | 500m–1000m | 256Mi–512Mi | 512Mi–1Gi |
| Web frontend | 50m–100m | 200m–500m | 128Mi–256Mi | 256Mi–512Mi |
| Database | 100m–500m | 500m–2000m | 256Mi–1Gi | 512Mi–2Gi |
| Cache (Redis) | 50m–100m | 200m–500m | 128Mi–256Mi | 256Mi–512Mi |

These are starting points. For production, profile actual usage and set
requests to P50, limits to P99.

### Health Probes

Probes serve fundamentally different purposes — choosing the wrong one causes
cascading failures.

**Liveness probe** — "Is this process alive?"
Restart the pod if it fails. Use for detecting deadlocks, infinite loops, or
zombie processes. Keep it cheap — no dependency checks.

```yaml
livenessProbe:
  httpGet:
    path: /healthz             # Or /health/live — lightweight, no deps
    port: 8000
  initialDelaySeconds: 10      # Wait for app startup
  periodSeconds: 10            # Check every 10s
  timeoutSeconds: 5            # Fail if no response in 5s
  failureThreshold: 3          # Restart after 3 consecutive failures
```

**Readiness probe** — "Can this instance serve traffic?"
Remove from Service endpoints if it fails. Use for dependency checks (database,
cache). The pod stays running — it just stops receiving traffic until recovery.

```yaml
readinessProbe:
  httpGet:
    path: /ready               # Or /health/ready — checks dependencies
    port: 8000
  initialDelaySeconds: 5
  periodSeconds: 5             # Check frequently — fast recovery
  timeoutSeconds: 5
  failureThreshold: 2          # Remove quickly on failure
```

**Startup probe** — "Has the app finished starting?"
Disables liveness/readiness until the app is ready. Use for slow-starting
applications (JVM warmup, large model loading).

```yaml
startupProbe:
  httpGet:
    path: /healthz
    port: 8000
  periodSeconds: 5
  failureThreshold: 30         # 30 × 5s = 150s max startup time
```

**Critical mistakes to avoid:**
- Never put dependency checks in the liveness probe — if the database goes
  down, you don't want every API pod restarting (cascade failure)
- Always set `initialDelaySeconds` — probing before the app starts causes
  unnecessary restarts
- Keep probe endpoints fast (< 200ms) — slow probes cause false positives

### Init Containers

Run setup tasks before the main container starts. Common uses: database
migrations, config generation, waiting for dependencies.

```yaml
spec:
  initContainers:
    - name: run-migrations
      image: my-app:latest           # Reuse the app image
      command: ["bash", "scripts/migrate.sh"]
      envFrom:
        - configMapRef:
            name: app-config
        - secretRef:
            name: app-secrets
  containers:
    - name: app
      # ... main container starts after init completes
```

**Init container rules:**
- Runs to completion before main container starts
- If it fails, the pod restarts (respects `restartPolicy`)
- Has access to the same volumes, ConfigMaps, and Secrets as main container
- Scripts must be idempotent — they run on every pod start

### Rolling Update Strategy

Control how deployments roll out new versions:

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1              # Create 1 extra pod during update
      maxUnavailable: 0        # Never reduce below desired replicas
```

`maxUnavailable: 0` ensures zero-downtime deploys — new pods must pass
readiness before old pods are terminated.

For single-instance databases, use `strategy: Recreate` to prevent dual-write.

---

## Services

Services provide stable DNS names and load balancing for pods.

### ClusterIP (Internal)

Default type. Accessible within the cluster via DNS:
`<service-name>.<namespace>.svc.cluster.local` (or just `<service-name>`
within the same namespace).

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-api
  namespace: my-project
spec:
  type: ClusterIP
  selector:
    app: my-api
  ports:
    - name: http
      port: 8000
      targetPort: 8000
      protocol: TCP
```

### NodePort (External Access for Dev)

Exposes on each node's IP at a static port (30000–32767). Use for local dev
when you need external access without an Ingress controller.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-web
  namespace: my-project
spec:
  type: NodePort
  selector:
    app: my-web
  ports:
    - name: http
      port: 3000
      targetPort: 3000
      nodePort: 30000         # Optional: omit for auto-assignment
```

### When to use which

| Type | Use Case | Access |
|------|----------|--------|
| ClusterIP | Internal services (DB, cache, API-to-API) | `svc-name:port` within cluster |
| NodePort | Dev/local external access | `localhost:nodePort` |
| LoadBalancer | Cloud production external access | Cloud-provisioned external IP |
| Ingress | HTTP routing, TLS, path/host-based | Domain-based routing |

---

## HorizontalPodAutoscaler

Auto-scale workloads based on observed metrics. Requires resource requests
on target pods (HPA calculates utilization as `current / request`).

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: my-api-hpa
  namespace: my-project
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: my-api
  minReplicas: 1
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70    # Scale up when avg CPU > 70%
```

**Behavior tuning** (prevent flapping):

```yaml
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Pods
          value: 2
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300   # 5 minutes cooldown
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
```

Scale up aggressively to handle spikes; scale down conservatively to avoid
oscillation.

---

## Best Practices

1. **Always set a namespace explicitly** — never depend on kubectl context.
   Manifests should be self-contained and apply correctly regardless of the
   user's current context.

2. **Never commit real secrets** — use empty placeholders in manifests and
   create real secrets with `kubectl create secret generic`. Add secret files
   to `.gitignore` if they ever contain real values.

3. **Match probe type to failure mode** — liveness for process health (no deps),
   readiness for dependency health (DB, cache). Mixing them causes cascading
   restarts.

4. **Set resource requests AND limits on every container** — requests enable
   scheduling; limits prevent runaway consumption. Without requests, HPA
   cannot function.

5. **Use `envFrom` over individual `env` entries** — cleaner manifests, easier
   to add new config without touching deployments.

6. **Make init containers idempotent** — they run on every pod start, not just
   the first time. Use `IF NOT EXISTS` in SQL, check-then-create patterns.

7. **PVCs for stateful services only** — databases and caches get PVCs. API
   servers and web frontends should be stateless and disposable.

8. **Label everything consistently** — `app`, `component`, and `part-of` labels
   enable `kubectl get pods -l app=my-api` and service discovery.

9. **Order manifests for dependency** — namespace first, then config/secrets,
   then storage, then workloads, then scaling. Use file naming
   (00-namespace.yml, 10-config.yml) or kustomize for ordering.

10. **Test with `kubectl apply --dry-run=client`** — catches syntax errors and
    missing fields before hitting the cluster. Follow with `kubectl diff` to
    preview changes against the live state.

---

## When to Read Reference Files

| Need | File |
|------|------|
| Deployment, StatefulSet, init containers, probes, rolling updates, pod security | `references/workload-resources.md` |
| Services, Ingress, PVCs, ConfigMaps, Secrets, NetworkPolicy, storage classes | `references/networking-and-storage.md` |
| HPA, VPA, resource tuning, pod disruption budgets, chaos testing, monitoring | `references/scaling-and-reliability.md` |

## Example Templates

| Need | File |
|------|------|
| Namespace + ConfigMap + Secret (project bootstrap) | `examples/namespace-config.yml` |
| Database Deployment + PVC + Service (PostgreSQL pattern) | `examples/stateful-database.yml` |
| Cache Deployment + PVC + Service (Redis with AOF) | `examples/stateful-cache.yml` |
| API Deployment with init container, probes, HPA + Service | `examples/api-deployment.yml` |
| Web Deployment + NodePort Service (frontend pattern) | `examples/web-deployment.yml` |
