# Phase 6: Advanced Algorithms Summary

## What We Built

### 1. Bloom Filter
* **Probabilistic Membership Testing**: Built a space-efficient, generic, in-memory Bloom Filter from scratch utilizing Java's `BitSet`.
* **Zero False Negatives**: Guaranteed that if an item is present, lookup never returns false.
* **Low False Positive Rate**: Handled custom false positive rates (FPR) by calculating optimal bit array size $m$ and hash function count $k$.
* **4-Hash Function Design**: Designed 4 independent hash functions—combining two seeded Murmur3 hashes (Guava) with custom FNV-1a and DJB2 hash algorithms written from scratch.
* **Auto-Scaling layers**: Implemented a `ScalableBloomFilter` that appends 2x larger layers with 0.85x tighter FPR limits when fill-ratio reaches 90%.
* **Distributed & Use Case**: Extended logic to a Redis-backed distributed filter and implemented a URL duplicate detector with canonical normalization.

### 2. Distributed Locking (Redlock)
* **Atomic Redis Lua Scripts**: Developed locking commands from scratch using Lua script executions ensuring absolute execution correctness.
* **Fencing Tokens**: Generated monotonically increasing sequence numbers linked with lock acquisitions to prevent stale database writes during long Stop-the-World JVM GC pauses.
* **Scheduled watchdogs**: Implemented a background thread scheduler that automatically extends lock leases every $TTL / 3$ ms during long tasks.
* **Deadlock Prevention**: SUBTRACTS clock drift (2ms) from validity time, evicts expired locks automatically, and includes monitor systems identifying locks held longer than $2 \times TTL$.
* **Audit trail log**: Maintained a thread-safe capping ring buffer (size 1,000) documenting operations, hold time, and acquisition speeds.

### 3. Leader Election
* **Cluster Coordination**: Configured single-leader guarantees across separate monolith JVMs using Redis `SETNX`-style Lua script elections.
* **Health heartbeats**: Managed scheduled background taskheartbeats (every 3s) keeping active leadership alive.
* **Automatic failover**: Evicts leadership locks on node crash (10s lease TTL) allowing secondary standby nodes to claim leadership on their next poll (5s interval).
* **Execution context**: Provided safe executors executing tasks or returning suppliers only if the current node is the active leader.

### 4. Mini Message Queue
* **Redis List structure**: Implemented producer-consumer message queues using Redis lists (`LPUSH` and `BRPOP` block poll).
* **Graceful Retries**: Retries stochastically failing tasks up to 3 times with backoff configurations.
* **Dead Letter Queue (DLQ)**: Automatically evicts repeatedly failing messages to a dedicated DLQ (`{queueName}:dlq`) for manual investigation.
* **Ordering**: Guaranteed strict FIFO (First In First Out) ordering.

---

## Interview Cheat Sheet

### Q: How does a Bloom Filter work?
**A**: A Bloom Filter maps keys to a bit array of size $m$ using $k$ independent hash functions.
* **Addition**: Set the bits at the $k$ calculated hash indices to `1`.
* **Membership Check**: Inspect bits at the $k$ hash indices. If *any* bit is `0`, the item is **definitely not** in the set (zero false negatives). If all bits are `1`, the item **might** be in the set (false positives are possible due to hash collisions, usually target ~0.1% or 1%).
* Used by: Google BigTable, Apache Cassandra, HBase, Bitcoin blockchain, and Chrome Safe Browsing.

### Q: How do you prevent race conditions in distributed systems?
**A**: Using a distributed lock manager such as Redis **Redlock**.
* The lock is acquired by setting a unique value on a majority ($N/2 + 1$) of independent Redis nodes within a short timeframe (less than lock TTL).
* **Fencing tokens** (monotonically increasing IDs) must be passed to storage writes. The database rejects any write carrying a token smaller than the last processed token, protecting against stale client updates caused by JVM garbage collection pauses.

### Q: How do you ensure only one node runs a scheduled task?
**A**: By using **Leader Election**.
* Nodes compete to write their unique `nodeId` to a central key in Redis using `SETNX` (or Lua scripts) with a Time-to-Live (TTL).
* The winning node becomes the leader and periodically sends **heartbeats** to extend the key's TTL.
* If the leader crashes, the heartbeat stops, the key expires, and a standby node wins the next election cycle to take over task execution.

### Q: What is a Dead Letter Queue (DLQ)?
**A**: A Dead Letter Queue is a secondary queue where messages that fail processing repeatedly (exceeding `MAX_RETRIES`) are routed.
* Moving failing messages to a DLQ prevents them from blocking the main queue (poison pill prevention).
* It ensures no messages are silently lost, letting operators inspect the payloads and manually resolve processing failures.
