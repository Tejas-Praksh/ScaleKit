# ScaleKit — Low Level Design

## 1. URL SHORTENER LLD

### Class Diagram (ASCII UML)
```
┌─────────────────────────────┐
│    UrlShortenerController   │
│─────────────────────────────│
│ + createUrl(req): Response  │
│ + redirect(code): Response  │
│ + getStats(code): Response │
│ + bulkCreate(req): Response │
└──────────┬──────────────────┘
│ uses
▼
┌─────────────────────────────┐
│    UrlShortenerService      │
│─────────────────────────────│
│ - urlRepo: UrlRepository    │
│ - cacheService: UrlCache    │
│ - counterService: Counter   │
│ - encoder: Base62Encoder    │
│ - publisher: EventPublisher │
│─────────────────────────────│
│ + createShortUrl(req): Url  │
│ + redirect(code): Result    │
│ + getUrl(code): Optional    │
│ + deleteUrl(code): void     │
└──────────┬──────────────────┘
│
┌──────┴──────┐
▼             ▼
┌────────┐  ┌──────────────┐
│UrlRepo │  │UrlCacheService│
│────────│  │──────────────│
│+findBy │  │+cacheUrl()   │
│ShortCode│  │+getCached()  │
│+save() │  │+evict()      │
└────────┘  └──────────────┘
│
▼
┌──────────────┐
│  PostgreSQL  │
│  urls table  │
└──────────────┘
```

### Sequence Diagram: Create Short URL
```
Client → Controller: POST /api/v1/urls
Controller → SafetyService: checkUrl(url)
SafetyService → Cache: checkCache(sha256)
Cache → SafetyService: MISS
SafetyService → Checkers: runAllChecks()
Checkers → SafetyService: SafetyResult
SafetyService → Cache: cacheResult(24h)
SafetyService → Controller: SAFE (score=95)
Controller → UrlService: createShortUrl(req)
UrlService → CounterService: getNextId()
CounterService → Redis: INCR url:counter
Redis → CounterService: 1000042
CounterService → UrlService: 1000042
UrlService → Base62: encode(1000042)
Base62 → UrlService: "G8xK2mP"
UrlService → UrlRepo: save(url)
UrlRepo → PostgreSQL: INSERT INTO urls
PostgreSQL → UrlRepo: saved
UrlService → CacheService: cacheUrl(code, url)
CacheService → Redis: SET url:G8xK2mP {json}
CacheService → Redis: SET url:redirect:G8xK2mP originalUrl
UrlService → EventBus: publish(UrlCreatedEvent)
Controller → Client: 201 {shortUrl: "scalekit.app/G8xK2mP"}
```

### Sequence Diagram: Redirect (Happy Path)
```
Client → Controller: GET /G8xK2mP
Controller → UrlService: redirect("G8xK2mP")
UrlService → CacheService: getCachedRedirect("G8xK2mP")
CacheService → Redis: GET url:redirect:G8xK2mP
Redis → CacheService: "https://original.com/long/url"
CacheService → UrlService: HIT ✅
UrlService → EventBus: publish(UrlClickedEvent) [ASYNC]
UrlService → Controller: RedirectResult{url, HIT}
Controller → Client: 302 Location: https://original.com
Headers: X-Cache: HIT
X-Response-Time: 3ms
[Background async]:
EventBus → ClickListener: handleClick(event)
ClickListener → UaParser: parseUserAgent(ua)
ClickListener → GeoService: getCountry(ip)
ClickListener → AnalyticsRepo: save(analytics)
ClickListener → Redis: INCR url:clicks:G8xK2mP
```

### Sequence Diagram: Redirect (Cache Miss)
```
Client → Controller: GET /G8xK2mP
Controller → UrlService: redirect("G8xK2mP")
UrlService → CacheService: getCachedRedirect("G8xK2mP")
CacheService → Redis: GET url:redirect:G8xK2mP
Redis → CacheService: nil (MISS)
UrlService → UrlRepo: findByShortCode("G8xK2mP")
UrlRepo → PostgreSQL: SELECT * FROM urls WHERE short_code='G8xK2mP'
PostgreSQL → UrlRepo: url record
UrlService → UrlService: check isActive, isExpired
UrlService → CacheService: cacheUrl(code, url) [warm cache]
CacheService → Redis: SET url:redirect:G8xK2mP originalUrl TTL=7200
UrlService → EventBus: publish(UrlClickedEvent) [ASYNC]
Controller → Client: 302 Location: https://original.com
Headers: X-Cache: MISS
X-Response-Time: 15ms
```

### DB Schema Detail
```sql
-- urls table
CREATE TABLE urls (
  id          BIGSERIAL PRIMARY KEY,
  -- Counter-based ID generation
  -- Base62(id) = short_code
  
  short_code  VARCHAR(10) UNIQUE NOT NULL,
  -- Index: idx_urls_short_code
  -- Type: HASH index better for equality
  
  original_url TEXT NOT NULL,
  -- No index: only accessed via short_code
  
  custom_alias VARCHAR(20),
  -- Nullable, unique when set
  
  expires_at  TIMESTAMPTZ,
  -- Partial index: WHERE expires_at IS NOT NULL
  -- Only index rows that actually expire
  
  is_active   BOOLEAN DEFAULT true,
  -- Partial index: WHERE is_active = true
  -- Inactive rows not in index (smaller!)
  
  click_count BIGINT DEFAULT 0,
  -- Updated by scheduler not on every click
  -- Prevents write amplification
  
  metadata    JSONB
  -- Flexible extra data
  -- GIN index if queried frequently
);

-- Index strategy explained:
-- Hash index for short_code equality: O(1)
-- B-tree for range queries (created_at)
-- Partial indexes reduce index size

-- url_analytics table (HIGH WRITE VOLUME)
CREATE TABLE url_analytics (
  id         BIGSERIAL,
  short_code VARCHAR(10) NOT NULL,
  clicked_at TIMESTAMPTZ DEFAULT NOW(),
  -- PARTITION KEY: clicked_at
  -- Monthly partitions: analytics_2026_01, etc.
  -- Old partitions dropped (not deleted)
  -- DROP is O(1), DELETE is O(n)
  
  country    VARCHAR(100),
  device_type VARCHAR(50),
  -- Low cardinality: perfect for B-tree index
  
  is_unique  BOOLEAN
  -- Unique visitors tracked here
) PARTITION BY RANGE (clicked_at);

-- Create monthly partitions:
CREATE TABLE url_analytics_2026_01
  PARTITION OF url_analytics
  FOR VALUES FROM ('2026-01-01')
  TO ('2026-02-01');
```

---

## 2. RATE LIMITER LLD

### Class Diagram
```
┌─────────────────────────────────┐
│      <<interface>>              │
│      RateLimitAlgorithm         │
│─────────────────────────────────│
│ + tryConsume(key): Result       │
│ + getRemaining(key): int        │
│ + reset(key): void              │
└──────────────┬──────────────────┘
│ implements
┌──────────┼──────────┐
▼          ▼          ▼
┌─────────┐ ┌────────┐ ┌────────┐
│ Token   │ │Sliding │ │ Fixed  │
│ Bucket  │ │ Window │ │ Window │
│─────────│ │────────│ │────────│
│LuaScript│ │LuaScript│ │LuaScript│
│capacity │ │limit   │ │limit   │
│refillRt │ │windowMs│ │windowS │
└─────────┘ └────────┘ └────────┘
```

### Token Bucket State Machine
```
State: {tokens: 10, lastRefill: T0}
Request at T0+30s:
elapsed = 30s
refill = 30 × rate(2/s) = 60
new_tokens = min(10, 10+60) = 10
tokens >= 1: ALLOW
new_tokens = 10 - 1 = 9
Request burst (10 at T0+60s):
elapsed = 30s, refill = 60
new_tokens = min(10, 9+60) = 10
Request 1: tokens=10 → ALLOW → 9
Request 2: tokens=9  → ALLOW → 8
...
Request 10: tokens=1 → ALLOW → 0
Request 11: tokens=0 → REJECT
State: {tokens: 0, lastRefill: T0+60s}
```

### Lua Script Execution Flow
```
Redis Server (single-threaded):
Queue: [Script1, Script2, Script3]
Script1 executes atomically:
HMGET key tokens last_refill → [9, T0]
Calculate new_tokens = 10
Deduct 1 token → 9
HMSET key tokens 9 last_refill T1
EXPIRE key 3600
Return {allowed:1, remaining:9}
Script2 executes (Script1 complete):
HMGET key tokens last_refill → [9, T1]
...
No race condition possible.
Redis processes one script at a time.
```

---

## 3. CONSISTENT HASHING LLD

### Hash Ring Data Structure
```
TreeMap<Integer, VirtualNode>:
Position:  0    1K    5K    8K    12K
│    │     │     │     │
Node:     n1v0  n2v0  n1v1  n3v0  n2v1
n1 = node-1, v0 = virtual node 0
Lookup key "user:123":
hash("user:123") = 6500
ceilingEntry(6500) = 8K → n3v0
→ Assigned to node-3
Wrap-around:
hash("user:456") = 15000
ceilingEntry(15000) = null
firstEntry() = 0 → n1v0
→ Assigned to node-1
```

### Virtual Node Distribution Algorithm
```
addNode("node-4"):
for i in 0..149:
virtualId = "node-4#" + i
position = murmurHash(virtualId)
ring.put(position, VirtualNode(node-4, i))
nodePositions["node-4"] = [pos0, pos1, ... pos149]
// Keys between pos_before and each virtual node
// automatically remapped to node-4
// Only ~25% of keys affected
// (1/4 of ring now belongs to node-4)
```

---

## 4. LRU CACHE LLD

### Memory Layout
```
HashMap:
┌──────────────────────────────┐
│ "key_a" → Node* (pointer)   │
│ "key_b" → Node* (pointer)   │
│ "key_c" → Node* (pointer)   │
└──────────────────────────────┘
Doubly Linked List:
[HEAD] ↔ [key_a:val_a] ↔ [key_c:val_c] ↔ [key_b:val_b] ↔ [TAIL]
←── MRU                               LRU ──→
After get("key_b"):
[HEAD] ↔ [key_b:val_b] ↔ [key_a:val_a] ↔ [key_c:val_c] ↔ [TAIL]
←── MRU                               LRU ──→
After put("key_d") when full:

Evict key_c (LRU)
Add key_d at front

[HEAD] ↔ [key_d:val_d] ↔ [key_b:val_b] ↔ [key_a:val_a] ↔ [TAIL]
```

### Thread Safety Analysis
```
ReadWriteLock:

Multiple readers: concurrent ✅
Single writer: exclusive ✅
Reader + Writer: writer waits ✅

get() uses WRITE lock because:
moveToFront() modifies list
If read lock: 2 threads move
same node → corruption!
containsKey() uses READ lock:
No modification → safe concurrent
Performance:
ReadWriteLock better than synchronized
when reads >> writes
Our cache: reads ≈ writes
→ Minimal difference but correct
```

---

## 5. BLOOM FILTER LLD

### Bit Array Operations
```
BloomFilter(n=1000000, p=0.001):
m = 14,377,588 bits
k = 10 hash functions
add("google.com"):
h0 = murmur3("google.com", 0) % m = 2,341,567
h1 = murmur3("google.com", 1) % m = 8,923,412
h2 = fnv1a("google.com")  % m = 1,234,890
h3 = djb2("google.com")   % m = 11,456,234
...h9
bitSet.set(2341567) → 1
bitSet.set(8923412) → 1
bitSet.set(1234890) → 1
...
```

### False Positive Example
```
add("apple.com"):
Sets positions: [100, 500, 1200, ...]
add("microsoft.com"):
Sets positions: [100, 750, 2100, ...]
Note: position 100 already set by apple.com!
mightContain("pear.com"):
h0 → position 100 → 1 (set by apple.com)
h1 → position 750 → 1 (set by microsoft.com)
h2 → position 999 → 0 → RETURN false ✅
mightContain("mango.com"):
h0 → position 100 → 1 (set by apple.com)
h1 → position 750 → 1 (set by microsoft.com)
h2 → position 1200 → 1 (set by apple.com)
... all 10 positions happen to be 1!
→ Returns TRUE (false positive!)
"mango.com" was never added but returns true
Probability of this: 0.1% with our params
```

---

## 6. API CONTRACTS

### URL Shortener API
```yaml
POST /api/v1/urls
Request:
  Content-Type: application/json
  X-Correlation-ID: uuid (optional)
  Body:
    originalUrl: string (required, URL format)
    customAlias: string (optional, 3-20 chars)
    expiresAt: ISO8601 (optional, future)
    password: string (optional)
    title: string (optional)

Response 201:
  X-Correlation-ID: uuid
  X-Response-Time: Xms
  Body:
    success: true
    data:
      shortCode: string
      shortUrl: string
      originalUrl: string
      createdAt: ISO8601
      expiresAt: ISO8601|null
      isPasswordProtected: boolean
      clickCount: 0
    timestamp: ISO8601
    requestId: uuid

Response 400:
  Body:
    success: false
    errorCode: VALIDATION_FAILED
    message: "Invalid URL format"
    data:
      errors:
        originalUrl: "Must be valid URL"

Response 403:
  Body:
    success: false
    errorCode: URL_BLOCKED
    message: "URL blocked: PHISHING detected"

GET /{shortCode}
Response 302:
  Location: https://original.com/url
  X-Cache: HIT|MISS
  X-Response-Time: Xms

Response 404:
  Body: {"error": "Short URL not found"}

Response 410:
  Body: {"error": "Short URL has expired"}
```

### Rate Limiter API
```yaml
GET /api/v1/rate-limiter/status/{identifier}
  ?endpoint=url-create
Response 200:
  data:
    allowed: true
    remainingRequests: 95
    limitPerMinute: 100
    retryAfterMs: 0
    algorithm: TOKEN_BUCKET
  headers:
    X-RateLimit-Limit: 100
    X-RateLimit-Remaining: 95
    X-RateLimit-Reset: 1716000000

POST /api/v1/benchmark/full
Request:
  Body:
    totalRequests: 10000
    concurrentThreads: 50
    limitPerWindow: 100
    windowMs: 60000

Response: (async, takes 30-60s)
  data:
    results:
      TOKEN_BUCKET:
        p50: 1.2ms
        p95: 2.1ms
        p99: 3.4ms
        throughputPerSec: 45000
        memoryBytes: 100
      SLIDING_WINDOW:
        p50: 2.1ms
        p95: 3.8ms
        p99: 6.2ms
        throughputPerSec: 28000
        memoryBytes: 5120
      FIXED_WINDOW:
        p50: 0.8ms
        p95: 1.4ms
        p99: 2.1ms
        throughputPerSec: 62000
        memoryBytes: 50
    winner: FIXED_WINDOW
    recommendation:
      "Fixed Window fastest but\n       has boundary problem.\n       Use Token Bucket for APIs."
```

### Consistent Hash API
```yaml
GET /api/v1/consistent-hash/distribution
  ?keyCount=10000

Response:
  data:
    totalKeys: 10000
    totalNodes: 3
    virtualNodesPerNode: 150
    keysPerNode:
      node-1: 3341
      node-2: 3298
      node-3: 3361
    idealKeysPerNode: 3333.3
    standardDeviation: 26.8
    coefficientOfVariation: 0.8%
    isWellDistributed: true
    recommendation:
      "Distribution is excellent.\n       CV < 5% indicates virtual\n       nodes working effectively."
```

---

## 7. DESIGN PATTERNS USED

### Pattern Index
| Pattern | Where Used | Why |
|---------|-----------|-----|
| Strategy | Rate Limiter algorithms | Swap algorithms without changing callers |
| Template Method | Cache strategies | Common skeleton, different steps |
| Observer (Events) | Click tracking | Decouple redirect from analytics |
| Decorator | Cache layers | Add TTL to LRU without modifying LRU |
| Factory | Algorithm selection | Create algorithm by type string |
| Singleton | Hash Ring | One ring instance |
| Builder | All DTOs | Readable object construction |
| Command | Message Queue | Encapsulate operations as messages |
| Chain of Responsibility | Safety checker | Run checkers in sequence |

### Strategy Pattern Example
```java
// Interface
interface RateLimitAlgorithm {
  RateLimitResult tryConsume(String key,
    int limit, long windowMs);
}

// Implementations
class TokenBucketAlgorithm
  implements RateLimitAlgorithm {...}

class SlidingWindowAlgorithm
  implements RateLimitAlgorithm {...}

// Context
class RateLimiterService {
  private final Map<RateLimitAlgorithmType,
    RateLimitAlgorithm> algorithms;

  RateLimitResult check(String key,
    String endpoint,
    RateLimitAlgorithmType type) {
    return algorithms.get(type)
      .tryConsume(key, limit, window);
  }
}
// Adding new algorithm = new class only
// Zero changes to existing code
// Open/Closed Principle ✅
```

### Chain of Responsibility: Safety Checker
```java
// Each checker is a link in chain
interface SafetyChecker {
  CheckResult check(String url);
}

// Chain runs all checkers
List<SafetyChecker> checkers = List.of(
  blacklistChecker,
  phishingDetector,
  typosquattingDetector,
  patternChecker,
  ipAddressChecker
);

// All run in parallel (CompletableFuture)
List<CompletableFuture<CheckResult>> futures =
  checkers.stream()
    .map(c -> CompletableFuture
      .supplyAsync(() -> c.check(url)))
    .toList();

// Combine results
List<CheckResult> results =
  futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

---

## 8. ERROR HANDLING STRATEGY

### Error Code Hierarchy
SCALEKIT_ERROR (base)
├── URL_ERROR (400)
│   ├── URL_NOT_FOUND (404)
│   ├── URL_EXPIRED (410)
│   ├── URL_BLOCKED (403)
│   ├── INVALID_URL_FORMAT (400)
│   └── DUPLICATE_ALIAS (409)
├── RATE_LIMIT_ERROR
│   ├── RATE_LIMIT_EXCEEDED (429)
│   └── RATE_LIMIT_CONFIG_ERROR (500)
├── CACHE_ERROR (500)
│   ├── CACHE_WRITE FAILED (500)
│   └── CACHE_READ_FAILED (500)
├── LOCK_ERROR
│   ├── LOCK_NOT_ACQUIRED (409)
│   └── LOCK_EXPIRED (410)
└── SYSTEM_ERROR (500)
    ├── DATABASE_ERROR (500)
    └── REDIS_UNAVAILABLE (503)

### Graceful Degradation Map
Redis DOWN:
├── Rate Limiter → FAIL OPEN (allow all)
├── URL Cache → Query PostgreSQL directly
├── Bloom Filter → Skip check (allow)
├── Distributed Lock → Local lock only
└── Leader Election → All nodes act as leader
PostgreSQL DOWN:
├── URL Redirect → Return cached only
├── Analytics → Buffer in memory queue
└── Rate Limit Config → Use defaults
Both DOWN:
├── URL Redirect → Return 503
├── All writes → Queue for retry
└── Alert fired immediately
```
