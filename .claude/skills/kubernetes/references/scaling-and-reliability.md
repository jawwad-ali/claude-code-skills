# Scaling & Reliability Reference

Deep-dive patterns for HPA, VPA, resource tuning, pod disruption budgets,
chaos testing, monitoring, and production readiness. Read SKILL.md first for
fundamentals.

## Table of Contents

1. [HorizontalPodAutoscaler Deep Dive](#horizontalpodautoscaler-deep-dive)
2. [Resource Tuning](#resource-tuning)
3. [Pod Disruption Budgets](#pod-disruption-budgets)
4. [Chaos Testing Patterns](#chaos-testing-patterns)
5. [Monitoring & Observability](#monitoring--observability)
6. [Production Readiness Checklist](#production-readiness-checklist)
7. [Common Pitfalls](#common-pitfalls)

---

## HorizontalPodAutoscaler Deep Dive

### Basic CPU-Based Scaling

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
          averageUtilization: 70
```

**How HPA calculates replicas:**

```
desiredReplicas = ceil(currentReplicas × (currentUtilization / targetUtilization))
```

Example: 2 replicas at 90% CPU, target 70%:
```
ceil(2 × (90/70)) = ceil(2.57) = 3 replicas
```

**Prerequisites:**
- Pods must have `resources.requests.cpu` — HPA uses this as the denominator
- Metrics Server must be installed:
  - Docker Desktop: pre-installed
  - minikube: `minikube addons enable metrics-server`
  - kind: install manually

### Multi-Metric Scaling

Scale on multiple signals. HPA picks whichever metric produces the highest
replica count.

```yaml
spec:
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

### Custom Metric Scaling

Scale on application-specific metrics (requires a custom metrics adapter
like Prometheus Adapter):

```yaml
spec:
  metrics:
    - type: Pods
      pods:
        metric:
          name: http_requests_per_second
        target:
          type: AverageValue
          averageValue: 100
```

### Behavior Tuning

Control scale-up and scale-down speed to prevent flapping:

```yaml
spec:
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Pods
          value: 2
          periodSeconds: 60
        - type: Percent
          value: 50
          periodSeconds: 60
      selectPolicy: Max              # Use whichever allows more pods

    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
      selectPolicy: Min              # Use whichever removes fewer pods
```

**Why asymmetric?** Scale up aggressively to handle spikes; scale down
conservatively to avoid oscillation.

### Verifying HPA

```bash
kubectl get hpa -n <ns>              # Current state
kubectl describe hpa <name> -n <ns>  # Detailed view with events
kubectl top pods -n <ns>             # Check metrics server works
```

---

## Resource Tuning

### Understanding CPU Units

| Value | Meaning |
|-------|---------|
| `1` | 1 full CPU core |
| `500m` | 0.5 CPU core |
| `100m` | 0.1 CPU core |
| `50m` | 0.05 CPU core |

**CPU is compressible**: hitting the limit means throttling (slower), not
termination.

### Understanding Memory Units

| Value | Meaning |
|-------|---------|
| `512Mi` | 512 mebibytes (536,870,912 bytes) |
| `1Gi` | 1 gibibyte (1,073,741,824 bytes) |
| `256M` | 256 megabytes (256,000,000 bytes) |

**Memory is incompressible**: exceeding the limit means OOMKill (terminated
and restarted).

### Tuning Strategy

1. **Start conservative** — requests at estimated typical usage, limits at
   2–4x requests
2. **Observe** — use `kubectl top pods` and monitoring dashboards
3. **Adjust** — reduce if unused, increase if OOMKills occur

```bash
kubectl top pods -n <ns>                                  # Current usage
kubectl get events -n <ns> --field-selector reason=OOMKilling  # OOM events
kubectl describe pod <name> -n <ns> | grep -A5 "Limits\|Requests"
```

### QoS Classes

K8s assigns Quality of Service based on resource configuration:

| QoS Class | Condition | Eviction Priority |
|-----------|-----------|-------------------|
| Guaranteed | requests == limits for all containers | Last to evict |
| Burstable | requests < limits (or only one set) | Middle |
| BestEffort | No requests or limits set | First to evict |

For production databases, use Guaranteed (requests == limits) to minimize
eviction risk.

---

## Pod Disruption Budgets

PDBs protect availability during voluntary disruptions (node drain, cluster
upgrade). They don't protect against involuntary disruptions (node crash).

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: api-pdb
  namespace: my-project
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: my-api
```

Or as a percentage:

```yaml
spec:
  maxUnavailable: 30%
```

**When to use**: multi-replica deployments during node drains/upgrades.
Not useful for single-replica deployments.

---

## Chaos Testing Patterns

Chaos testing validates that your system self-heals as designed.

### Manual Pod Kill

```bash
kubectl -n <ns> delete pod <pod-name>           # Kill specific pod
kubectl -n <ns> delete pods -l app=my-api       # Kill by label
kubectl -n <ns> get pods -w                     # Watch recovery
```

### Automated Chaos Script

```bash
#!/usr/bin/env bash
# chaos-test.sh — Random pod kills over a duration
set -euo pipefail

NAMESPACE="${1:?Usage: chaos-test.sh <namespace>}"
DURATION_HOURS="${2:-24}"
INTERVAL_SECONDS="${3:-7200}"

LABELS=("app=my-api" "app=my-web" "app=redis" "app=postgres")
END_TIME=$(($(date +%s) + DURATION_HOURS * 3600))

while [ "$(date +%s)" -lt "$END_TIME" ]; do
    TARGET=${LABELS[$RANDOM % ${#LABELS[@]}]}
    POD=$(kubectl -n "$NAMESPACE" get pods -l "$TARGET" -o name | head -1)

    if [ -n "$POD" ]; then
        echo "[$(date -Iseconds)] Killing $POD"
        kubectl -n "$NAMESPACE" delete "$POD" --grace-period=0
    fi

    sleep "$INTERVAL_SECONDS"
done
```

### Verification After Chaos

```bash
# 1. Pod restarts within expected time
kubectl -n <ns> get pods -w

# 2. Service endpoints recover
kubectl -n <ns> get endpoints

# 3. Data persists (stateful services)
kubectl -n <ns> exec deploy/postgres -- psql -U postgres -d mydb \
  -c "SELECT count(*) FROM some_table;"

# 4. Application serves requests
curl -s http://localhost:<nodeport>     # Web responds
curl -s http://localhost:8000/healthz   # API healthy
```

### Success Criteria

| Metric | Target | How to Measure |
|--------|--------|----------------|
| Pod recovery | < 30s | Time between delete and Ready |
| Data persistence | 100% | Row counts before/after kill |
| Uptime | > 99.9% | (total - downtime) / total |
| Unrecoverable failures | 0 | No stuck CrashLoopBackOff |

---

## Monitoring & Observability

### Built-in kubectl Commands

```bash
# Cluster health
kubectl get nodes
kubectl top nodes

# Namespace overview
kubectl -n <ns> get all

# Pod resource usage
kubectl top pods -n <ns>

# Events (scheduling, probe failures, OOMKill)
kubectl -n <ns> get events --sort-by='.lastTimestamp'

# Logs
kubectl -n <ns> logs deploy/my-api --tail=100 -f        # Follow logs
kubectl -n <ns> logs deploy/my-api --previous            # Previous crash
kubectl -n <ns> logs deploy/my-api -c run-migrations     # Init container
```

### Health Check Verification

```bash
# Port-forward to reach ClusterIP services locally
kubectl -n <ns> port-forward svc/my-api 8000:8000 &

# Test probes
curl -s localhost:8000/healthz | jq .
curl -s localhost:8000/ready | jq .
```

### Resource Monitoring

```bash
kubectl -n <ns> get hpa -w                        # Watch HPA decisions
kubectl -n <ns> describe pod <name> | grep -i throttl  # CPU throttling
kubectl -n <ns> describe pod <name> | grep -i oom      # Memory pressure
```

---

## Production Readiness Checklist

### Workloads
- [ ] All containers have resource requests AND limits
- [ ] All long-running containers have liveness probes
- [ ] All traffic-serving containers have readiness probes
- [ ] Liveness probes check process health only (no dependency checks)
- [ ] Readiness probes check all critical dependencies
- [ ] `initialDelaySeconds` appropriate for startup time
- [ ] Rolling update strategy configured (maxSurge, maxUnavailable)
- [ ] Database deployments use `strategy: Recreate`

### Security
- [ ] No real secrets committed to source control
- [ ] Containers run as non-root
- [ ] `allowPrivilegeEscalation: false` on all containers
- [ ] Sensitive data in Secrets, not ConfigMaps

### Storage
- [ ] Stateful services have PVCs
- [ ] PVC sizes appropriate for data growth
- [ ] PGDATA uses subdirectory (avoids `lost+found` conflict)
- [ ] Cache persistence enabled if durability required

### Networking
- [ ] Correct service types (ClusterIP internal, NodePort/LB external)
- [ ] All ports named in Service specs
- [ ] Labels consistent between Deployments and Services

### Scaling
- [ ] HPA targets have CPU requests defined
- [ ] Metrics Server available in cluster
- [ ] HPA behavior tuning prevents flapping
- [ ] PDBs protect multi-replica deployments

### Observability
- [ ] Structured logging with correlation IDs
- [ ] Log levels configurable via ConfigMap
- [ ] Health endpoints document dependency status
- [ ] Events and logs accessible for debugging

---

## Common Pitfalls

### HPA Not Scaling

**Symptoms**: HPA shows `<unknown>` for current utilization.

**Cause**: Metrics Server missing, or pods lack CPU requests.

```bash
kubectl get hpa -n <ns>                              # TARGETS: <unknown>/70%
kubectl get deployment metrics-server -n kube-system  # Is it running?
```

### HPA Flapping

**Symptoms**: Replicas constantly scaling up and down.

**Cause**: No stabilization window, or threshold too close to baseline.

**Fix**: Add behavior tuning and set target utilization 15–20% above normal.

### OOMKill During Spikes

**Symptoms**: Pods restart with exit code 137 during high load.

**Cause**: Memory limit too close to baseline.

**Fix**: Increase limits or add HPA on memory:
```yaml
metrics:
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### Pods Evicted

**Symptoms**: Pods terminated with reason `Evicted`.

**Cause**: Node under disk/memory pressure.

**Fix**:
1. Set resource requests to improve scheduling
2. Use Guaranteed QoS (requests == limits) for critical workloads
3. Add PDBs for graceful eviction handling
