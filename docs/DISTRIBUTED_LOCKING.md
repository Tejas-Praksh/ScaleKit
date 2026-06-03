# Distributed Locking Deep Dive

## The Problem
In distributed environments, race conditions occur across multiple separate application instances. For example, two instances of a service could read a database balance of `$100` concurrently, both attempt to deduct `$50`, and both update the balance to `$50`—resulting in a lost update ($100 - 50 = 50 instead of 0).
* **Database Row Locking** (`SELECT FOR UPDATE`) works but binds performance directly to a single relational database instance and does not scale out for arbitrary non-db resources.
* **Distributed Locking** ensures that exactly one execution node can access a given shared resource across the entire cluster.

---

## Redlock Algorithm
The Redlock algorithm distributes safety across a quorum of independent Redis instances. 
1. **Clock Monotonic Time**: Get the current timestamp $T_1$.
2. **Quorum Acquisition**: Try to acquire the lock key on all $N$ Redis nodes using the atomic Lua script `SET key value NX PX ttl`. A lock is considered acquired if the majority ($N/2 + 1$) of the nodes succeed AND the elapsed time is less than the lock TTL.
3. **Validity Time**: Calculate the remaining lock validity time as $TTL - (T_{now} - T_1) - ClockDrift$.
4. **Acquired Use**: If acquired successfully, proceed with the execution.
5. **Rollback/Release**: If acquisition fails, or the validity time is expired, immediately release all acquired keys across all nodes using the release Lua script to avoid stale locks.

---

## Why Fencing Tokens?
Garbage Collection (GC) pauses in virtual machines (like JVM Stop-the-World pauses) can pause execution for seconds. 
If Client 1 acquires a lock with a 2-second TTL, pauses for 3 seconds during a GC cycle:
1. Client 1's lock expires in Redis.
2. Client 2 acquires the lock and writes to the storage.
3. Client 1 resumes, unaware that the lock expired, and overrides/corrupts Client 2's write.

```
Client 1: [Acquire Lock (Token=33)] ──► [GC PAUSE (3s)] ──────────────────────► [Write DB (Stale! Overwrite!)]
Client 2:                                └──► [Lock Expired] ──► [Acquire (Token=34)] ──► [Write DB]
```

**Fencing Tokens** prevent this split-brain scenario.
- Every acquired lock receives a monotonically increasing fencing token (e.g. 100, 101, 102).
- The storage validator checks the token before completing a write.
- If the validator detects a write request with a token smaller or equal to the last processed token (e.g., Token 33 < Token 34), it rejects the write as stale, ensuring complete transactional safety.

---

## Watchdog Pattern
Long-running jobs risk exceeding the lock's initial Time-to-Live (TTL). However, configuring a massive TTL is unsafe because it causes long deadlocks if the node holding the lock crashes.
The **Watchdog Pattern** solves this:
- The system sets a conservative, short TTL (e.g., 300ms) on lock acquisition.
- If the transaction is still running, a background thread periodically (every $TTL / 3$ ms) executes the `extend` Lua script in Redis to renew the TTL.
- When the execution finishes or crashes, the watchdog stops, ensuring the lock is released or naturally expires quickly.

---

## Deadlock Prevention
1. **Expiration Time (TTL)**: Every lock has a mandatory TTL. If a client crashes mid-transaction, Redis will automatically evict the key when the TTL expires.
2. **Quorum Cleanup**: If the algorithm fails to acquire a quorum, it immediately deletes keys on all nodes, avoiding stranded locks.
3. **Deadlock Monitor**: The lock monitoring engine runs checks to detect locks held longer than $2 \times TTL$, alerting developers of potential long-running blockages.

---

## REST Endpoints

### 1. Acquire Lock
* **Path**: `POST /api/v1/locks/acquire`
* **Body**:
```json
{
  "lockKey": "payment:process:123",
  "ttlMs": 5000,
  "maxRetries": 3,
  "retryDelayMs": 100
}
```

### 2. Release Lock
* **Path**: `POST /api/v1/locks/release`
* **Body**:
```json
{
  "lockKey": "payment:process:123",
  "lockValue": "thread-1-42cb4912"
}
```

### 3. Live Stats & Audits
* **Stats**: `GET /api/v1/locks/stats`
* **Audit Logs**: `GET /api/v1/locks/audit?limit=50`
