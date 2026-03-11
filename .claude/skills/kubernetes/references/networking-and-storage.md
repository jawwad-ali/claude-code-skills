# Networking & Storage Reference

Deep-dive patterns for Services, Ingress, PVCs, storage classes, ConfigMaps,
Secrets management, and NetworkPolicy. Read SKILL.md first for fundamentals.

## Table of Contents

1. [Service Patterns](#service-patterns)
2. [Ingress Configuration](#ingress-configuration)
3. [Persistent Storage Deep Dive](#persistent-storage-deep-dive)
4. [ConfigMap & Secret Patterns](#configmap--secret-patterns)
5. [DNS & Service Discovery](#dns--service-discovery)
6. [NetworkPolicy](#networkpolicy)
7. [Common Pitfalls](#common-pitfalls)

---

## Service Patterns

### ClusterIP (Internal Communication)

Default type. Creates a stable internal IP and DNS name for pod-to-pod
communication.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-db
  namespace: my-project
  labels:
    app: my-db
    component: database
spec:
  type: ClusterIP
  selector:
    app: my-db
  ports:
    - name: postgres
      port: 5432
      targetPort: 5432
      protocol: TCP
```

**Multi-port Service** (for services exposing multiple ports):

```yaml
spec:
  ports:
    - name: http
      port: 8000
      targetPort: 8000
    - name: metrics
      port: 9090
      targetPort: 9090
```

When a service exposes multiple ports, every port must have a `name`.

### NodePort (Local/Dev External Access)

Exposes on each node's IP at a static port in the 30000–32767 range.

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
      nodePort: 30000       # Optional: K8s auto-assigns if omitted
```

**Specify `nodePort`** for documentation clarity and consistency.
**Omit `nodePort`** when running multiple environments on the same node.

### LoadBalancer (Cloud Production)

Cloud providers provision an external IP or hostname automatically:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-api
  namespace: my-project
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
spec:
  type: LoadBalancer
  selector:
    app: my-api
  ports:
    - port: 443
      targetPort: 8000
```

Not applicable for local clusters (Docker Desktop, minikube, kind) unless
using MetalLB or similar.

### Headless Service (Direct Pod Access)

For StatefulSets where you need direct pod DNS (`pod-0.svc-name`):

```yaml
spec:
  type: ClusterIP
  clusterIP: None           # Makes it headless
  selector:
    app: my-db
```

---

## Ingress Configuration

Route external HTTP traffic to internal services based on hostname and path.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-ingress
  namespace: my-project
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
spec:
  ingressClassName: nginx
  rules:
    - host: app.example.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: my-api
                port:
                  number: 8000
          - path: /
            pathType: Prefix
            backend:
              service:
                name: my-web
                port:
                  number: 3000
  tls:
    - hosts:
        - app.example.com
      secretName: tls-cert
```

**Requires an Ingress Controller** (nginx-ingress, traefik, etc.) installed
in the cluster. Without one, Ingress resources are ignored.

For local development, NodePort is simpler. Ingress is for production
multi-service routing.

---

## Persistent Storage Deep Dive

### PVC Lifecycle

```
PVC Created → Pending → Bound → In Use → Released → (Reclaimed/Deleted)
```

A PVC stays `Pending` until a PersistentVolume matching its request exists.
In managed clusters and local clusters with a default StorageClass, dynamic
provisioning creates PVs automatically.

### Storage Classes

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: my-pvc
  namespace: my-project
spec:
  storageClassName: standard       # Omit to use cluster default
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

**Local cluster defaults:**

| Cluster | Default StorageClass | Backend |
|---------|---------------------|---------|
| Docker Desktop | `hostpath` | Host filesystem |
| minikube | `standard` (hostpath provisioner) | Host filesystem |
| kind | `standard` (rancher local-path) | Host filesystem |

All support `ReadWriteOnce`. None support `ReadWriteMany` without additional
setup (NFS, Ceph).

### Volume Mount Patterns

**Database with PGDATA subdirectory:**

```yaml
containers:
  - name: postgres
    env:
      - name: PGDATA
        value: /var/lib/postgresql/data/pgdata
    volumeMounts:
      - name: storage
        mountPath: /var/lib/postgresql/data
```

**Redis with AOF persistence:**

```yaml
containers:
  - name: redis
    command: ["redis-server", "--appendonly", "yes", "--appendfsync", "everysec"]
    volumeMounts:
      - name: storage
        mountPath: /data
```

### EmptyDir (Ephemeral Shared Storage)

Temporary storage for the lifetime of the pod. Shared between containers in
the same pod.

```yaml
volumes:
  - name: tmp
    emptyDir: {}
  - name: cache
    emptyDir:
      sizeLimit: 100Mi            # Evict pod if exceeded
```

Use cases: temp files, inter-container communication, cache warm-up.

---

## ConfigMap & Secret Patterns

### ConfigMap from Literal Values

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: my-project
data:
  LOG_LEVEL: "info"
  CACHE_TTL: "3600"
  EXTERNAL_API_URL: "https://api.example.com"
```

### ConfigMap from File Content

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: nginx-config
  namespace: my-project
data:
  nginx.conf: |
    server {
      listen 80;
      location / {
        proxy_pass http://my-web:3000;
      }
    }
```

Mount as a single file (not the whole directory):

```yaml
volumeMounts:
  - name: config
    mountPath: /etc/nginx/nginx.conf
    subPath: nginx.conf
volumes:
  - name: config
    configMap:
      name: nginx-config
```

### Secret Management Patterns

**Pattern 1: Placeholder manifest + kubectl create**

```yaml
# Committed to source control — empty placeholders only
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
  namespace: my-project
type: Opaque
data:
  DATABASE_PASSWORD: ""
  API_KEY: ""
```

Create real secret at deploy time:
```bash
kubectl -n my-project create secret generic app-secrets \
  --from-literal=DATABASE_PASSWORD="secure-pw" \
  --from-literal=API_KEY="sk-..." \
  --dry-run=client -o yaml | kubectl apply -f -
```

**Pattern 2: envFrom (inject all keys)**

```yaml
envFrom:
  - secretRef:
      name: app-secrets
```

**Pattern 3: Individual key reference**

```yaml
env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: app-secrets
        key: DATABASE_PASSWORD
```

**Pattern 4: Composite env with variable substitution**

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

Order matters — the referenced variable must appear first in the `env` list.

---

## DNS & Service Discovery

Within the same namespace, pods reach services by name:

```
postgres       → postgres.<ns>.svc.cluster.local
redis:6379     → redis.<ns>.svc.cluster.local:6379
my-api:8000    → my-api.<ns>.svc.cluster.local:8000
```

**Cross-namespace access** requires the full form:

```
monitoring.observability.svc.cluster.local
```

**Auto-injected env vars**: K8s injects `<SERVICE_NAME>_SERVICE_HOST` and
`<SERVICE_NAME>_SERVICE_PORT` for every service in the same namespace. Prefer
DNS names — they're more readable and don't depend on service creation order.

---

## NetworkPolicy

Restrict traffic flow between pods. By default, all pods can communicate.
NetworkPolicy adds firewall rules.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: db-allow-api-only
  namespace: my-project
spec:
  podSelector:
    matchLabels:
      app: my-db
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: my-api
      ports:
        - protocol: TCP
          port: 5432
```

**Requires a CNI that supports NetworkPolicy** (Calico, Cilium, etc.).
Docker Desktop and basic minikube may not enforce NetworkPolicy.

---

## Common Pitfalls

### PVC Stuck in Pending

**Cause**: No StorageClass matches, or provisioner can't fulfill the request.

```bash
kubectl get pvc -n <ns>                  # Check status
kubectl describe pvc <name> -n <ns>      # Events show errors
kubectl get storageclass                 # Available classes
```

**Fix**: Specify a valid `storageClassName` or ensure a default exists.

### Service Not Routing to Pods

**Cause**: Label selector mismatch between Service and Pod.

```bash
kubectl get svc <name> -n <ns> -o yaml | grep -A5 selector
kubectl get pods -n <ns> --show-labels
kubectl get endpoints <name> -n <ns>     # Should list pod IPs
```

Empty endpoints = selector doesn't match any running, ready pods.

### ConfigMap/Secret Updates Not Reflected

Env vars from `envFrom` or `valueFrom` are injected at pod creation. Changing
a ConfigMap/Secret does not automatically restart pods.

**Options:**
1. Delete pods — deployment recreates them with new config
2. Add a config hash annotation to trigger rolling update
3. Use a tool like Reloader that watches for changes

### DNS Not Resolving

New services take a few seconds to propagate through CoreDNS. If a pod starts
before the service DNS is available, connections fail.

**Fix**: Use init containers to wait for the service, or configure application
retry logic.
