# ScaleKit — Complete Capacity Estimation

> **Purpose:** Back-of-the-envelope calculations for system sizing.  
> **Audience:** System design interviews, infrastructure planning.  
> **Methodology:** Worst-case estimates with safety margins.

---

## 1. System-Wide Assumptions

| Parameter             | Value                  | Rationale                        |
|-----------------------|------------------------|----------------------------------|
| Server                | 8-core CPU, 32GB RAM   | Typical production VM            |
| Network               | 1 Gbps NIC             | Standard cloud instance          |
| PostgreSQL             | Single instance, SSD   | ACID for URL data                |
| Redis                 | Single instance, 16GB  | Cache + locks + queues           |
| JVM heap              | 16GB (-Xmx16g)        | Half of system RAM               |
| Concurrent users      | 100,000                | Active at any moment             |
| Total registered URLs | 100,000,000            | 5-year projection                |

---

## 2. Request Budget

### Throughput Allocation

```
Total capacity target: 50,000 requests/second (single instance)

Allocation by subsystem:
┌────────────────────────┬──────────┬─────────┬────────────────────────┐
│ Subsystem              │ QPS      │ % Total │ Justification          │
├────────────────────────┼──────────┼─────────┼────────────────────────┤
│ URL redirects          │ 10,000   │ 20%     │ User-facing, critical  │
│ Rate limit checks      │ 30,000   │ 60%     │ Every request is checked│
│ Cache operations       │  8,000   │ 16%     │ Behind rate limiter    │
│ Lock operations        │  1,000   │  2%     │ Infrequent, write-path │
│ Other (health, metrics)│  1,000   │  2%     │ Monitoring overhead    │
├────────────────────────┼──────────┼─────────┼────────────────────────┤
│ Total                  │ 50,000   │ 100%    │                        │
└────────────────────────┴──────────┴─────────┴────────────────────────┘

Why rate limiter is 60%:
  Every inbound request passes through the rate limiter filter.
  10,000 URL redirects = 10,000 rate limit checks
  + API calls, dashboard, health checks = additional 20,000 checks
```

### Peak Traffic Estimation

```
Steady state:     50,000 req/sec
Daily peak (10x): 500,000 req/sec (lunch hour + evening)
Viral event (50x): 2,500,000 req/sec (single URL goes viral)

For viral events:
  CDN absorbs 95% → 125,000 req/sec to origin
  Redis cache absorbs 94% → 7,500 req/sec to database
  Database can handle 10,000+ reads/sec easily
```

---

## 3. Storage Budget

### PostgreSQL

```
┌──────────────────────────────────────────────────────────────────┐
│ Table: urls                                                      │
├──────────────────────┬───────────┬───────────────────────────────┤
│ Column               │ Size      │ Notes                         │
├──────────────────────┼───────────┼───────────────────────────────┤
│ id (BIGINT)          │ 8 bytes   │ Primary key                   │
│ short_code (VARCHAR) │ 7 bytes   │ Indexed, unique               │
│ original_url (TEXT)  │ 500 bytes │ Average URL length            │
│ created_at (TIMESTAMP)│ 8 bytes  │ Indexed for TTL cleanup       │
│ expires_at (TIMESTAMP)│ 8 bytes  │ Nullable                      │
│ click_count (BIGINT) │ 8 bytes   │ Denormalized counter          │
│ custom_alias (VARCHAR)│ 20 bytes │ Nullable                      │
│ is_active (BOOLEAN)  │ 1 byte    │                               │
│ metadata (JSONB)     │ 200 bytes │ Password hash, flags, etc.    │
│ Row overhead         │ 23 bytes  │ PostgreSQL tuple header       │
├──────────────────────┼───────────┼───────────────────────────────┤
│ Total per row        │ ~783 bytes│ Round to 800 bytes            │
└──────────────────────┴───────────┴───────────────────────────────┘

  100M URLs × 800 bytes = 80 GB raw data

  Index: short_code B-tree
  100M × (7 + 8 + 16) bytes = ~3.1 GB index
  
  Index: created_at B-tree  
  100M × (8 + 8 + 16) bytes = ~3.2 GB index

  Total URLs storage: 80 + 3.1 + 3.2 ≈ 87 GB
  With 20% bloat factor: ~104 GB
```

```
┌──────────────────────────────────────────────────────────────────┐
│ Table: url_analytics (PARTITIONED by month)                      │
├──────────────────────┬───────────┬───────────────────────────────┤
│ Column               │ Size      │ Notes                         │
├──────────────────────┼───────────┼───────────────────────────────┤
│ id (BIGINT)          │ 8 bytes   │ Partition key component       │
│ url_id (BIGINT)      │ 8 bytes   │ FK to urls                    │
│ clicked_at (TIMESTAMP)│ 8 bytes  │ Partition key                 │
│ ip_address (INET)    │ 16 bytes  │ IPv6 capable                  │
│ user_agent (TEXT)     │ 100 bytes │ Truncated to 200 chars        │
│ referrer (TEXT)       │ 50 bytes  │ Truncated to 200 chars        │
│ country_code (CHAR)  │ 2 bytes   │ ISO 3166-1 alpha-2            │
│ Row overhead         │ 23 bytes  │                               │
├──────────────────────┼───────────┼───────────────────────────────┤
│ Total per row        │ ~215 bytes│ Round to 250 bytes            │
└──────────────────────┴───────────┴───────────────────────────────┘

  Daily click volume:
  10,000 redirects/sec × 86,400 sec/day = 864,000,000 clicks/day
  
  Wait — that's at peak. Steady state:
  317 redirects/sec × 86,400 = 27,388,800 clicks/day ≈ 28M
  
  Monthly: 28M × 30 = 840M clicks/month
  840M × 250 bytes = 210 GB per month

  90-day retention (3 partitions):
  3 × 210 GB = 630 GB analytics storage

  Partition strategy:
  CREATE TABLE url_analytics_2026_01 PARTITION OF url_analytics
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
  
  DROP old partitions → instant storage reclaim (no DELETE scan)
```

```
PostgreSQL Total Storage Budget:
┌────────────────────┬──────────┐
│ Component          │ Size     │
├────────────────────┼──────────┤
│ urls table + index │ 104 GB   │
│ analytics (90-day) │ 630 GB   │
│ Other tables       │ 5 GB     │
│ WAL + temp         │ 20 GB    │
├────────────────────┼──────────┤
│ Total              │ ~760 GB  │
│ Recommended disk   │ 1 TB SSD │
└────────────────────┴──────────┘
```

### Redis

```
┌──────────────────────────────────────────────────────────────────┐
│ Redis Memory Budget                                              │
├─────────────────────────────────┬──────────┬─────────────────────┤
│ Purpose                         │ Size     │ Key pattern          │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ URL redirect cache              │          │                     │
│   20% hot URLs = 20M entries    │          │                     │
│   Key: url:redirect:{code} (20B)│          │                     │
│   Value: URL string (500B avg)  │          │                     │
│   Overhead per key: 80B (Redis) │          │                     │
│   Total: 20M × 600B             │ 12.0 GB  │ url:redirect:*     │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ URL full object cache           │          │                     │
│   Top 5% = 5M entries           │          │                     │
│   Key + value: ~900B per entry  │          │                     │
│   Total: 5M × 900B              │ 4.5 GB   │ url:*              │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Rate limiter (Token Bucket)     │          │                     │
│   100K concurrent users         │          │                     │
│   Hash per user: ~150B          │          │                     │
│   Total: 100K × 150B            │ 15 MB    │ rl:tb:*            │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Rate limiter (Sliding Window)   │          │                     │
│   10K strict-limit users        │          │                     │
│   ZSET per user: 5KB avg        │          │                     │
│   Total: 10K × 5KB              │ 50 MB    │ rl:sw:*            │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Bloom Filter bit arrays         │          │                     │
│   URL dedup: 1.7MB              │          │                     │
│   Malicious URL: 0.5MB          │          │                     │
│   Custom filters: ~5MB          │          │                     │
│   Total                         │ 7 MB     │ bloom:*            │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Distributed Locks               │          │                     │
│   Max 1,000 concurrent locks    │          │                     │
│   Key + value: ~200B per lock   │          │                     │
│   Audit log: ~1MB               │          │                     │
│   Total                         │ 1.2 MB   │ lock:*             │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Leader Election                 │          │                     │
│   1 key + metadata              │ <1 KB    │ leader:*           │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Message Queue                   │          │                     │
│   10 queues × 10K messages      │          │                     │
│   Message: ~500B                 │          │                     │
│   Total: 100K × 500B            │ 50 MB    │ queue:*            │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ URL counter (INCR)              │ <1 KB    │ url:counter        │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ Analytics counters              │          │                     │
│   Per-URL click count: 100M     │          │                     │
│   Key + counter: ~30B           │          │                     │
│   Total: 100M × 30B             │ 3.0 GB   │ analytics:*        │
├─────────────────────────────────┼──────────┼─────────────────────┤
│ TOTAL                           │ ~19.6 GB │                     │
│ Recommended Redis instance      │ 32 GB    │ 60% utilization     │
└─────────────────────────────────┴──────────┴─────────────────────┘
```

---

## 4. Memory Budget (JVM)

```
Total system RAM: 32 GB

┌─────────────────────────────────────────────────────┐
│ JVM Heap (-Xmx16g -Xms16g)               │ 16 GB  │
├─────────────────────────────────────────────────────┤
│  ├─ LRU Cache (in-process)                │  2 GB  │
│  │   10,000 entries × 200KB avg           │        │
│  ├─ LFU Cache (in-process)                │  1 GB  │
│  │   5,000 entries × 200KB avg            │        │
│  ├─ Request processing buffers            │  1 GB  │
│  │   50K concurrent × 20KB per request    │        │
│  ├─ Connection pools                      │  0.5 GB│
│  │   PostgreSQL: 20 connections × 2MB     │        │
│  │   Redis: 16 connections × 1MB          │        │
│  ├─ Thread stacks                         │  0.5 GB│
│  │   200 threads × 1MB (Tomcat default)   │        │
│  │   + 20 async threads × 1MB             │        │
│  ├─ Spring context + class metadata       │  1 GB  │
│  └─ Free for GC + young gen              │ 10 GB  │
│      (G1GC, 200ms target pause)           │        │
├─────────────────────────────────────────────────────┤
│ Off-heap                                  │ 16 GB  │
│  ├─ OS kernel + file system cache         │  8 GB  │
│  ├─ Direct byte buffers (Netty/NIO)       │  2 GB  │
│  ├─ JVM metaspace (-XX:MaxMetaspaceSize)  │  512 MB│
│  └─ OS overhead + reserved                │  5.5 GB│
└─────────────────────────────────────────────────────┘
```

---

## 5. Network Budget

```
Inbound traffic:
  50,000 req/sec × 1 KB avg request = 50 MB/sec = 400 Mbps

Outbound traffic:
  URL redirects: 10,000 × 200B = 2 MB/sec
  API responses: 5,000 × 2KB = 10 MB/sec
  Dashboard data: 100 × 50KB = 5 MB/sec
  Total outbound: ~17 MB/sec = 136 Mbps

Redis traffic (loopback):
  50,000 operations/sec × 500B avg = 25 MB/sec bidirectional

PostgreSQL traffic (loopback or local):
  3,000 queries/sec × 2KB avg = 6 MB/sec bidirectional

Total network utilization:
  External: 400 + 136 = 536 Mbps (< 1 Gbps NIC ✅)
  Internal: 25 + 6 = 31 MB/sec (loopback, virtually unlimited)
```

---

## 6. Per-Subsystem Breakdown

### URL Shortener

```
                        Writes          Reads
─────────────────────────────────────────────────────
QPS (steady)            3.17/sec        317/sec
QPS (peak)              31.7/sec        3,170/sec
QPS (viral burst)       317/sec         31,700/sec

Latency target:
  Write p99:            < 50ms
  Read  p99:            < 10ms
  Read  p99 (cached):   < 2ms

Cache hit rate target:  94%
Effective DB QPS:       317 × 0.06 = 19 reads/sec (steady)
                        3,170 × 0.06 = 190 reads/sec (peak)
```

### Rate Limiter

```
                    Token Bucket    Sliding Window
──────────────────────────────────────────────────
Memory per user     ~150 bytes      ~5 KB
Users supported*    100,000,000     3,000,000
p99 latency         2ms             4ms
Redis ops/check     1 (Lua)         3 (ZADD+ZCOUNT+ZREM)

* Before exceeding 16GB Redis instance
```

### Bloom Filter

```
Filter: URL Deduplication
  Expected elements:    1,000,000
  Desired FPR:          0.1%
  Bit array size:       14,377,588 bits = 1.71 MB
  Hash functions:       10
  Memory savings vs HashSet: 97%

Filter: Malicious URL Screening
  Expected elements:    100,000
  Desired FPR:          0.01%
  Bit array size:       2,875,518 bits = 0.34 MB
  Hash functions:       13
```

### Distributed Locks

```
Concurrent locks:       1,000 max
Lock TTL:               30 seconds default
Watchdog renewal:       every 10 seconds
Fencing token range:    BIGINT (2^63 = 9.2 quintillion)
  At 1000 locks/sec:    292 million years until overflow
```

---

## 7. Cost Estimation

### Self-Hosted (Bare Metal / VPS)

```
┌────────────────────────────┬──────────┬──────────────────┐
│ Component                  │ Specs    │ Monthly Cost     │
├────────────────────────────┼──────────┼──────────────────┤
│ App Server (Hetzner AX52)  │ 8-core,  │ $55/month        │
│                            │ 64GB RAM │                  │
│ Database (same server)     │ 1TB NVMe │ (included)       │
│ Redis (same server)        │ 16GB     │ (included)       │
│ Backup storage             │ 1TB      │ $5/month         │
│ Domain + SSL               │          │ $1/month         │
├────────────────────────────┼──────────┼──────────────────┤
│ Total                      │          │ ~$61/month       │
└────────────────────────────┴──────────┴──────────────────┘
```

### Cloud (AWS)

```
┌────────────────────────────┬──────────┬──────────────────┐
│ Component                  │ Specs    │ Monthly Cost     │
├────────────────────────────┼──────────┼──────────────────┤
│ EC2 (m6i.2xlarge)          │ 8 vCPU,  │ $280/month       │
│                            │ 32GB RAM │                  │
│ RDS PostgreSQL (db.m6g.lg) │ 2 vCPU,  │ $165/month       │
│                            │ 8GB, 1TB │                  │
│ ElastiCache Redis (r6g.lg) │ 16GB     │ $210/month       │
│ ALB                        │          │ $25/month        │
│ S3 (backups)               │ 1TB      │ $23/month        │
│ CloudWatch                 │          │ $15/month        │
├────────────────────────────┼──────────┼──────────────────┤
│ Total                      │          │ ~$718/month      │
└────────────────────────────┴──────────┴──────────────────┘
```

---

## 8. Bottleneck Analysis

```
Under steady-state load, the bottleneck cascade:

1st bottleneck: PostgreSQL connections (if uncached)
   Default pool: 20 connections
   Each query: ~5ms
   Throughput: 20 × (1000/5) = 4,000 queries/sec
   Mitigation: 94% cache hit rate → only 190 queries/sec at peak

2nd bottleneck: Redis single-threaded execution
   Simple GET: 100,000 ops/sec
   Lua script: 50,000 ops/sec
   Our rate limiter: 30,000 checks/sec → 60% utilization
   Mitigation: Redis 7 multi-threading for I/O

3rd bottleneck: JVM GC pauses
   G1GC with 16GB heap: ~50ms pauses every few minutes
   Impact: p99.9 latency spike
   Mitigation: -XX:MaxGCPauseMillis=200, tune young gen
   
4th bottleneck: Network (not a bottleneck)
   536 Mbps of 1 Gbps = 54% utilization
   
Summary: System is CACHE-BOUND.
If cache is working, everything is fast.
If cache fails, PostgreSQL is the limit at 4K QPS.
```
