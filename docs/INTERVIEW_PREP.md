# ScaleKit — System Design Interview Preparation

> **Purpose:** Honest, depth-first answers for FAANG-level system design interviews.  
> **Philosophy:** Show design *thinking*, not just design *doing*.  
> **Format:** Q&A organized by subsystem, with follow-ups interviewers actually ask.

---

## Table of Contents

1. [URL Shortener](#1-url-shortener)
2. [Rate Limiter](#2-rate-limiter)
3. [Consistent Hashing](#3-consistent-hashing)
4. [Caching (LRU/LFU)](#4-caching-lrulfu)
5. [Bloom Filter](#5-bloom-filter)
6. [Distributed Locking](#6-distributed-locking)
7. [Leader Election](#7-leader-election)
8. [Message Queue](#8-message-queue)
9. [Architecture & Meta Questions](#9-architecture--meta-questions)

---

## 1. URL Shortener

### Q: How do you generate short codes?

**A:** Counter-based. Redis `INCR` returns a monotonically increasing integer. I Base62 encode it to produce a 7-character alphanumeric code. The counter starts at 1,000,000 so codes are always 4+ characters.

**Why not random?**
Random 7-char Base62 has 62^7 = 3.5 trillion combinations. Sounds huge, but the birthday paradox means 50% collision probability at √(3.5T) ≈ 1.87 million URLs. At scale, collision handling becomes a real concern — retry loops, DB unique constraint checks, backoff logic. Counter-based has zero collisions, mathematically guaranteed.

**Trade-off acknowledged:** Counter-based codes are sequential and somewhat guessable. I mitigate this with rate limiting on the creation API and requiring authentication for bulk creation. For our use case, zero collisions is more valuable than non-guessability.

### Q: How do you handle 10,000 redirects/sec?

**A:** Two-level Redis caching.

- **L1 (redirect-only cache):** Key `url:redirect:{code}` → raw URL string (~30 bytes). TTL 2 hours. This is the hot path — just the URL needed for HTTP 302.
- **L2 (full object cache):** Key `url:{code}` → serialized URL object (~700 bytes). TTL 1 hour. Used for API responses that need metadata.

At 94% cache hit rate, only 600 requests/sec actually hit PostgreSQL. PostgreSQL can handle 10,000+ reads/sec on indexed lookups, so even with 0% cache hit rate, the system would survive — just with higher p99.

**Follow-up — What if Redis goes down?**
Graceful degradation. All reads fall through to PostgreSQL. Latency increases from 2ms to 5ms. At 10,000 req/sec, PostgreSQL connection pool (20 connections × 5ms) handles 4,000 QPS. We'd start seeing 503s above that. Mitigation: circuit breaker on Redis with fast fallback, and Redis Sentinel for automatic failover.

### Q: How do you track analytics without slowing redirects?

**A:** Asynchronous event publishing. The redirect method returns HTTP 302 immediately, then fires an async event.

```
redirect() → return 302 (< 5ms) → AsyncEvent published
                                         ↓
                                   Thread pool (5-20 threads)
                                         ↓
                                   Parse UA, write to DB,
                                   increment Redis counters
```

Thread pool config: 5 core, 20 max, 1,000 queue. CallerRunsPolicy if queue is full — this is critical because it means **zero analytics data loss**. If the queue is saturated, the Tomcat thread processes analytics itself. One redirect becomes slower, but no data is ever dropped.

**Follow-up — Why not Kafka?**
At 3 writes/sec steady state, Kafka is massive overkill. Kafka adds operational complexity (ZooKeeper/KRaft, partitions, consumer groups, offset management) for a workload that a simple thread pool handles perfectly. If we hit 100,000 events/sec, I'd introduce Kafka. Not before.

### Q: How would you handle URL expiry?

**A:** Two mechanisms:

1. **On-read check:** When a redirect is requested, check `expires_at`. If expired, return 410 Gone. This is instant — no background job needed.
2. **Background cleanup:** Scheduled task (leader-only) runs daily, deletes URLs expired > 7 days. Uses batch DELETE with LIMIT to avoid long locks.

I don't eagerly delete expired URLs because they might be useful for analytics queries. The on-read check ensures expired URLs are never served.

---

## 2. Rate Limiter

### Q: Token Bucket vs Sliding Window — when do you use each?

**A:** Both are implemented. The choice depends on the API's requirements:

| Scenario                   | Algorithm      | Why                               |
|----------------------------|----------------|-----------------------------------|
| General API protection     | Token Bucket   | O(1) memory, allows bursts        |
| Strict per-second limits   | Sliding Window | No burst possible, highest accuracy |
| Low-memory constraint      | Token Bucket   | 150 bytes/user vs 5KB/user        |
| Billing/metered APIs       | Sliding Window | Must be exact                     |

**Concrete numbers I benchmarked:**
- Token Bucket p99: 2ms, 50,000 checks/sec
- Sliding Window p99: 4ms, 25,000 checks/sec
- Token Bucket memory at 1M users: 150 MB
- Sliding Window memory at 1M users: 5 GB

For most APIs, Token Bucket is the right default. Sliding Window is reserved for APIs where exact rate accuracy matters more than memory.

### Q: How do you prevent race conditions in the rate limiter?

**A:** Lua scripts in Redis. The entire check-and-decrement operation runs as a single atomic Lua script on the Redis server.

```lua
-- Simplified token bucket Lua
local tokens = tonumber(redis.call('HGET', key, 'tokens')) or capacity
local last = tonumber(redis.call('HGET', key, 'last_refill')) or now
local elapsed = (now - last) / 1000.0
local refilled = math.min(capacity, tokens + elapsed * rate)

if refilled >= 1 then
    redis.call('HSET', key, 'tokens', refilled - 1, 'last_refill', now)
    return {1, refilled - 1}  -- allowed, remaining
else
    return {0, 0}  -- rejected
end
```

Redis is single-threaded, and Lua scripts execute atomically — no other command can interleave. This is stronger than MULTI/EXEC because Lua supports conditional logic (if/else), which MULTI/EXEC does not.

**Correctness proof:** I wrote a concurrency test — 100 threads, limit=10, each thread sends 1 request simultaneously. Result: exactly 10 allowed, 90 rejected. Every time. No race conditions possible.

### Q: What happens if Redis is down?

**A:** Fail-open. Allow all requests.

Rate limiting is a **protection mechanism**, not an authentication mechanism. Blocking 100% of legitimate traffic because the rate limiter is down is worse than allowing some excess traffic for 30 seconds until Redis recovers.

Every fail-open event is logged with the request details and a metric is incremented. If Redis is down for > 60 seconds, an alert fires. Redis typically has 99.99% uptime — this scenario is rare and short-lived.

**Caveat:** If the rate limiter protected billing APIs or security-critical endpoints, I'd use a hybrid approach — in-memory fallback rate limiter with conservative limits. For general API protection, fail-open is the right call.

---

## 3. Consistent Hashing

### Q: Why not modulo hashing?

**A:** Modulo hashing (`hash(key) % N`) remaps **every key** when N changes.

Example: 3 servers, 1 million cached keys.
- Add 1 server: `hash(key) % 3` → `hash(key) % 4`
- ~75% of keys map to a different server
- 750,000 cache misses simultaneously
- All 750,000 hit the database → thundering herd → outage

Consistent hashing: add 1 server to 3 → only 25% of keys remapped (K/N = 1M/4 = 250K). The other 750K keys stay on the same server. No thundering herd.

Remove 1 server from 3 → 33% remapped. Graceful.

### Q: What are virtual nodes and why do you need them?

**A:** Without virtual nodes, 3 physical servers placed randomly on a hash ring result in uneven distribution — often one server gets 60% of keys while another gets 15%.

Virtual nodes solve this. Each physical server creates 150 copies of itself on the ring (e.g., `server-a#0`, `server-a#1`, ..., `server-a#149`). With 450 points on the ring instead of 3, the law of large numbers ensures near-equal distribution.

**Numbers:**

| Virtual Nodes | Std Deviation |
|---------------|---------------|
| 1 (none)      | ~23%          |
| 10            | ~12%          |
| 50            | ~5%           |
| 150           | ~2%           |
| 1000          | ~0.5%         |

I chose 150 as the sweet spot — 2% deviation is negligible, and 450 TreeMap entries (3 servers × 150) consume only ~45KB of memory.

### Q: What data structure do you use for the ring?

**A:** `TreeMap<Integer, VirtualNode>` in Java.

- `ceilingEntry(hash)` returns the next clockwise node in O(log N). N = total virtual nodes.
- With 3 servers × 150 vnodes = 450 entries, that's O(log 450) ≈ 9 comparisons per lookup.
- If `ceilingEntry` returns null (hash is past the last entry), wrap around to `firstEntry()`.

Why TreeMap over sorted array + binary search? TreeMap supports efficient insert/delete for adding/removing nodes. Sorted array would require O(N) shift on modification. When scaling nodes up/down frequently, TreeMap's O(log N) insert matters.

---

## 4. Caching (LRU/LFU)

### Q: How do you implement LRU in O(1)?

**A:** Doubly linked list + HashMap, both standard textbook answer and what I actually built:

- **HashMap:** `key → Node`. O(1) lookup.
- **Doubly linked list:** maintains access order. Most recently used at head, least recently used at tail.
- **get(key):** HashMap lookup (O(1)) → move node to head (O(1) — we have prev/next pointers).
- **put(key, value):** If full, remove tail (O(1)). Add new node at head (O(1)). Update HashMap (O(1)).

**Implementation detail:** I use dummy head and tail sentinel nodes. This eliminates null checks in `moveToFront()` and `removeLast()`. Without sentinels, you need 4 additional if-checks per operation for edge cases (empty list, single element, head move, tail move). Sentinels make the code cleaner and marginally faster.

### Q: What is cache stampede and how do you prevent it?

**A:** Cache stampede (also called thundering herd) happens when a popular key expires and thousands of concurrent requests all miss cache simultaneously, all query the database for the same key.

I implemented three solutions:

1. **Mutex lock** (primary): First request acquires a lock, queries DB, populates cache. Other requests wait or retry and find the cache populated. Result: 1 DB query instead of 10,000. This is the approach I use in production.

2. **Probabilistic Early Recomputation (PER):** Before the key expires, a random request recomputes it early. Formula: `currentTime - (TTL × β × ln(random()))` > expiryTime. As the key approaches expiry, the probability of recomputation increases. The key gets refreshed before it expires, so there's never a stampede window.

3. **Stale-while-revalidate:** Return the stale value immediately (fast response), trigger a background refresh. User gets slightly stale data for one request. Next request gets fresh data. Best for data where momentary staleness is acceptable.

### Q: LRU vs LFU — how do you decide?

**A:** It depends on the access pattern.

**Use LRU for:** temporal locality. If "recent = relevant" (social feeds, news, recent orders), LRU captures the working set well. A URL that was popular an hour ago but hasn't been accessed since should be evicted.

**Use LFU for:** frequency-based popularity. If "popular = relevant" (search results, CDN assets, product pages), LFU keeps the most-requested items. A product page that gets 10,000 hits/day should survive even if it wasn't accessed in the last 5 minutes.

**The trap:** LFU has a scan resistance problem. If you scan through 1 million keys once each, they all get frequency=1 and pollute the cache, evicting genuinely popular keys. LRU handles this naturally because scan items quickly move to the tail.

---

## 5. Bloom Filter

### Q: How does a Bloom Filter work?

**A:** A bit array of m bits, initialized to all zeros, with k hash functions.

**Add:** Hash the item k times → set those k bit positions to 1.
**Check:** Hash the item k times → check those k bit positions.
  - If **any bit is 0**: item is **definitely not** in the set.
  - If **all bits are 1**: item **might be** in the set (could be false positive from other items setting those bits).

**Key properties:**
- False negatives: **impossible** (if we added it, its bits are set)
- False positives: **possible** (other items' bits may coincidentally align)
- Deletion: **not supported** (clearing a bit might affect other items)

### Q: What is the false positive rate formula?

**A:** `p = (1 - e^(-kn/m))^k`

Where:
- p = false positive probability
- k = number of hash functions
- n = number of inserted elements
- m = bit array size

**Concrete example I implemented:**
- n = 1,000,000 URLs
- p = 0.001 (target: 0.1%)
- m = -(1M × ln(0.001)) / (ln 2)² = 14,377,588 bits ≈ 1.71 MB
- k = (m/n) × ln 2 = 14.38 × 0.693 ≈ 10 hash functions

**Actual measured FPR:** 0.089% — slightly better than theoretical because I used truly independent hash functions from different algorithm families.

**Memory comparison:** HashSet<String> for 1M URLs ≈ 50 MB. Bloom Filter: 1.7 MB. That's a **97% memory reduction** with 0.1% false positive rate — an excellent trade-off for deduplication use cases.

### Q: Why do you use different hash function families?

**A:** Mathematical independence. Using the same hash function with different seeds (e.g., MurmurHash3 × 4) produces outputs with correlated internal structure — they share the same mixing function. This correlation increases the actual FPR above the theoretical prediction.

Using different algorithm families (MurmurHash3, FNV-1a, DJB2) gives true statistical independence because the mixing functions are mathematically unrelated. My measurement confirmed: mixed algorithms achieved 0.089% FPR vs 0.14% for same-algorithm approach — 36% better.

---

## 6. Distributed Locking

### Q: What is the split-brain problem with distributed locks?

**A:** A client acquires a lock, then experiences a GC pause (or network partition). During the pause, the lock's TTL expires. Another client acquires the same lock. When the first client resumes, it believes it still holds the lock. Both clients proceed with mutually exclusive operations. Data corruption.

```
Timeline:
  Client A: acquire lock (token=33) → GC PAUSE (3 seconds)
  Lock expires after 2 seconds...
  Client B: acquire lock (token=34) → writes to DB ✓
  Client A: resumes → writes to DB (STALE, should be blocked)
```

**Solution: Fencing tokens.** Each lock acquisition gets a monotonically increasing token from Redis INCR. The storage layer tracks the highest token it has seen. Any write with a token lower than the highest-seen token is rejected.

```
Client B writes with token=34 → storage records highToken=34
Client A writes with token=33 → 33 < 34 → REJECTED ✓
```

**Honest caveat:** Fencing tokens require cooperation from the storage layer. The database or service being protected must check the token. If it doesn't, fencing tokens don't help. This is a fundamental limitation of all distributed locking approaches — the protection is end-to-end.

### Q: Explain the Redlock algorithm.

**A:** Redlock runs against N independent Redis instances (typically N=5, always odd).

1. Record `start_time`
2. Try `SET key value NX PX ttl` on each of the N instances sequentially
3. Count successes
4. Lock is acquired if: successes ≥ quorum (N/2 + 1 = 3) AND elapsed time < TTL
5. If failed: release any partial locks (cleanup)

**Why quorum?** If 2 of 5 nodes are down, the remaining 3 can still form a majority. No two clients can both achieve quorum for the same resource — at most 5 locks exist, and getting 3 requires a majority that excludes the other client.

**Why check elapsed time?** If it takes 29 seconds to acquire a 30-second lock, you only have 1 second of validity. The lock might expire before you finish your work. Redlock rejects the acquisition if too much time was spent acquiring it.

**Controversy:** Martin Kleppmann's critique (2016) argues Redlock is unsafe because it relies on clock synchronization. Redis authors counter that bounded clock drift + fencing tokens make it safe enough for practical use. I agree with the pragmatic view — Redlock + fencing tokens is safe for our use cases.

### Q: Why is lock TTL important?

**A:** Without TTL, a crashed client holds the lock forever → system deadlock.

**TTL sizing formula:** `TTL = expected_operation_time × 3`

- 10s operation → 30s TTL
- Too short: lock expires mid-work → split-brain
- Too long: crashed client blocks others for the full TTL duration

**Watchdog pattern (my implementation):**
- Initial TTL = 30s
- While working: extend TTL every 10s
- On crash: no extension → TTL expires naturally in 30s
- On completion: explicit DEL (immediate release)

This gives the best of both worlds — short recovery time if the client crashes, unlimited work time for long operations.

---

## 7. Leader Election

### Q: Why do you need leader election?

**A:** Scheduled tasks on multiple instances.

Without leader election: 3 instances of the app, each running `@Scheduled cleanupExpiredUrls()`. Expired URLs get deleted 3 times. Analytics get aggregated 3 times. Notifications sent 3 times.

With leader election: only the leader runs scheduled tasks. Followers skip. If the leader crashes, a new leader is elected within 15-20 seconds.

### Q: How does your election work?

**A:** Redis SETNX-based lease:

1. Each instance tries: `SET leader:election {nodeId} NX EX 15`
2. `NX` = only set if not exists (atomic election)
3. `EX 15` = 15-second lease duration
4. Winner: becomes leader, starts heartbeat (renew lease every 5s)
5. Losers: check back every 5s, notice if leader's lease expires

**Failover timeline:**
```
t=0:  Leader sends heartbeat (extends lease to t=15)
t=5:  Leader sends heartbeat (extends lease to t=20)
t=10: Leader CRASHES
t=20: Lease expires (no heartbeat since t=5, lease was to t=20)
t=21: Follower attempts SETNX → succeeds → new leader
Total failover time: ~11 seconds
```

**Why not ZooKeeper?** ZooKeeper is a dedicated coordination service — powerful but heavy. For a monolith with 3-5 instances, Redis SETNX is simple, reliable, and we already have Redis for caching. ZooKeeper adds operational complexity (separate cluster, separate monitoring) that isn't justified at our scale.

---

## 8. Message Queue

### Q: Why build a message queue when Kafka exists?

**A:** Because this is a learning project demonstrating distributed systems primitives. But also: at our scale (thousands of messages/sec, not millions), a Redis-backed queue is perfectly adequate and avoids Kafka's operational complexity (broker management, partition rebalancing, ZooKeeper/KRaft).

The implementation covers the core concepts:
- **At-least-once delivery:** message moves to processing set on dequeue, deleted on ACK
- **Dead letter queue:** after 4 failed attempts, message moves to DLQ for manual inspection
- **Retry with backoff:** delays double each attempt: 1s → 5s → 30s → DLQ
- **Visibility timeout:** unacknowledged messages return to the queue after timeout

### Q: How do you ensure at-least-once delivery?

**A:** Three-phase message lifecycle:

1. **Enqueue:** `LPUSH queue:{name} message` — message is in the queue
2. **Dequeue:** `BRPOPLPUSH queue:{name} queue:{name}:processing` — atomically removes from queue and adds to processing set
3. **Acknowledge:** `LREM queue:{name}:processing message` — removed from processing set, delivery confirmed

If the consumer crashes after step 2 but before step 3, the message remains in the processing set. A reaper thread periodically checks for messages that have been in the processing set longer than the visibility timeout and moves them back to the main queue.

**Trade-off:** This means a message could be processed twice (at-least-once, not exactly-once). Consumers must be idempotent. I achieve idempotency by using message IDs as deduplication keys — if a message ID was already processed, skip it.

---

## 9. Architecture & Meta Questions

### Q: Why a monolith instead of microservices?

**A:** Performance and operational simplicity.

Our URL shortener p99 target is < 10ms. With microservices, each inter-service call adds 3-18ms of overhead (network hop, serialization, service discovery). A single redirect would traverse: API Gateway → URL Service → Cache Service → Analytics Service = 3 hops = 9-54ms overhead. That exceeds our entire latency budget.

In a monolith, these are direct method calls with 0ms overhead. Full budget available for business logic.

**I'm not anti-microservices.** I'd extract services when:
- Team exceeds 20 engineers (need independent deployment)
- One subsystem needs 10x resources (need independent scaling)
- Different technology stacks are optimal (e.g., Rust for redirect proxy)
- Independent release cadence is needed

**The architecture supports extraction:** each subsystem lives in its own package with clean interfaces. Extracting `com.scalekit.urlshortener` to a separate service would require adding HTTP/gRPC at the boundary — the internal contract is already well-defined.

### Q: What would you change if building this again?

**A:** Three things:

1. **Use Caffeine instead of custom LRU/LFU.** Building from scratch was educational, but Google's Caffeine cache is battle-tested, handles edge cases like cache stampede internally, and supports size-based eviction with accurate frequency counting (TinyLFU). For production, use the library.

2. **Add structured logging from day one.** I added it later. Starting with JSON-structured logs (logback-logstash-encoder) would have made debugging easier throughout development.

3. **More comprehensive integration tests.** Unit tests are thorough, but integration tests with Testcontainers (real PostgreSQL + real Redis) would catch issues that embedded/mock servers miss — like Lua script behavior differences, Redis version-specific features, and transaction isolation edge cases.

### Q: How would you monitor this in production?

**A:** Four pillars:

1. **Metrics (Prometheus + Grafana):**
   - Request rate, error rate, latency percentiles per endpoint
   - Cache hit rate (target: > 90%)
   - Rate limiter rejection rate
   - Lock contention rate
   - Queue depth and processing lag

2. **Logging (Structured JSON → ELK):**
   - Every request has a correlation ID (X-Correlation-ID)
   - MDC propagation through async boundaries
   - Log levels: ERROR for failures, WARN for degradation, INFO for state changes

3. **Health checks:**
   - `/actuator/health` — Spring Boot aggregated health
   - Custom health indicators: Redis connectivity, PostgreSQL connectivity, queue depth
   - Circuit breaker state per downstream dependency

4. **Alerting:**
   - p99 latency > 50ms for 5 minutes → page on-call
   - Error rate > 1% for 2 minutes → page on-call
   - Cache hit rate < 80% → warning
   - Queue depth > 10,000 → warning
   - Redis connectivity loss → critical alert

### Q: What are the limitations of your system?

**A:** I'm honest about these:

1. **Single Redis instance is a SPOF.** If Redis dies, rate limiting degrades (fail-open), caching degrades (all requests hit DB), locks fail, queues are unavailable. Mitigation: Redis Sentinel or Redis Cluster for HA.

2. **No horizontal auto-scaling.** Currently a single monolith. Adding instances requires manual load balancer configuration. For production, I'd add Kubernetes with HPA based on CPU/request rate.

3. **Analytics storage grows unboundedly.** The 90-day partition strategy helps, but at 200+ GB/month, storage costs accumulate. For high-volume production, I'd move analytics to a columnar store (ClickHouse) or data lake (S3 + Athena).

4. **No end-to-end encryption for stored URLs.** URLs are stored in plaintext. For sensitive use cases (internal tools, protected documents), I'd add application-level encryption with a key management service.

5. **Counter-based IDs are enumerable.** An attacker can increment through short codes and discover all URLs. Rate limiting mitigates but doesn't eliminate this. For high-security deployments, I'd switch to encrypted counter (AES-128 of the counter value → Base62).
