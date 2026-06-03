# ScaleKit — High Level Design

> **Author:** Staff Engineer  
> **Date:** May 2026  
> **Stack:** Java 21, Spring Boot 3.2.5, PostgreSQL, Redis  
> **Deployment:** Single monolith (intentional)

---

## Table of Contents

1. [System Overview](#system-overview)
2. [URL Shortener HLD](#1-url-shortener-hld)
3. [Rate Limiter HLD](#2-rate-limiter-hld)
4. [Consistent Hashing HLD](#3-consistent-hashing-hld)
5. [Cache HLD](#4-cache-hld)
6. [Bloom Filter HLD](#5-bloom-filter-hld)
7. [Distributed Locking HLD](#6-distributed-locking-hld)
8. [Leader Election HLD](#7-leader-election-hld)
9. [Message Queue HLD](#8-message-queue-hld)
10. [Scalability Roadmap](#9-scalability-roadmap)
11. [Trade-Off Analysis](#10-trade-off-analysis)

---

## System Overview

ScaleKit is a distributed systems toolkit implementing **8 core algorithms from scratch**. Built as a **monolith intentionally** — demonstrating architectural maturity, not ignorance.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         ScaleKit Monolith                               │
│                                                                         │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │
│  │ URL Shortener│ │ Rate Limiter │ │  Cache (LRU  │ │ Bloom Filter │   │
│  │ + Analytics  │ │ Token Bucket │ │   LFU, TTL)  │ │ Probabilistic│   │
│  │ + Safety     │ │ Sliding Win  │ │ + Strategies │ │ + URL Dedup  │   │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘   │
│         │                │                │                │            │
│  ┌──────┴───────┐ ┌──────┴───────┐ ┌──────┴───────┐ ┌──────┴───────┐   │
│  │ Consistent   │ │ Distributed  │ │   Leader     │ │   Message    │   │
│  │ Hashing Ring │ │ Lock (Redlock│ │  Election    │ │    Queue     │   │
│  │ + Virtual    │ │ + Fencing)   │ │ + Heartbeat  │ │ + DLQ + Retry│   │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Cross-Cutting Concerns                       │    │
│  │  API Gateway Filter │ Circuit Breakers │ Correlation IDs        │    │
│  │  Structured Logging │ Prometheus Metrics │ Health Checks         │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
    ┌──────────┐        ┌──────────┐        ┌──────────┐
    │PostgreSQL│        │  Redis   │        │ React    │
    │  (ACID)  │        │ (Cache+  │        │Dashboard │
    │  URLs,   │        │  Locks,  │        │  (Vite)  │
    │ Analytics│        │ Queues)  │        │          │
    └──────────┘        └──────────┘        └──────────┘
```

### Why Monolith for ScaleKit?

Microservices add overhead that **directly conflicts** with our latency targets:

| Overhead Source             | Microservices  | Monolith      |
|-----------------------------|----------------|---------------|
| Inter-service network hop   | 1–10ms per hop | 0ms           |
| Serialization/deserialization| 0.5–2ms       | 0ms           |
| Service discovery           | 1–5ms          | 0ms           |
| Distributed tracing         | 0.5–1ms        | Free via MDC  |
| Deployment complexity       | K8s + Helm     | Single JAR    |
| **Total overhead**          | **3–18ms**     | **0ms**       |

Our URL shortener p99 target: **< 10ms** for redirects.

With microservices: `gateway(2ms) + url-service(2ms) + cache-service(1ms) + analytics-service(1ms)` = **6ms** minimum — **60% of budget consumed by infrastructure alone**.

Monolith: direct method call = **0ms overhead**. Full budget for business logic.

> **Decision:** Right tool for the right job. Monolith until proven otherwise.  
> **Trigger for migration:** When a single subsystem needs 10x more resources than the rest.

---

## 1. URL Shortener HLD

### Functional Requirements

| Requirement          | Priority | Status |
|----------------------|----------|--------|
| Shorten URL → 7-char code | P0  | ✅     |
| Redirect short → original | P0  | ✅     |
| Click analytics tracking  | P1  | ✅     |
| Custom aliases             | P1  | ✅     |
| URL expiry (TTL)           | P1  | ✅     |
| Malicious URL detection    | P1  | ✅     |
| Password protection        | P2  | ✅     |

### Non-Functional Requirements

| Metric        | Target           | Rationale                          |
|---------------|------------------|------------------------------------|
| Availability  | 99.9% (8.7h/yr)  | Revenue impact of downtime         |
| Read latency  | p99 < 10ms       | User-facing redirect, must be fast |
| Write latency | p99 < 50ms       | URL creation is infrequent         |
| Scale         | 100M URLs stored  | 5-year projection                  |
| Read QPS      | 10,000 req/sec   | Peak traffic estimation            |
| Consistency   | Strong for URLs, eventual for analytics | Analytics can lag   |
| Durability    | Zero URL loss     | URL is the product                 |

### Capacity Estimation

#### Traffic

```
Write QPS:
  100M URLs / (365 days × 24 hours × 3,600 sec)
  = 100,000,000 / 31,536,000
  = 3.17 writes/second (steady state — very low)

Read QPS:
  Read:Write ratio = 100:1 (typical for URL shorteners)
  = 3.17 × 100 = 317 reads/second (steady state)
  
Peak factor:
  Social media viral link = 10x–100x normal
  Peak: 317 × 10 = 3,170 reads/second (design target)
  Extreme: 317 × 100 = 31,700 reads/second (burst protection)
```

#### Storage

```
One URL record:
  short_code (7B) + original_url (500B avg) + metadata (200B)
  ≈ 700 bytes per record

URL storage:
  100M × 700 bytes = 70 GB (PostgreSQL)

Analytics:
  1 click event ≈ 200 bytes (timestamp, IP, UA, referrer, geo)
  Assumption: 10 clicks per URL average
  100M URLs × 10 clicks = 1B total clicks
  1B × 200 bytes = 200 GB
  
  Retention: 90 days → partition by month → delete old partitions
  At 10k reads/sec steady:
  10k × 86,400 sec × 30 days × 200 bytes = 518 GB / month
  → 90-day rolling = ~1.5 TB max analytics storage

Redis cache (hot URLs):
  Zipf's law: 20% of URLs get 80% of traffic
  20M URLs × 700 bytes = 14 GB Redis
  With redirect-only cache (30 bytes): 600 MB additional
```

#### Bandwidth

```
Redirect response: HTTP 301/302 + Location header ≈ 200 bytes
Read bandwidth: 10,000 req/sec × 200 bytes = 2 MB/sec (trivial)

URL creation: request body ≈ 1 KB, response ≈ 500 bytes
Write bandwidth: 3.17 req/sec × 1.5 KB = 4.75 KB/sec (negligible)
```

### Architecture

```
Client (Browser / API)
│
│  HTTP GET /:shortCode
│
▼
┌─────────────────────────────────────┐
│          ScaleKit App               │
│                                     │
│  1. API Gateway Filter              │
│     ├─ Attach X-Correlation-ID      │
│     ├─ Rate limit check             │
│     └─ Request timing start         │
│                                     │
│  2. URL Controller                  │
│     └─ GET /{code} → redirect()     │
│                                     │
│  3. URL Service                     │
│     ├─ L1 Cache Check (Caffeine)    │
│     │   HIT? → return immediately   │
│     │                               │
│     ├─ L2 Cache Check (Redis)       │
│     │   Key: url:redirect:{code}    │
│     │   HIT? → promote to L1        │
│     │                               │
│     └─ DB Lookup (PostgreSQL)       │
│         FOUND? → populate L1 + L2   │
│         NOT FOUND? → 404            │
│                                     │
│  4. Async Analytics (non-blocking)  │
│     └─ @Async @EventListener        │
│         ├─ Parse User-Agent         │
│         ├─ Geo-locate IP            │
│         ├─ Write click to DB        │
│         └─ Increment Redis counter  │
└─────────────────────────────────────┘
         │              │
         ▼              ▼
    ┌──────────┐   ┌──────────┐
    │PostgreSQL│   │  Redis   │
    │          │   │          │
    │ urls     │   │ url:     │
    │ url_     │   │  {code}  │
    │ analytics│   │ counter  │
    └──────────┘   └──────────┘
```

### URL ID Generation: Counter vs Random

```
┌────────────────────────────────────────────────────────┐
│                  ID Generation Strategy                 │
│                                                         │
│  Option A: Random Base62                                │
│  ┌─────────────────────────────────────────────┐       │
│  │ 62^7 = 3,521,614,606,208 combinations       │       │
│  │ Birthday paradox: 50% collision at √(3.5T)   │       │
│  │ = ~1.87M URLs → COLLISION HANDLING NEEDED    │       │
│  │ ❌ Retry loops, DB unique constraint checks  │       │
│  │ ❌ Unpredictable code length                 │       │
│  │ ✅ Non-sequential (harder to guess)          │       │
│  └─────────────────────────────────────────────┘       │
│                                                         │
│  Option B: Counter-based Base62 ← CHOSEN               │
│  ┌─────────────────────────────────────────────┐       │
│  │ Redis INCR → Base62(counter)                 │       │
│  │ Start at 1,000,000 (min 4-char codes)        │       │
│  │ ✅ ZERO collisions (mathematically proven)   │       │
│  │ ✅ Sortable by creation time                 │       │
│  │ ✅ Predictable 7-char output                 │       │
│  │ ⚠️  Sequential (guessable next code)         │       │
│  │   Mitigation: rate limit creation API        │       │
│  │   Mitigation: auth required for bulk         │       │
│  └─────────────────────────────────────────────┘       │
│                                                         │
│  Decision: Counter-based                                │
│  Reason: Zero collisions > non-guessability             │
│  At 3 writes/sec, collision retry loops waste time      │
│  with zero engineering benefit.                         │
└────────────────────────────────────────────────────────┘
```

### Caching Strategy

Two-level caching with purpose-built keys:

```
Level 1: Redirect-Only Cache
  Key:     url:redirect:{code}
  Value:   raw URL string (30-500 bytes)
  TTL:     2 hours
  Purpose: Absolute fastest redirect path
  Hit:     O(1) Redis GET → HTTP 302

Level 2: Full Object Cache
  Key:     url:{code}
  Value:   serialized URL object (700 bytes)
  TTL:     1 hour
  Purpose: API responses (stats, metadata)

Cache Invalidation:
  On update → delete BOTH keys (safe, conservative)
  On delete → delete BOTH keys
  No write-through (URLs rarely change after creation)
  
  Why not write-through?
  URL shortener is 100:1 read-heavy.
  Write-through adds write latency for
  a benefit that occurs once per URL lifetime.
```

### Analytics Pipeline

```
Redirect Request
       │
       ├──→ Return HTTP 302 immediately (< 5ms)
       │
       └──→ AsyncEvent published (non-blocking)
              │
              ▼
         Thread Pool
         ┌────────────────────────────────┐
         │ Core: 5 threads               │
         │ Max:  20 threads              │
         │ Queue: 1,000 capacity         │
         │ Policy: CallerRunsPolicy      │
         │ (never drop, caller blocks)   │
         └───────────┬────────────────────┘
                     │
                     ▼
         ┌────────────────────────────────┐
         │ 1. Parse User-Agent           │
         │ 2. Extract referrer           │
         │ 3. Geo-locate IP (future)     │
         │ 4. Write to PostgreSQL        │
         │ 5. Increment Redis counters   │
         │    - url:clicks:{code}        │
         │    - url:clicks:hourly:{hour} │
         └────────────────────────────────┘

Benefits:
  ✅ Redirect p99 completely unaffected by analytics
  ✅ Analytics can tolerate 5-30 second lag
  ✅ CallerRuns policy = zero data loss under load
  ✅ Thread pool isolates analytics failure from redirects
```

---

## 2. Rate Limiter HLD

### Algorithm Comparison

| Aspect           | Token Bucket      | Sliding Window Log | Fixed Window    |
|------------------|-------------------|--------------------|-----------------|
| Burst handling   | ✅ Allows bursts  | ❌ Strict, no burst| ⚠️ Boundary burst|
| Memory per user  | O(1) — 2 fields   | O(N) — N entries  | O(1) — 1 counter|
| Accuracy         | High              | Highest            | Medium          |
| Redis ops/check  | 1 (Lua script)    | 3 (ZADD+ZCOUNT+ZREM)| 1 (INCR)      |
| Complexity       | Medium            | High               | Low             |
| Latency (p99)    | ~2ms              | ~4ms               | ~1ms            |
| Used by          | Stripe, AWS       | GitHub API         | Simple APIs     |

### Token Bucket Internals

```
Redis Hash per user:
  Key: rl:tb:{identifier}:{endpoint}
  Fields:
    tokens:      float (current available tokens)
    last_refill: long  (epoch millis of last refill)

Lua Script (atomic, single round-trip):
  ┌─────────────────────────────────────────────────────┐
  │ 1. GET current tokens and last_refill               │
  │ 2. elapsed = now - last_refill                      │
  │ 3. new_tokens = min(capacity,                       │
  │      current + (elapsed_sec × refill_rate))         │
  │ 4. IF new_tokens >= 1:                              │
  │      SET tokens = new_tokens - 1                    │
  │      SET last_refill = now                          │
  │      RETURN {allowed: true, remaining: new_tokens}  │
  │    ELSE:                                            │
  │      RETURN {allowed: false, remaining: 0,          │
  │              retry_after: (1 - tokens) / rate}      │
  └─────────────────────────────────────────────────────┘

Why Lua, not MULTI/EXEC?
  Lua: 1 round-trip, conditional logic, atomic
  MULTI/EXEC: 2 round-trips, no conditionals
  Lua: single-threaded execution on Redis (no races)
```

### Sliding Window Memory Analysis

```
At 100 requests/minute per user:
  Each request = 1 ZSET member ≈ 50 bytes (timestamp + score)
  100 entries × 50 bytes = 5 KB per user

Scale projections:
  1,000 users   = 5 MB    ← trivial
  100,000 users = 500 MB  ← manageable
  1,000,000 users = 5 GB  ← significant Redis memory
  10,000,000 users = 50 GB ← exceeds typical Redis instance

Decision matrix:
  < 100K concurrent users → Sliding Window (strictest accuracy)
  > 100K concurrent users → Token Bucket (O(1) memory)
```

### Distributed Rate Limiting

```
Problem:
  3 app servers, each with local counter.
  Limit = 100 requests/minute.
  User sends 33 to each server = 99 total → all pass locally.
  But actual total = 99 → should be limited!

  Worse: user sends 100 to each = 300 total.
  Each server sees 100 → allows all.
  User bypasses limit by 3x.

Solution:
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ Server 1 │  │ Server 2 │  │ Server 3 │
  └────┬─────┘  └────┬─────┘  └────┬─────┘
       │              │              │
       └──────────────┼──────────────┘
                      │
                      ▼
               ┌──────────────┐
               │    Redis     │
               │  (Lua script │
               │   per key)   │
               └──────────────┘

  All servers read/write SAME Redis key.
  Lua script ensures check-and-decrement is atomic.
  Result: globally accurate rate limiting.
```

### Fail-Open Strategy

```
Redis down scenario:
  Option A: Fail-closed → block ALL requests
            ❌ 100% user impact for a protection mechanism
            ❌ Self-inflicted DDoS worse than actual attack

  Option B: Fail-open → allow ALL requests  ← CHOSEN
            ✅ Users continue working
            ✅ Protection mechanism gracefully degrades
            ⚠️ Some excess traffic allowed temporarily
            Mitigation: log ALL fail-open events
            Mitigation: alert on Redis connectivity loss
            Mitigation: Redis downtime is rare + short

  Decision: Rate limiter is protection, not authentication.
  Blocking legitimate traffic is WORSE than allowing excess.
```

---

## 3. Consistent Hashing HLD

### Problem Statement

```
Traditional modulo hashing:
  serverIndex = hash(key) % N

  N = 3 servers → key lands on server (hash % 3)
  Add 1 server → N = 4 → key lands on (hash % 4)

  Impact: EVERY key's server assignment changes!
  Cache hit rate → 0% immediately.
  Database stampede: all requests hit DB.

  With 1M cached keys:
  Rehash all 1M keys = catastrophic thundering herd.
```

### Solution: Hash Ring

```
Hash space: 0 to 2³² - 1 (4,294,967,295)

        0°
        │
   330° ─┼─ 30°
        ╱│╲
      ╱  │  ╲
  300°   │    60°          Ring with 3 physical nodes:
    │    │    │              A at 45°, B at 160°, C at 280°
  270°───┼───90°           
    │    │    │            Key "user:42" hashes to position 120°
      ╲  │  ╱              → Next clockwise node = B (at 160°)
       ╲ │ ╱               
   240° ─┼─ 120°          Add server D at 200°:
        │                   Only keys between 160°-200° move.
       180°                 = ~11% of keys (1/9 of ring)

Operation costs:
  Lookup: O(log N) via TreeMap.ceilingEntry(hash)
  Add node: O(V × log N) — insert V virtual nodes
  Remove node: O(V × log N) — remove V virtual nodes
  V = virtual nodes per server, N = total virtual nodes
```

### Virtual Nodes

```
Without virtual nodes (3 servers, random placement):
  ┌──────────────────────────────────────────────────┐
  │ Server A: ████████████████████████████░░░░░ 60%  │
  │ Server B: █████████░░░░░░░░░░░░░░░░░░░░░░░ 15%  │
  │ Server C: ████████████████░░░░░░░░░░░░░░░░ 25%  │
  │                                                   │
  │ Standard deviation: 23.6% — EXTREMELY UNEVEN     │
  └──────────────────────────────────────────────────┘

With 150 virtual nodes per server:
  ┌──────────────────────────────────────────────────┐
  │ Server A: █████████████████████░░░░░░░░░░░ 34%   │
  │ Server B: ████████████████████░░░░░░░░░░░░ 33%   │
  │ Server C: ████████████████████░░░░░░░░░░░░ 33%   │
  │                                                   │
  │ Standard deviation: 0.7% — NEAR PERFECT          │
  │ Law of large numbers at work.                     │
  └──────────────────────────────────────────────────┘

Why 150?
  Too few (10):  std dev ~12% — still uneven
  Sweet spot (150): std dev <2% — negligible imbalance
  Too many (1000): std dev <0.5% but memory overhead
  
  150 vnodes × 3 servers = 450 TreeMap entries
  O(log 450) = ~9 comparisons per lookup
  Memory: 450 × ~100 bytes = 45 KB (trivial)
```

### Data Structures

```java
// Core data structure
TreeMap<Integer, VirtualNode> ring;
// Integer = hash position (0 to 2^32)
// VirtualNode = { physicalNode, virtualIndex }

// Lookup: O(log N)
Map.Entry<Integer, VirtualNode> entry = ring.ceilingEntry(hash);
if (entry == null) entry = ring.firstEntry(); // wrap around

// Why TreeMap?
// - ceilingEntry() = next clockwise node = O(log N)
// - Ordered iteration for rebalance analysis
// - Standard JDK, no external dependency
```

---

## 4. Cache HLD

### LRU vs LFU Decision Guide

```
┌────────────────────────────────────────────────────────┐
│                  Use LRU When:                          │
│                                                         │
│  • Recency = relevance                                  │
│    (news feeds, social timelines, recent orders)        │
│  • Access patterns are temporally local                 │
│  • Working set changes frequently                       │
│  • Simpler to reason about and debug                    │
│                                                         │
│  LRU O(1) internals:                                    │
│    HashMap<K, Node> + DoublyLinkedList                   │
│    get(): map.get(key) → moveToFront() → O(1)          │
│    put(): if full, evict tail → O(1)                    │
│    Dummy head/tail eliminate null pointer checks         │
├────────────────────────────────────────────────────────┤
│                  Use LFU When:                          │
│                                                         │
│  • Frequency = relevance                                │
│    (search results, CDN assets, static configs)         │
│  • Hot keys accessed repeatedly over time               │
│  • Working set is stable for long periods               │
│  • Willing to accept scan resistance trade-off          │
│                                                         │
│  LFU O(1) internals:                                    │
│    HashMap<K, Node> + HashMap<freq, DoublyLinkedList>   │
│    get(): increment freq → move to freq+1 bucket        │
│    put(): if full, evict from minFreq bucket tail       │
│    Maintains minFreq pointer for O(1) eviction          │
└────────────────────────────────────────────────────────┘
```

### Cache Stampede Prevention

```
Problem:
  Popular key expires.
  10,000 concurrent requests arrive.
  ALL miss cache → ALL query database simultaneously.
  Database: 10,000 identical queries → overload → cascading failure.

  Timeline:
  t=0:    Key "hot:item" expires in cache
  t=1ms:  Request #1 checks cache → MISS → queries DB
  t=1ms:  Request #2 checks cache → MISS → queries DB
  t=1ms:  Request #3 checks cache → MISS → queries DB
  ...
  t=1ms:  Request #10,000 → MISS → queries DB
  t=50ms: Database connection pool exhausted → 503 errors

Solutions implemented:

  1. Mutex Lock (Primary)
     ┌──────────────────────────────────────┐
     │ Request 1: cache MISS → acquire lock │
     │ Request 2: cache MISS → lock busy    │──→ wait/retry
     │ Request 3: cache MISS → lock busy    │──→ wait/retry
     │ Request 1: DB query → set cache      │
     │ Request 2: retry → cache HIT         │
     │ Request 3: retry → cache HIT         │
     │                                      │
     │ DB queries: 1 (instead of 10,000)    │
     └──────────────────────────────────────┘

  2. Probabilistic Early Recomputation (PER)
     TTL = 60 seconds
     At t=55s: random chance of recompute
     Formula: currentTime - (TTL × beta × ln(rand)) > expiry
     Key refreshed BEFORE expiry → no stampede window

  3. Stale-While-Revalidate
     Return stale value immediately.
     Trigger background refresh.
     User gets fast (possibly stale) response.
     Next request gets fresh data.
```

### Cache Strategy Comparison

```
┌────────────────────────────────────────────────────────────────┐
│ Strategy         │ Write Path              │ Read Path          │
├──────────────────┼─────────────────────────┼────────────────────┤
│ Write-Through    │ Write cache + DB sync   │ Read from cache    │
│                  │ ✅ Strong consistency    │ ✅ Always fresh    │
│                  │ ❌ High write latency   │                    │
├──────────────────┼─────────────────────────┼────────────────────┤
│ Write-Behind     │ Write cache, async DB   │ Read from cache    │
│                  │ ✅ Low write latency    │ ✅ Always fresh    │
│                  │ ❌ Data loss risk       │                    │
├──────────────────┼─────────────────────────┼────────────────────┤
│ Cache-Aside      │ Write DB, invalidate    │ Check cache → DB   │
│  (our primary)   │ ✅ Simple, reliable     │ ⚠️ First miss cold │
│                  │ ✅ No stale data        │                    │
├──────────────────┼─────────────────────────┼────────────────────┤
│ Read-Through     │ N/A                     │ Cache loads from DB│
│                  │                         │ ✅ Transparent     │
│                  │                         │ ⚠️ Cold start      │
└────────────────────────────────────────────────────────────────┘

ScaleKit Decision: Cache-Aside as primary.
Reason: URL shortener is 100:1 read-heavy.
Write-through adds latency for writes that happen 3x/sec.
Cache-aside: invalidate on write, populate on read miss.
```

---

## 5. Bloom Filter HLD

### Mathematics

```
Given:
  n = expected insertions (number of elements)
  p = desired false positive rate
  m = bit array size
  k = number of hash functions

Optimal bit array size:
  m = -(n × ln(p)) / (ln 2)²
  
Optimal hash function count:
  k = (m / n) × ln 2

Example calculation for URL deduplication:
  n = 1,000,000 URLs
  p = 0.001 (0.1% false positive rate)

  m = -(1,000,000 × ln(0.001)) / (ln 2)²
    = -(1,000,000 × -6.908) / 0.4805
    = 6,908,000 / 0.4805
    = 14,377,588 bits
    ≈ 1.71 MB

  k = (14,377,588 / 1,000,000) × 0.693
    = 14.38 × 0.693
    ≈ 10 hash functions

Memory comparison:
  ┌────────────────────────────────────────────┐
  │ Data Structure │ Memory for 1M URLs        │
  ├────────────────┼───────────────────────────┤
  │ HashSet<String>│ ~50 MB (50 bytes/entry)   │
  │ Bloom Filter   │ ~1.7 MB (1.7 bytes/entry) │
  │ Savings        │ 97% less memory!           │
  └────────────────────────────────────────────┘
```

### Hash Function Independence

```
Why multiple DIFFERENT algorithms, not same algorithm different seeds?

  Same algorithm, different seeds (e.g., MurmurHash3 × 4):
    h1 = murmur3(key, seed=0)
    h2 = murmur3(key, seed=1)
    h3 = murmur3(key, seed=2)
    h4 = murmur3(key, seed=3)

    Problem: same internal mixing function.
    Correlated outputs → higher false positive rate.
    Mathematical: not truly pairwise independent.

  Different algorithms (our approach):
    h1 = MurmurHash3(key, seed=0)  — multiplicative hash
    h2 = MurmurHash3(key, seed=1)  — different seed
    h3 = FNV-1a(key)               — XOR-then-multiply
    h4 = DJB2(key)                 — shift-and-add

    Different mathematical structures.
    True independence → theoretical FPR achieved.
    Measured FPR: 0.089% vs theoretical 0.1%.
```

### Bloom Filter Use Cases in ScaleKit

```
1. URL Deduplication
   Before inserting URL → check Bloom Filter
   "Definitely not exists" → proceed with insert
   "Might exist" → check database (rare, only on FP)
   Saves: ~99.9% of duplicate-check DB queries

2. Malicious URL Screening
   Bloom Filter of known bad URL patterns
   Fast pre-check before full safety analysis
   False positive: safe URL gets extra check (harmless)
   False negative: impossible (unsafe URL never passes)
```

---

## 6. Distributed Locking HLD

### The Distributed Lock Problem

```
Single-server locking:
  synchronized(lock) { /* safe */ }
  ReentrantLock.lock() → works perfectly.
  Why? Single JVM, single memory space.

Multi-server problem:
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ Server 1 │  │ Server 2 │  │ Server 3 │
  │ lock = ✅│  │ lock = ✅│  │ lock = ✅│
  └──────────┘  └──────────┘  └──────────┘
  
  Each server has its OWN lock object.
  All three think they hold "the lock".
  All three modify the same resource.
  Data corruption!

Solution: External lock coordinator (Redis).
  All servers ask SAME Redis for the lock.
  Redis SETNX: only one wins.
```

### Redlock Algorithm

```
Setup: N = 5 Redis instances (odd number for majority)
Quorum: N/2 + 1 = 3

Acquisition protocol:
  ┌─────────────────────────────────────────────────┐
  │ 1. Record start_time                            │
  │ 2. Try to acquire lock on ALL N instances       │
  │    SET lock_key lock_value NX PX ttl_ms         │
  │    (NX = only if not exists, PX = expiry in ms) │
  │ 3. Count successes                              │
  │ 4. Elapsed = now - start_time                   │
  │ 5. IF successes >= quorum AND elapsed < TTL:    │
  │    → Lock ACQUIRED                              │
  │    → Validity = TTL - elapsed (clock drift adj) │
  │    ELSE:                                        │
  │    → Lock FAILED                                │
  │    → Release any acquired locks (cleanup)       │
  └─────────────────────────────────────────────────┘

Failure scenarios:
  2 of 5 nodes down:
    3 remaining can form quorum → lock still works ✅
  
  3 of 5 nodes down:
    2 remaining < quorum of 3 → lock fails safely ✅
    No split-brain possible.
```

### Fencing Token Safety

```
The GC-pause split-brain problem:

  Time ──────────────────────────────────────────→

  Client A:  ┌─LOCK──────────────GC PAUSE────────┐
             │ token=33         ░░░░░░░░░░░░░░░░░░│
             │                  (lock expires!)    │
             │                                     │
  Client B:  │              ┌─LOCK────────────────┐│
             │              │ token=34             ││
             │              │                      ││
             │              └──WRITE──→ DB ✅      ││
             │                    (token 34 accepted)
             └──────WRITE──→ DB ❌
                   (token 33 REJECTED: < 34)

  Without fencing tokens:
    Client A's stale write OVERWRITES Client B's.
    Data corruption. Silent. Catastrophic.

  With fencing tokens:
    Storage rejects any token < highest seen.
    Client A's write rejected. Data safe.
    
  Implementation:
    fencingToken = Redis INCR "lock:fencing:counter"
    Monotonically increasing. Never reused. Never reset.
```

### Lock TTL Design

```
Why TTL is mandatory:
  Client acquires lock → crashes → never releases.
  Without TTL: lock held FOREVER → deadlock.
  With TTL: lock auto-expires → system recovers.

TTL sizing:
  Too short (1s):
    Normal operation takes 2s → lock expires mid-work.
    Another client acquires → both running → data corruption.
  
  Too long (5min):
    Client crashes → 5 minutes until recovery.
    Acceptable for batch jobs. Not for real-time.

  Sweet spot:
    operation_time × 3 = safety margin
    10s operation → 30s TTL
    
  Watchdog pattern (our implementation):
    Initial TTL = 30s
    Every 10s: IF still working → extend TTL by 30s
    On crash: no extension → TTL expires naturally
    On completion: explicit release (immediate)
```

---

## 7. Leader Election HLD

### Why Leader Election?

```
Problem: Scheduled tasks on multiple instances.
  
  Instance 1: @Scheduled cleanupExpiredUrls() → runs
  Instance 2: @Scheduled cleanupExpiredUrls() → runs
  Instance 3: @Scheduled cleanupExpiredUrls() → runs
  
  Same expired URLs deleted 3 times.
  Same analytics aggregated 3 times.
  Same notifications sent 3 times.

Solution: Only the LEADER runs scheduled tasks.
  
  Instance 1 (LEADER):  cleanupExpiredUrls() → runs ✅
  Instance 2 (FOLLOWER): cleanupExpiredUrls() → skipped
  Instance 3 (FOLLOWER): cleanupExpiredUrls() → skipped
```

### Election via Redis

```
Election protocol:
  1. Each instance attempts:
     SET leader:election {nodeId} NX EX 15
     NX = only if not exists (atomic election)
     EX = 15 second TTL (lease duration)
  
  2. If SET succeeds → this instance is LEADER
  3. If SET fails → another instance is leader
  
  Heartbeat (leader only):
     Every 5 seconds:
     SET leader:election {nodeId} XX EX 15
     XX = only if exists (renewal, not acquisition)
     Resets TTL to 15 seconds.
  
  Failover:
     Leader crashes → no heartbeat → TTL expires after 15s
     Next election round → new leader in < 20 seconds
     
     ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┐
     │ t=0 │ t=5 │ t=10│ t=15│ t=16│ t=17│ t=20│
     │ HB  │ HB  │CRASH│     │     │ELECT│ HB  │
     │ A   │ A   │     │EXPIR│     │ B   │ B   │
     └─────┴─────┴─────┴─────┴─────┴─────┴─────┘
       Leader A healthy   Failover    Leader B takes over
```

---

## 8. Message Queue HLD

### Architecture

```
┌───────────┐     ┌─────────────────────────────┐     ┌───────────┐
│ Producers │────▶│       Message Queue          │────▶│ Consumers │
│           │     │                              │     │           │
│ enqueue() │     │ ┌──────────────────────────┐ │     │ dequeue() │
│           │     │ │   Main Queue (Redis List)│ │     │ ack()     │
│           │     │ │   LPUSH / BRPOP          │ │     │ nack()    │
│           │     │ └──────────────────────────┘ │     │           │
│           │     │                              │     │           │
│           │     │ ┌──────────────────────────┐ │     │           │
│           │     │ │   Processing Set         │ │     │           │
│           │     │ │   (in-flight messages)   │ │     │           │
│           │     │ └──────────────────────────┘ │     │           │
│           │     │                              │     │           │
│           │     │ ┌──────────────────────────┐ │     │           │
│           │     │ │   Dead Letter Queue      │ │     │           │
│           │     │ │   (failed after retries) │ │     │           │
│           │     │ └──────────────────────────┘ │     │           │
└───────────┘     └─────────────────────────────┘     └───────────┘

Message lifecycle:
  1. Producer LPUSH to main queue
  2. Consumer BRPOP from main queue
  3. Message moved to processing set (in-flight)
  4. Consumer processes message
  5a. Success → ack() → remove from processing set
  5b. Failure → nack() → retry with backoff
  5c. Max retries exceeded → move to Dead Letter Queue

Retry strategy:
  Attempt 1: immediate
  Attempt 2: 1 second delay
  Attempt 3: 5 second delay
  Attempt 4: 30 second delay (final)
  After 4 failures → Dead Letter Queue

  Backoff formula: min(30s, base × 2^attempt)
```

---

## 9. Scalability Roadmap

### Current State (Single Instance)

```
┌─────────────────────────────────────┐
│         Current Performance          │
├────────────────────┬────────────────┤
│ URL redirects      │ 10,000 req/sec │
│ Rate limit checks  │ 50,000 req/sec │
│ Cache operations   │ 100,000 ops/sec│
│ Bloom Filter       │ 1,000,000 op/s │
│ Lock acquisitions  │ 5,000 req/sec  │
│ Queue throughput   │ 20,000 msg/sec │
├────────────────────┼────────────────┤
│ Hardware           │ 8-core, 32GB   │
│ PostgreSQL         │ Single instance│
│ Redis              │ Single instance│
└────────────────────┴────────────────┘
```

### Phase 1: Scale to 10x (100K req/sec)

```
Changes:
  1. PostgreSQL read replicas (2 replicas)
     Writes → primary, Reads → replicas
     URL lookups spread across 3 DB instances
     
  2. Redis Cluster (3 shards)
     Rate limiter keys partitioned by user
     Cache keys partitioned by short code
     
  3. Multiple app instances (3) + load balancer
     Nginx/HAProxy for HTTP load balancing
     Sticky sessions NOT needed (stateless app)
     
  4. CDN for React dashboard static assets

Cost: ~$500/month cloud, ~$100/month self-hosted
```

### Phase 2: Scale to 100x (1M req/sec)

```
Changes:
  1. Extract URL redirect service
     Lightweight service, no Spring Boot overhead
     Netty/Vert.x for raw throughput
     Only function: code → URL → 302
     
  2. Edge caching (Cloudflare/Fastly)
     Cache redirects at CDN edge
     TTL: 5 minutes (tolerate slight staleness)
     Cache hit rate: 95%+ (only 50K hit origin)
     
  3. Geographically distributed Redis
     US-East, US-West, EU-West
     Reduces Redis latency from 5ms to <1ms
     
  4. PostgreSQL partitioning
     urls: partition by creation date (monthly)
     analytics: partition by event date (daily)
     Auto-drop partitions older than 90 days

Cost: ~$5,000/month cloud
```

### Phase 3: Scale to 1000x (10M req/sec)

```
Changes:
  1. Dedicated redirect infrastructure
     Pre-compiled redirect lookup
     No JVM, no GC → C/Rust redirect proxy
     
  2. Global load balancing (Anycast DNS)
     Route to nearest datacenter
     Active-active across 5+ regions
     
  3. Pre-computed redirect cache
     All active URLs loaded into memory at boot
     In-memory lookup: O(1), <1μs
     Background sync from DB every 10 seconds
     
  4. NoSQL for URL storage
     DynamoDB/Cassandra for URL metadata
     Horizontally scalable writes
     PostgreSQL kept for analytics (OLAP)

Cost: ~$50,000/month cloud
Justification: 10M req/sec = significant revenue business
```

---

## 10. Trade-Off Analysis

### Monolith vs Microservices

```
                     Monolith (CHOSEN)        Microservices
─────────────────────────────────────────────────────────────
Latency overhead     0ms                      5-18ms per hop
Deployment           1 JAR, 1 command         K8s + Helm + CI/CD
Debugging            Single process, IDE      Distributed tracing
Team scaling         Harder at 20+ eng        Natural team boundaries
Independent deploy   Not possible             Per-service
Cost                 1 server                 N servers + infra
Monitoring           Simple                   Complex (Jaeger, etc)

Decision: Monolith
Reason: p99 < 10ms impossible with microservices overhead
Re-evaluate when: Team > 20 OR single subsystem needs 10x resources
```

### PostgreSQL vs NoSQL

```
                     PostgreSQL (CHOSEN)      NoSQL (DynamoDB)
─────────────────────────────────────────────────────────────
Consistency          ACID (strong)            Eventual
Write throughput     ~10K writes/sec          ~100K writes/sec
URL creation         3 writes/sec (trivial)   Overkill
Schema flexibility   Fixed schema             Schema-less
Joins                Full SQL                 None
Analytics queries    GROUP BY, aggregates     Scan + aggregate
Operational cost     Low (familiar)           Medium (DynamoDB pricing)

Decision: PostgreSQL
Reason: 3 writes/sec doesn't justify NoSQL complexity
Re-evaluate when: Writes > 10K/sec OR geographic distribution needed
```

### Redis vs Memcached

```
                     Redis (CHOSEN)           Memcached
─────────────────────────────────────────────────────────────
Data structures      Strings, Hashes, Lists,  Strings ONLY
                     Sets, Sorted Sets,
                     Streams, Pub/Sub
Rate limiting        ZSET for sliding window  Impossible natively
Bloom Filter         BIT operations           Not supported
Message Queue        LIST (LPUSH/BRPOP)       Not supported
Leader Election      SET NX + Pub/Sub         Not supported
Locking              SET NX PX                SET only (no PX)
Persistence          RDB + AOF                None
Lua scripting        ✅ Atomic operations     ❌
Multi-threading      Single-threaded (safe)   Multi-threaded (faster)

Decision: Redis
Reason: We use 6+ Redis data structures.
Memcached is a key-value store. Redis is a data structure server.
There is no contest for our use case.
```
