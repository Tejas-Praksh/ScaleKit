# Capacity Estimation & Partitioning Strategy

This document outlines the database and caching capacity estimations, indexing strategy, and partitioning strategy for ScaleKit designed to handle 100 Million URLs and their corresponding analytics.

---

## 1. Storage & Memory Estimations (100 Million URLs)

### A. Database (PostgreSQL)

#### `urls` Table
* **Data Fields & Sizes (per row):**
  * `id` (BIGINT): 8 bytes
  * `short_code` (VARCHAR(10)): ~10 bytes + 1 byte varlen overhead = 11 bytes
  * `original_url` (TEXT): Average 150 bytes
  * `custom_alias` (VARCHAR(20)): Nullable, average 5 bytes (including overhead)
  * `created_at` (TIMESTAMPTZ): 8 bytes
  * `expires_at` (TIMESTAMPTZ): 8 bytes
  * `is_active` (BOOLEAN): 1 byte
  * `is_password_protected` (BOOLEAN): 1 byte
  * `password_hash` (VARCHAR(255)): Nullable, average 10 bytes (if mostly false)
  * `created_by` (VARCHAR(255)): Nullable, average 20 bytes
  * `title` (VARCHAR(500)): Nullable, average 50 bytes
  * `description` (TEXT): Nullable, average 50 bytes
  * `thumbnail_url` (VARCHAR(1000)): Nullable, average 20 bytes
  * `is_safe` (BOOLEAN): 1 byte
  * `click_count` (BIGINT): 8 bytes
  * `unique_click_count` (BIGINT): 8 bytes
  * `last_accessed_at` (TIMESTAMPTZ): 8 bytes
  * `metadata` (JSONB): Nullable, average 30 bytes
  * `version` (INT): 4 bytes
  * **Row Overhead (PostgreSQL page & tuple headers):** ~28 bytes
  * **Total Estimated Row Size:** ~412 bytes (rounded to **450 bytes** to be conservative).

* **Storage for 100 Million Rows:**
  * Raw Data: $100,000,000 \times 450 \text{ bytes} \approx 45 \text{ GB}$
  * Index Overhead:
    * `pk_urls` (B-tree on `id`): $\sim 3.2 \text{ GB}$
    * `idx_urls_short_code` (Unique B-tree on `short_code`): $\sim 4.2 \text{ GB}$
    * `idx_urls_custom_alias` (B-tree on `custom_alias`): $\sim 2.5 \text{ GB}$
    * `idx_urls_created_at` (B-tree on `created_at DESC`): $\sim 3.2 \text{ GB}$
  * **Total `urls` Storage:** $\sim 58.1 \text{ GB}$

#### `url_analytics` Table (Assuming 10x Clicks per URL = 1 Billion Clicks)
* **Data Fields & Sizes (per row):**
  * `id` (BIGINT): 8 bytes
  * `short_code` (VARCHAR(10)): 11 bytes
  * `clicked_at` (TIMESTAMPTZ): 8 bytes
  * `ip_address` (VARCHAR(45)): ~16 bytes (IPv4/IPv6 mix)
  * `country` (VARCHAR(100)): ~15 bytes
  * `city` (VARCHAR(100)): ~20 bytes
  * `device_type` (VARCHAR(50)): ~10 bytes
  * `browser` (VARCHAR(100)): ~15 bytes
  * `os` (VARCHAR(100)): ~15 bytes
  * `referrer` (TEXT): ~80 bytes
  * `user_agent` (TEXT): ~150 bytes
  * `is_unique` (BOOLEAN): 1 byte
  * `response_time_ms` (INT): 4 bytes
  * **Tuple Overhead:** ~28 bytes
  * **Total Estimated Row Size:** ~381 bytes (rounded to **400 bytes**).

* **Storage for 1 Billion Click Events:**
  * Raw Data: $1,000,000,000 \times 400 \text{ bytes} \approx 400 \text{ GB}$
  * Index Overhead:
    * Primary key (`id`, `clicked_at`): $\sim 35 \text{ GB}$
    * `idx_url_analytics_short_code`: $\sim 28 \text{ GB}$
    * `idx_url_analytics_clicked_at`: $\sim 22 \text{ GB}$
    * `idx_url_analytics_country`: $\sim 30 \text{ GB}$
  * **Total `url_analytics` Storage:** $\sim 515 \text{ GB}$

---

### B. Cache (Redis)

To ensure sub-millisecond redirect latencies, we cache hot URLs. Applying the Pareto Principle (80/20 rule), we cache the most active 10% of URLs (10 Million URLs).

* **Cache Entry size (per URL):**
  * Key: `url:{short_code}` (15 bytes)
  * Value: JSON serialized URL object (average 250 bytes)
  * Redis Hash/Overhead per entry: ~250 bytes
  * **Total per entry:** ~515 bytes
* **Cache Capacity for 10M URLs:**
  * $10,000,000 \times 515 \text{ bytes} \approx 5.15 \text{ GB}$
  * **Recommendation:** Configure Redis with **8 GB maxmemory** and `allkeys-lru` eviction policy.

---

## 2. Indexing Strategy

To maintain $O(1)$ lookup complexity for short codes and fast analytics generation, the following indexes are applied:

1. **`idx_urls_short_code` (Unique Index on `urls.short_code`):**
   * *Purpose:* Instantly maps the 7-character short code to the original URL during redirection.
   * *Complexity:* $O(\log N)$ via B-tree.

2. **`idx_urls_custom_alias` (Index on `urls.custom_alias`):**
   * *Purpose:* Allows clients to look up original URLs using their custom alias.

3. **`idx_urls_created_at` (Index on `urls.created_at DESC`):**
   * *Purpose:* Speeds up user dashboards listing their recently created short URLs.

4. **`idx_url_analytics_short_code` (Index on `url_analytics.short_code`):**
   * *Purpose:* Aggregates click stats for a specific short URL quickly.

5. **`idx_url_analytics_clicked_at` (Index on `url_analytics.clicked_at DESC`):**
   * *Purpose:* Drives date-range filtering and real-time dashboard analytics.

---

## 3. Partitioning Strategy (`url_analytics` Table)

As a high-traffic table tracking every redirect, `url_analytics` will grow by billions of rows. To prevent query degradation and index bloat, a TimescaleDB-style range partitioning strategy is used.

### A. Range Partitioning by Month
The table is partitioned by the `clicked_at` timestamp:
```sql
CREATE TABLE url_analytics (
  ...
) PARTITION BY RANGE (clicked_at);
```

### B. Partition Management
1. **Historical Partitions:** Partition tables are created for specific timeframes, e.g., monthly:
   * `url_analytics_y2026m05` for `2026-05-01` to `2026-06-01`
   * `url_analytics_y2026m06` for `2026-06-01` to `2026-07-01`
   * `url_analytics_default` catches any insert falling outside pre-defined partition ranges to prevent write failures.

### C. Performance Benefits
* **Index Locality:** PostgreSQL only needs to update indexes for the *current active partition* (e.g., current month). This active partition easily fits in RAM, avoiding random disk I/O.
* **Fast Deletion (TTL/Data Retention):** To prune analytics older than 1 year, we can simply run `DROP TABLE url_analytics_y2025m05;` instead of expensive `DELETE FROM` statements which generate massive WAL logs and cause table fragmentation.
* **Partition Pruning:** Queries specifying a `clicked_at` range will only scan the matching monthly tables, ignoring the rest of the 500+ GB dataset.
