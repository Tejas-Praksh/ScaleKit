-- ScaleKit Distributed Systems Showcase DB Schema

-- ── 1. URL SHORTENER SUBSYSTEM ──────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS urls (
  id BIGSERIAL PRIMARY KEY,
  short_code VARCHAR(10) UNIQUE NOT NULL,
  original_url TEXT NOT NULL,
  custom_alias VARCHAR(20),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  expires_at TIMESTAMPTZ,
  is_active BOOLEAN DEFAULT true,
  is_password_protected BOOLEAN DEFAULT false,
  password_hash VARCHAR(255),
  created_by VARCHAR(255),
  title VARCHAR(500),
  description TEXT,
  thumbnail_url VARCHAR(1000),
  is_safe BOOLEAN DEFAULT true,
  click_count BIGINT DEFAULT 0,
  unique_click_count BIGINT DEFAULT 0,
  last_accessed_at TIMESTAMPTZ,
  metadata JSONB,
  version INT DEFAULT 0 NOT NULL
);

-- Range partitioned TimescaleDB-style analytics events
CREATE TABLE IF NOT EXISTS url_analytics (
  id BIGSERIAL,
  short_code VARCHAR(10) NOT NULL,
  clicked_at TIMESTAMPTZ DEFAULT NOW(),
  ip_address VARCHAR(45),
  country VARCHAR(100),
  city VARCHAR(100),
  device_type VARCHAR(50),
  browser VARCHAR(100),
  os VARCHAR(100),
  referrer TEXT,
  user_agent TEXT,
  is_unique BOOLEAN DEFAULT false,
  response_time_ms INTEGER,
  PRIMARY KEY (id, clicked_at)
) PARTITION BY RANGE (clicked_at);

-- Partitions for 2026-05 and 2026-06 and default partition
CREATE TABLE IF NOT EXISTS url_analytics_y2026m05 PARTITION OF url_analytics
  FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS url_analytics_y2026m06 PARTITION OF url_analytics
  FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS url_analytics_default PARTITION OF url_analytics DEFAULT;

-- Daily pre-aggregated statistics table
CREATE TABLE IF NOT EXISTS url_daily_stats (
  id BIGSERIAL PRIMARY KEY,
  short_code VARCHAR(10) NOT NULL,
  date DATE NOT NULL,
  total_clicks BIGINT DEFAULT 0,
  unique_clicks BIGINT DEFAULT 0,
  top_country VARCHAR(100),
  top_device VARCHAR(50),
  top_referrer TEXT,
  UNIQUE(short_code, date)
);

-- Indexes for URL Shortener
CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls(short_code);
CREATE INDEX IF NOT EXISTS idx_urls_custom_alias ON urls(custom_alias);
CREATE INDEX IF NOT EXISTS idx_urls_created_at ON urls(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_url_analytics_short_code ON url_analytics(short_code);
CREATE INDEX IF NOT EXISTS idx_url_analytics_clicked_at ON url_analytics(clicked_at DESC);
CREATE INDEX IF NOT EXISTS idx_url_analytics_country ON url_analytics(country, clicked_at DESC);
CREATE INDEX IF NOT EXISTS idx_url_daily_stats_lookup ON url_daily_stats(short_code, date);

-- ── 2. RATE LIMITER SUBSYSTEM ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS rate_limit_rules (
  id BIGSERIAL PRIMARY KEY,
  rule_key VARCHAR(100) UNIQUE NOT NULL, -- e.g. "api:public" or "user:123"
  limit_type VARCHAR(20) NOT NULL, -- IP, USER, API
  requests_per_minute INT NOT NULL,
  burst_capacity INT NOT NULL,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rate_limit_audit_logs (
  id BIGSERIAL PRIMARY KEY,
  rule_id BIGINT REFERENCES rate_limit_rules(id),
  identifier VARCHAR(100) NOT NULL, -- IP or User ID
  blocked_at TIMESTAMPTZ DEFAULT NOW(),
  request_uri VARCHAR(255) NOT NULL,
  violation_count INT NOT NULL
);

-- Indexes for Rate Limiter
CREATE INDEX IF NOT EXISTS idx_audit_identifier ON rate_limit_audit_logs(identifier);
CREATE INDEX IF NOT EXISTS idx_audit_blocked_at ON rate_limit_audit_logs(blocked_at DESC);

-- ── 3. ANALYTICS & MONITORING SUBSYSTEM ──────────────────────────────────────

CREATE TABLE IF NOT EXISTS system_metrics (
  id BIGSERIAL PRIMARY KEY,
  system VARCHAR(50) NOT NULL,
  total_requests BIGINT DEFAULT 0,
  success_requests BIGINT DEFAULT 0,
  failed_requests BIGINT DEFAULT 0,
  success_rate DOUBLE PRECISION DEFAULT 0.0,
  avg_response_time_ms DOUBLE PRECISION DEFAULT 0.0,
  p99_response_time_ms DOUBLE PRECISION DEFAULT 0.0,
  cache_hits BIGINT DEFAULT 0,
  cache_misses BIGINT DEFAULT 0,
  cache_hit_rate DOUBLE PRECISION DEFAULT 0.0,
  measured_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS algorithm_benchmarks (
  id BIGSERIAL PRIMARY KEY,
  algorithm VARCHAR(50) NOT NULL,
  requests_per_second DOUBLE PRECISION DEFAULT 0.0,
  latency_ms DOUBLE PRECISION DEFAULT 0.0,
  throughput BIGINT DEFAULT 0,
  error_rate DOUBLE PRECISION DEFAULT 0.0,
  tested_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for Analytics
CREATE INDEX IF NOT EXISTS idx_system_metrics_measured_at ON system_metrics(measured_at DESC);
CREATE INDEX IF NOT EXISTS idx_benchmarks_tested_at ON algorithm_benchmarks(tested_at DESC);

-- ── 4. URL SAFETY SUBSYSTEM ──────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS blocked_attempts (
  id BIGSERIAL PRIMARY KEY,
  url TEXT NOT NULL,
  reputation_score INT NOT NULL,
  threats VARCHAR(500),
  blocked_at TIMESTAMPTZ DEFAULT NOW(),
  ip_address VARCHAR(45),
  user_agent TEXT
);

CREATE INDEX IF NOT EXISTS idx_blocked_attempts_blocked_at ON blocked_attempts(blocked_at DESC);
