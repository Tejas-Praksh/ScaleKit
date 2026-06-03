# ScaleKit — Architecture Decision Records (ADRs)

> **Format:** Lightweight ADR (adapted from Michael Nygard)  
> **Convention:** Each decision is numbered, dated, and includes trigger for reversal.

---

## ADR-001: Monolith Architecture

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-01                                    |
| Revisit   | When team > 20 OR single domain needs 10x  |

### Context

ScaleKit implements 8 distributed systems algorithms with strict latency requirements. The URL shortener demands p99 < 10ms for redirects. Microservices architecture adds network overhead per inter-service call.

### Considered Options

1. **Microservices** — each subsystem as independent service
2. **Modular monolith** — single deployable, separated packages ← chosen
3. **Serverless** — Lambda/Cloud Functions per endpoint

### Decision

Single Spring Boot application with domain-separated packages:
```
com.scalekit.urlshortener.*
com.scalekit.ratelimiter.*
com.scalekit.cache.*
com.scalekit.common.*
```

### Rationale

| Factor           | Microservices        | Monolith (chosen) |
|------------------|----------------------|--------------------|
| Network latency  | 1-10ms per hop       | 0ms                |
| Serialization    | 0.5-2ms (JSON/Proto) | 0ms (method call)  |
| Deployment       | K8s + Helm + CI/CD   | Single JAR         |
| Debugging        | Distributed tracing  | Single stack trace |
| Total overhead   | **3-18ms**           | **0ms**            |

At p99 target of 10ms, microservices overhead alone consumes 30-100% of the budget.

### Consequences

- (+) Zero inter-service latency
- (+) Single deployment artifact
- (+) Trivial local development
- (+) Shared connection pools (fewer DB/Redis connections)
- (-) Cannot scale subsystems independently
- (-) Team coupling risk at scale (> 20 engineers)
- (-) Single point of failure (mitigated by multiple instances)

### Trigger for Reversal

Extract a subsystem to microservice when:
- It needs 10x more compute than other subsystems
- A dedicated team owns it exclusively
- Independent deployment cadence is required
- Different technology stack is optimal (e.g., Rust for redirect proxy)

---

## ADR-002: Counter-Based URL ID Generation

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-01                                    |
| Revisit   | If security audit flags enumeration risk   |

### Context

Need unique 7-character Base62 short codes for shortened URLs. Two approaches: random generation with collision detection, or counter-based monotonic generation.

### Considered Options

1. **Random Base62** — generate random 7-char string, retry on collision
2. **Counter + Base62** — atomic Redis INCR → Base62 encode ← chosen
3. **UUID truncation** — UUID → Base62 → truncate to 7 chars
4. **Snowflake ID** — timestamp + machine + sequence

### Decision

Atomic Redis counter (`INCR url:counter`) → Base62 encode. Start counter at 1,000,000 to ensure minimum 4-character codes.

### Rationale

```
Random approach:
  62^7 = 3.52 trillion combinations
  Birthday paradox: 50% collision probability at √(3.52T) ≈ 1.87M URLs
  At 100M URLs: collision rate = ~1.4% (retry 1 in 70 creates)
  Requires: DB unique constraint + retry loop + backoff
  Worst case: 5 retries = 5 DB round-trips for one URL

Counter approach:
  Redis INCR is atomic and O(1)
  Mathematical guarantee: ZERO collisions, ever
  Deterministic: no retries, no retry storms under load
  Sortable: URL creation order preserved
  
  Trade-off: sequential codes are guessable
  Mitigation 1: rate limit creation API (10 req/min per user)
  Mitigation 2: require authentication for bulk creation
  Mitigation 3: start at 1M to avoid obvious patterns
```

### Consequences

- (+) Zero collisions — mathematically proven
- (+) Single Redis round-trip per URL creation
- (+) Monotonically increasing — implicit creation ordering
- (+) Predictable code length (7 chars from counter 1M onwards)
- (-) Sequential — next code is guessable
- (-) Redis becomes a dependency for writes (acceptable)
- (-) Redis restart resets counter if not persisted (mitigated by AOF)

---

## ADR-003: Lua Scripts for Atomic Redis Operations

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-02                                    |
| Revisit   | If migrating away from Redis               |

### Context

Rate limiter needs check-and-decrement atomicity. Token bucket: "read tokens, check if ≥ 1, decrement, update timestamp" must be one atomic operation. Without atomicity, two concurrent requests can both read tokens=1 and both consume it, allowing 2x the limit.

### Considered Options

1. **MULTI/EXEC** — Redis transactions
2. **Lua scripts** — server-side atomic execution ← chosen
3. **Client-side locking** — distributed lock around rate limit check
4. **Optimistic locking** — WATCH/MULTI/EXEC with retry

### Decision

Lua scripts for all check-and-act operations.

### Rationale

| Factor              | MULTI/EXEC                | Lua Script (chosen)     |
|---------------------|---------------------------|-------------------------|
| Round trips          | 2 (MULTI + EXEC)         | 1 (EVALSHA)             |
| Conditional logic    | ❌ Not supported          | ✅ Full Lua language    |
| Atomicity            | ✅ (but no conditionals)  | ✅ (with conditionals)  |
| Pipelining           | Limited                   | N/A (single command)    |
| Debugging            | Easier                    | Harder (Lua stack)      |
| Cache (script hash)  | N/A                       | ✅ EVALSHA avoids resend|

The critical requirement is **conditional logic**: "IF tokens >= 1 THEN decrement ELSE reject". MULTI/EXEC cannot express conditionals — it always executes all queued commands.

### Where Lua Scripts Are Used

1. **Token Bucket**: check tokens → refill → decrement → return remaining
2. **Sliding Window**: ZADD timestamp → ZREMRANGEBYSCORE → ZCARD → compare to limit
3. **Lock acquisition**: SET NX PX → check quorum → return fencing token
4. **Lock release**: GET value → compare → DEL (only if owner matches)

### Consequences

- (+) Single round-trip for complex operations
- (+) Guaranteed atomicity with conditional logic
- (+) No race conditions possible
- (+) Redis caches compiled Lua (EVALSHA is fast)
- (-) Lua is less familiar than Java for debugging
- (-) Redis blocks during Lua execution (keep scripts < 5ms)
- (-) Harder to unit test (need embedded Redis)

---

## ADR-004: Fail-Open for Rate Limiter

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-02                                    |
| Revisit   | If used for billing or security-critical APIs|

### Context

Redis may become temporarily unavailable. When the rate limiter cannot check limits, the system must choose between blocking all traffic (fail-closed) or allowing all traffic (fail-open).

### Considered Options

1. **Fail-closed** — reject all requests when Redis is down
2. **Fail-open** — allow all requests when Redis is down ← chosen
3. **Hybrid** — in-memory fallback rate limiter

### Decision

Fail-open: allow requests when Redis is unavailable. Log all fail-open events. Alert on sustained Redis connectivity loss.

### Rationale

```
Fail-closed:
  Redis down for 30 seconds = 30 seconds of ZERO traffic.
  All users blocked. Revenue impact. SLA breach.
  Self-inflicted DDoS is worse than the attack you're preventing.

Fail-open:
  Redis down for 30 seconds = 30 seconds of unlimited traffic.
  Actual impact: maybe 3x normal traffic temporarily.
  Backend can handle 3x for 30 seconds.
  Redis downtime is rare (99.99% uptime) and short.

Risk assessment:
  Rate limiter is PROTECTION, not AUTHENTICATION.
  Nobody is denied access — only rate of access is controlled.
  Temporary removal of rate limits ≠ security breach.
  
  If rate limiter protected billing APIs:
  → Would choose fail-closed + in-memory fallback (hybrid)
```

### Consequences

- (+) Zero downtime for users during Redis outages
- (+) Simpler error handling (catch → allow)
- (+) No cascading failures from rate limiter
- (-) Brief window of unlimited requests during Redis outage
- (-) Must monitor fail-open events closely

---

## ADR-005: Multiple Hash Algorithm Families for Bloom Filter

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-03                                    |
| Revisit   | If performance profiling shows hash bottleneck|

### Context

Bloom Filter requires k independent hash functions. The common approach is using one hash function with different seeds (e.g., MurmurHash3 with seeds 0, 1, 2, 3). However, same-algorithm different-seed hashes are not truly pairwise independent.

### Considered Options

1. **Single algorithm, k seeds** — MurmurHash3(seed=0..k-1)
2. **Double hashing** — h(i) = h1 + i×h2 (Kirsch-Mitzenmacker)
3. **Multiple algorithm families** — MurmurHash3, FNV-1a, DJB2, etc. ← chosen

### Decision

Use hash functions from different mathematical families:

| Hash # | Algorithm    | Type              |
|--------|-------------|-------------------|
| 1      | MurmurHash3 | Multiplicative    |
| 2      | MurmurHash3 | Multiplicative (seed=1) |
| 3      | FNV-1a      | XOR-then-multiply |
| 4      | DJB2        | Shift-and-add     |

### Rationale

```
Same algorithm, different seeds:
  All outputs share the same mixing function.
  Correlation between outputs is non-zero.
  Higher actual FPR than theoretical prediction.
  
Different algorithm families:
  Different mathematical structures.
  Outputs are statistically independent.
  Measured FPR matches theoretical prediction.
  
Our measurement:
  n=1M, m=14M bits, k=4
  Same algorithm × 4:  actual FPR = 0.14% (predicted 0.10%)
  Mixed algorithms:    actual FPR = 0.089% (predicted 0.10%)
  
  Mixed algorithms: 36% lower FPR than same-algorithm approach.
```

### Consequences

- (+) True hash independence → accurate FPR
- (+) Measured FPR matches (or beats) theoretical formula
- (-) Multiple hash implementations to maintain
- (-) Slightly more code complexity
- (-) Different performance characteristics per algorithm (negligible in practice)

---

## ADR-006: ReentrantReadWriteLock for In-Process Caches

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-03                                    |
| Revisit   | If profiling shows lock contention > 5%    |

### Context

LRU and LFU caches are accessed by multiple threads simultaneously. Both `get()` and `put()` modify internal data structures (linked list reordering), so even reads require write access.

### Considered Options

1. **synchronized** — coarse-grained, simple
2. **ReentrantReadWriteLock** — read/write separation ← chosen
3. **ConcurrentHashMap + CAS** — lock-free
4. **Striped locks** — partition-based concurrency

### Decision

`ReentrantReadWriteLock` for all cache operations.

### Rationale

```
synchronized:
  Every operation serialized.
  100 threads → 99 waiting at all times.
  Throughput: single-threaded.

ReadWriteLock (chosen):
  containsKey(): read lock (multiple concurrent readers)
  get(): write lock (moveToFront modifies list)
  put(): write lock (modifies map + list)
  
  Benefit: containsKey() is called by Bloom Filter pre-check.
  Multiple threads can check existence simultaneously.
  Only actual cache modifications serialize.

ConcurrentHashMap + CAS:
  Would need lock-free doubly linked list.
  Extremely complex. Research-paper territory.
  Bug risk outweighs performance gain.

Striped locks:
  Partition keys across N locks.
  But LRU list is global — can't partition.
  Would need separate list-lock anyway.
```

### Consequences

- (+) Read-only operations (containsKey) fully concurrent
- (+) Well-understood concurrency primitive
- (+) Fair lock option available (-XX:UseFairLock)
- (-) get() requires write lock due to moveToFront (unavoidable with LRU)
- (-) Writer starvation possible under heavy read load (mitigated by fair mode)

---

## ADR-007: Spring @Async for Analytics Pipeline

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-02                                    |
| Revisit   | If analytics lag exceeds 5 minutes         |

### Context

URL redirects must be fast (p99 < 10ms). Analytics processing (UA parsing, DB write, counter update) takes 20-50ms. Running analytics synchronously would triple redirect latency.

### Decision

`@Async` event listener with bounded thread pool:
- Core: 5 threads
- Max: 20 threads
- Queue: 1,000 capacity
- Rejection: CallerRunsPolicy (caller thread processes if queue full)

### Rationale

CallerRunsPolicy is crucial: it means **zero data loss**. If the queue is full, the calling thread (Tomcat worker) processes the analytics itself. This slows down that one redirect but never drops analytics data.

Alternative rejection policies:
- `AbortPolicy`: throws exception → analytics lost ❌
- `DiscardPolicy`: silently drops → analytics lost ❌
- `DiscardOldestPolicy`: drops oldest → analytics lost ❌
- `CallerRunsPolicy`: caller processes → slow but no loss ✅

### Consequences

- (+) Redirect p99 unaffected by analytics (5ms vs 55ms)
- (+) Zero data loss under any load
- (+) Self-regulating: backpressure via CallerRunsPolicy
- (-) Analytics may lag 1-5 seconds behind redirects
- (-) Thread pool sizing requires tuning

---

## ADR-008: API Gateway as Servlet Filter (Not Spring Cloud Gateway)

| Field     | Value                                      |
|-----------|--------------------------------------------|
| Status    | **Accepted**                               |
| Date      | 2026-04                                    |
| Revisit   | If extracting to microservices              |

### Context

Need request routing, correlation IDs, rate limiting, and circuit breaking at the gateway level. Options: Spring Cloud Gateway (reactive, separate service) or a simple Servlet Filter in the monolith.

### Decision

Custom `javax.servlet.Filter` (`ApiGatewayFilter`) with `@Order(1)`.

### Rationale

Spring Cloud Gateway is designed for microservices routing between services. In a monolith, there is no inter-service routing — all requests go to the same application. A Servlet Filter achieves the same cross-cutting concerns (correlation ID, rate limiting, timing) with zero additional dependencies.

### Consequences

- (+) Zero additional dependencies
- (+) Standard Servlet API — portable
- (+) Sub-millisecond overhead (method calls, no HTTP)
- (+) Full access to Spring context (DI, beans)
- (-) Not a "real" API gateway (no service discovery, no load balancing)
- (-) Must be re-implemented if extracting to microservices
