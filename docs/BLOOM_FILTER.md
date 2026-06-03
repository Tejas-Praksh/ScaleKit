# Bloom Filter Implementation

## What is a Bloom Filter?

A **probabilistic data structure** that answers the question: *"Is this item in the set?"*

| Answer                         | Accuracy          |
|-------------------------------|-------------------|
| ✅ **Definitely NOT in set**   | 100% accurate     |
| ⚠️ **MIGHT BE in set**        | Small error rate  |

**Never false negatives.** Small false positive rate.

## The Math

### Optimal Parameters

Given `n` expected insertions and desired false positive rate `p`:

```
Bit array size:   m = -(n × ln(p)) / (ln(2))²
Hash functions:   k = (m / n) × ln(2)
```

#### Example: 1M elements, 0.1% FPR

```
m = -(1,000,000 × ln(0.001)) / ln(2)²
m = 14,377,588 bits ≈ 1.7 MB

k = (14,377,588 / 1,000,000) × ln(2)
k ≈ 10 hash functions
```

> **1.7 MB** to track 1 million elements with 99.9% accuracy.
> A HashSet would need ~50 MB for the same elements.

### False Positive Rate Formula

```
p = (1 - e^(-kn/m))^k
```

| Fill Ratio | Estimated FPR |
|-----------|---------------|
| 10%       | ~0.000001%    |
| 50%       | ~0.1% (target)|
| 90%       | ~2.5%         |
| 100%      | ~8.2% ⚠️     |

**Solution for capacity overflow:** Scalable Bloom Filter (see below).

## Our Implementation

### 4 Hash Functions

| Seed | Function           | Origin         |
|------|--------------------|----------------|
| 0    | MurmurHash3(seed=0)| Guava          |
| 1    | MurmurHash3(seed=1)| Guava          |
| 2    | FNV-1a             | From scratch   |
| 3    | DJB2               | From scratch   |
| 4+   | MurmurHash3(seed=N)| Guava          |

### Scalable Bloom Filter

When the active filter reaches **90% capacity**, a new layer is added:
- **2× larger** capacity
- **0.85× tighter** false positive rate

Lookups check **all layers** — an item is "might contain" if *any* layer reports it.

### Redis-Backed Distributed Filter

Stores the bit array in Redis using `SETBIT`/`GETBIT` commands:
- Works across multiple application instances
- Gracefully degrades if Redis is unavailable

## Use Cases in Production

### Google BigTable
Check if a row key exists before reading from disk.
**Saves expensive disk I/O.**

### Apache Cassandra
Check SSTable before disk read.
**Reduces read latency by 50%+.**

### Chrome Safe Browsing
Local Bloom Filter of malicious URLs.
**Avoids network lookup for most URLs.**

### Medium
Track which articles you've read.
**Never show the same article twice.**

### Bitcoin Blockchain
SPV nodes use Bloom Filters to request only relevant transactions.
**Reduces bandwidth for light clients.**

## When to Use Bloom Filters

### ✅ Good For
- **Membership testing** where false positives are acceptable
- **Cache miss avoidance** (check before expensive DB lookup)
- **Duplicate detection** (URL dedup, email dedup)
- **Distributed systems** where space efficiency matters
- **Write-heavy workloads** (O(k) insert, never reorganizes)

### ❌ Not Good For
- Cases requiring **zero false positives**
- Deletion of elements (use Counting Bloom Filter instead)
- Exact set operations (use HashSet)
- Small datasets where memory isn't a concern

## API Endpoints

```
POST   /api/v1/bloom-filter/filters                Create named filter
POST   /api/v1/bloom-filter/filters/{name}/add      Add item
POST   /api/v1/bloom-filter/filters/{name}/check    Check membership
GET    /api/v1/bloom-filter/filters/{name}/stats     Get stats
POST   /api/v1/bloom-filter/demo                    False-positive demo
POST   /api/v1/bloom-filter/url-dedup               URL deduplication
GET    /api/v1/bloom-filter/url-dedup/stats          URL dedup stats
POST   /api/v1/bloom-filter/benchmark               Performance benchmark
```
