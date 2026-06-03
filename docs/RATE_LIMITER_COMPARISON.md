# ScaleKit Rate Limiter Algorithm Comparison

ScaleKit implements two industrial-grade distributed rate limiting algorithms from scratch, utilizing Redis for coordinate-free precision and clustering readiness. This document analyzes the architectural paradigms, mathematical memory implications, burst behaviors, and technical tradeoffs of the **Token Bucket** and **Sliding Window Log** algorithms.

---

## Architectural Comparison Matrix

| Feature | Token Bucket | Sliding Window Log (Redis Sorted Sets) | Fixed Window Counter |
| :--- | :---: | :---: | :---: |
| **Space Complexity** | $O(1)$ constant memory | $O(N)$ linear memory per client (requests in window) | $O(1)$ constant memory |
| **Time Complexity** | $O(1)$ constant runtime | $O(\log N + M)$ where $M$ is expired logs to clean | $O(1)$ constant runtime |
| **Burst Capacity** | ✅ Allowed (up to bucket capacity) | ❌ strictly forbidden (rigidly capped) | ❌ spikes at window edge |
| **Edge Spikes** | ✅ Native protection | ✅ Native protection | ❌ Edge spikes (2x capacity possible) |
| **Redis Structure** | Redis Hash (`tokens`, `last_refill`) | Redis Sorted Set (`ZSET` containing UUIDs and timestamps) | Single Integer Counter (`INCR`) |
| **Redis Roundtrips** | 1 (Atomic Lua Script) | 1 (Atomic Lua Script) | 2 (`INCR` + `TTL`) |
| **Distributed Safety** | ✅ Absolute (Lua thread-safe) | ✅ Absolute (Lua thread-safe) | ✅ Absolute (Atomic increment) |
| **CPU Overhead** | Low (Simple math) | High (Set sorting, element removals, scores calculation) | Extremely Low |
| **Primary Use Cases** | Public APIs, high-throughput end-points | Payment gateways, strict/audit security gates | Simple user-tier tracking |

---

## Algorithm Architecture

### 1. Distributed Token Bucket (ScaleKit's Default)
A bucket holds up to `capacity` tokens. Tokens are continuously added at a fixed `refillRate` per second. Each request consumes one token. If the bucket is empty, the request is rejected. 

ScaleKit achieves complete distributed thread-safety and eliminates race-conditions by wrapping the refill math and token consumption into a single atomic Redis Lua script.

```
Capacity: 20 tokens  |  Refill: 1 token/sec

t=0   [████████████████████] 20 tokens → request allowed (19 left)
t=0   [███████████████████ ] 19 tokens → request allowed (18 left)
...
t=0   [                    ]  0 tokens → REJECTED (X-RateLimit-Remaining: 0)
t=1   [█                   ]  1 token  → request allowed (refilled)
```

* **Pros:** Permissible bursting (smooths out minor consumer network delays), constant $O(1)$ space, high execution performance.
* **Cons:** Approximates rolling windows under continuous heavy bursts.

---

### 2. Sliding Window Log (ScaleKit's Precision Guard)
Instead of aggregating requests, the sliding window log tracks the **precise timestamp** of every request. Each time a client requests an operation, ScaleKit cleans up all logs older than the rolling window (e.g. `now - 60 seconds`) and counts the remaining elements in the Sorted Set.

ScaleKit implements this precisely using Redis Sorted Sets (`ZSET`) where:
- **Score**: Millisecond timestamp of request (`now`).
- **Value**: Unique Request UUID (guarantees every request represents a unique element in the set).

```
Window Size: 60s | Max Limit: 5

Client logs: [t1, t2, t3, t4, t5]
Incoming request at `now`
1. Clean up logs before `now - 60s` -> Set becomes [t3, t4, t5]
2. Check size: ZCARD key -> size is 3 (less than 5)
3. ZADD `now` UUID -> Set becomes [t3, t4, t5, now] (allowed!)
4. X-RateLimit-Remaining is calculated as (Limit - ZCARD)
```

* **Pros:** Highly accurate rolling-window enforcement, completely prevents boundary-burst issues, zero burst tolerance (strict protection).
* **Cons:** Linear memory consumption $O(N)$ with respect to request volume. Expensive cleanup overhead.

---

## Memory Consumption Proofs (The Mathematical Gap)

The primary trade-off between Token Bucket and Sliding Window Log lies in **Redis memory consumption**. 

### 1. Token Bucket Memory Formula ($O(1)$)
Every rate limiter instance stores a single Redis Hash (`tb:{endpoint}:{identifier}`) with two fields:
- `tokens` (String/Double)
- `last_refill` (String/Int64)

Redis stores this under its internal hash structure, costing **~100 bytes** per client regardless of whether they make 10 requests or 1,000,000 requests.
$$\text{Memory}_{TB} = 100 \text{ bytes} \times \text{Clients}$$
- **1,000 Clients**: $100 \text{ KB}$
- **1,000,000 Clients**: $100 \text{ MB}$ (Extremely low footprint!)

### 2. Sliding Window Log Memory Formula ($O(N)$)
Sliding Window uses a Redis Sorted Set (`sw:{endpoint}:{identifier}`) per client. A `ZSET` uses a skiplist and a hash table under the hood. In Redis, each element in a ZSET occupies approximately **50-80 bytes** (depending on member size and allocator overhead). Let us assume a conservative average of **50 bytes** per log element.

$$\text{Memory}_{SW} = \text{Clients} \times \left( \text{Base ZSET Overhead (~250 bytes)} + (\text{Requests in Window} \times 50 \text{ bytes}) \right)$$

If an endpoint has a limit of **100 requests per minute**:
- **Memory per Client**: $250 + (100 \times 50) = 5,250 \text{ bytes} \approx 5.2 \text{ KB}$
- **1,000 Clients**: $5.2 \text{ MB}$
- **1,000,000 Clients**: $5.2 \text{ GB}$ (Massive RAM consumption difference!)

This mathematical proof explains why **Token Bucket is preferred for high-throughput public endpoints**, while **Sliding Window Log is reserved for strict, high-risk security gates (like password reset, OTP, or vulnerability safety checks)**.

---

## Running Benchmarks and Comparative Analysis

ScaleKit exposes concurrent load-testing and comparative analytics endpoints via the `BenchmarkController`:

### 1. Run Token Bucket Load Test
Executes a highly concurrent load test against the Token Bucket rate limiter.
```bash
curl -X POST "http://localhost:8080/api/benchmark/run?threads=50&requestsPerThread=200&endpoint=url-create"
```

### 2. Run Sliding Window Load Test
Executes a highly concurrent load test against the Sorted Set Sliding Window rate limiter.
```bash
curl -X POST "http://localhost:8080/api/benchmark/sliding-window?threads=50&requestsPerThread=200&endpoint=safety-check"
```

### 3. Run Comparative Benchmark
Executes a head-to-head performance analysis, measuring throughput, exact latency percentiles ($p_{50}, p_{95}, p_{99}$), decision counts, and live memory allocations:
```bash
curl -X POST "http://localhost:8080/api/benchmark/compare?threads=20&requestsPerThread=100&endpoint=api-global"
```

#### Example Comparative JSON Response:
```json
{
  "results": {
    "SLIDING_WINDOW": {
      "algorithm": "SLIDING_WINDOW",
      "threads": 20,
      "requestsPerThread": 100,
      "totalRequests": 2000,
      "allowed": 1000,
      "rejected": 1000,
      "totalDurationMs": 240.25,
      "throughputRps": 8324.6,
      "latency": {
        "p50Ms": 0.85,
        "p95Ms": 1.95,
        "p99Ms": 3.42
      }
    },
    "TOKEN_BUCKET": {
      "algorithm": "TOKEN_BUCKET",
      "threads": 20,
      "requestsPerThread": 100,
      "totalRequests": 2000,
      "allowed": 1200,
      "rejected": 800,
      "totalDurationMs": 115.40,
      "throughputRps": 17331.0,
      "latency": {
        "p50Ms": 0.42,
        "p95Ms": 1.10,
        "p99Ms": 1.98
      }
    }
  },
  "winner": "TOKEN_BUCKET",
  "recommendation": "TOKEN_BUCKET is generally recommended for highly scaled public endpoints due to its burst capacity and O(1) space complexity. SLIDING_WINDOW is recommended for strict low-volume operations.",
  "memoryAnalysis": [
    {
      "algorithm": "TOKEN_BUCKET",
      "memoryPerUserBytes": 100,
      "memoryFor1000UsersKb": 100,
      "memoryFor1MUsersGb": 0.095,
      "explanation": "Extremely memory efficient. Stores exactly two numbers (token count and timestamp) inside a single Redis Hash per user. Memory is constant (O(1)) regardless of request volume."
    },
    {
      "algorithm": "SLIDING_WINDOW",
      "memoryPerUserBytes": 5000,
      "memoryFor1000UsersKb": 5000,
      "memoryFor1MUsersGb": 4.76,
      "explanation": "High memory footprint. Uses a Redis Sorted Set (ZSET) per user, storing a timestamp and unique request ID for every single request within the active window (O(N) memory per user)."
    }
  ]
}
```

---

## Architectural Recommendation Summary

1. **Use Token Bucket (`TOKEN_BUCKET`) for:**
   - Public API Gateway layers (`api-global`).
   - High-throughput redirection links (`url-redirect`).
   - Consumer mobile/web application actions that may occasionally burst naturally due to rapid UI interactions.

2. **Use Sliding Window (`SLIDING_WINDOW`) for:**
   - Strict safety/vulnerability heuristics checks (`safety-check`).
   - Financial transactions or withdrawal gateways.
   - Brute-force protection end-points (login submission, password-reset, OTP delivery).
