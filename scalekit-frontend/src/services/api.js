import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL || '';

const api = axios.create({
  baseURL: BASE_URL,
  timeout: 60000, // 60s timeout — Render cold starts can take 30-60s
  headers: {
    'Content-Type': 'application/json'
  }
});

// Attach a fresh X-Correlation-ID to every request for request tracing
api.interceptors.request.use((config) => {
  try {
    config.headers['X-Correlation-ID'] = window.crypto?.randomUUID 
      ? window.crypto.randomUUID() 
      : 'c-' + Math.random().toString(36).substring(2, 15);
  } catch (e) {
    config.headers['X-Correlation-ID'] = 'fallback-correlation-id';
  }
  // Track retry count
  config.__retryCount = config.__retryCount || 0;
  return config;
}, (error) => {
  return Promise.reject(error);
});

// Auto-retry on network errors (handles Render free-tier cold starts which take ~2 minutes)
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config;
    // Only retry on network errors or 5xx, not on 4xx client errors
    const isRetryable = !error.response || error.response.status >= 500;
    
    // Render free tier can take up to 150 seconds to wake up
    // We retry up to 15 times, waiting 10 seconds each time = 150 seconds total
    if (isRetryable && config && config.__retryCount < 15) {
      config.__retryCount += 1;
      
      // Wait 10 seconds before retrying
      await new Promise((resolve) => setTimeout(resolve, 10000));
      return api(config);
    }
    return Promise.reject(error);
  }
);

// Centralized API functions
export const apiService = {
  // ── URL Shortener ──────────────────────────────────────────────────────────
  createUrl: (data) => api.post('/api/v1/urls', data),
  getUrl: (code) => api.get(`/api/v1/urls/${code}`),
  getUrlStats: (code) => api.get(`/api/v1/urls/${code}/stats`),
  redirectUrl: (code) => api.get(`/${code}`),
  getGlobalAnalytics: () => api.get('/api/v1/observability/dashboard').then(res => {
    const urlsStats = res.data?.data?.urls || {};
    return {
      data: {
        success: true,
        data: {
          totalUrls: urlsStats.totalUrls || 0,
          totalClicks: urlsStats.totalClicks || 0,
          clicksLastHour: urlsStats.clicksLastHour || 0,
          activeUrls: urlsStats.activeUrls || 0,
          cacheHitRate: urlsStats.cacheHitRate || 0.0
        }
      }
    };
  }),
  getTopUrls: (limit = 10) => api.get(`/api/v1/urls/top?limit=${limit}`),
  getUrlAnalytics: (code) => api.get(`/api/v1/urls/${code}/analytics`),
  getUrlRealTimeStats: (code) => api.get(`/api/v1/urls/${code}/analytics/realtime`),
  testSafety: (url) => api.get(`/api/v1/urls/safety-check?url=${encodeURIComponent(url)}`),

  // ── Rate Limiter ──────────────────────────────────────────────────────────
  checkRateLimit: (id, endpoint) => api.get(`/api/ratelimit/status?identifier=${encodeURIComponent(id)}&endpoint=${encodeURIComponent(endpoint)}`),
  getRateLimitConfig: () => api.get('/api/ratelimit/rules'),
  runBenchmark: (config) => {
    const params = new URLSearchParams();
    if (config.threads) params.append('threads', config.threads);
    if (config.requestsPerThread) params.append('requestsPerThread', config.requestsPerThread);
    if (config.endpoint) params.append('endpoint', config.endpoint);
    return api.post(`/api/benchmark/full?${params.toString()}`);
  },
  compareRateLimiters: (config) => {
    const params = new URLSearchParams();
    if (config.threads) params.append('threads', config.threads);
    if (config.requestsPerThread) params.append('requestsPerThread', config.requestsPerThread);
    if (config.endpoint) params.append('endpoint', config.endpoint);
    return api.post(`/api/benchmark/compare?${params.toString()}`);
  },
  getVisualizationState: (algo, key) => {
    const path = algo === 'TOKEN_BUCKET' ? 'token-bucket' : 'sliding-window';
    return api.get(`/api/v1/rate-limiter/visualization/${path}/${key}`);
  },

  // ── Consistent Hashing ────────────────────────────────────────────────────
  hashAddNode: (name) => api.post(`/api/consistent-hash/nodes/${name}`),
  hashRemoveNode: (name) => api.delete(`/api/consistent-hash/nodes/${name}`),
  hashGetNode: (key) => api.get(`/api/consistent-hash/node?key=${encodeURIComponent(key)}`),
  hashGetReplicas: (key, n = 3) => api.get(`/api/consistent-hash/nodes?key=${encodeURIComponent(key)}&replication=${n}`),
  hashGetRing: () => api.get('/api/consistent-hash/ring'),
  hashGetHotspots: (t = 1.5) => api.get(`/api/consistent-hash/hotspots?threshold=${t}`),
  hashGetRebalance: () => api.get('/api/consistent-hash/rebalance'),
  hashGetAllNodes: () => api.get('/api/consistent-hash/all-nodes'),
  hashGetDistribution: (keys) => api.post('/api/consistent-hash/distribution', keys),

  // ── Cache Strategies ──────────────────────────────────────────────────────
  getCacheStrategies: () => api.get('/api/v1/cache/strategies'),
  runCacheDemo: (data) => api.post('/api/v1/cache/strategies/demo', data),
  getCacheStrategyStats: () => api.get('/api/v1/cache/strategies/stats'),
  getLRUStats: (name) => api.get(`/api/v1/cache/lru/${name}/stats`),
  getLFUStats: (name) => api.get(`/api/v1/cache/lfu/${name}/stats`),
  getCacheSnapshot: (name) => api.get(`/api/v1/cache/lru/${name}/snapshot`),
  compareStrategies: (ops) => api.post('/api/v1/cache/strategies/benchmark', ops),

  // ── Distributed Locks ─────────────────────────────────────────────────────
  lockAcquire: (data) => api.post('/api/v1/locks/acquire', data),
  lockRelease: (data) => api.post('/api/v1/locks/release', data),
  lockExtend: (data) => api.post('/api/v1/locks/extend', data),
  lockGetActive: () => api.get('/api/v1/locks/active'),
  lockGetStats: () => api.get('/api/v1/locks/stats'),
  lockGetAudit: (limit = 100) => api.get(`/api/v1/locks/audit?limit=${limit}`),
  lockDemoRace: (data) => api.post('/api/v1/locks/demo/race-condition', data),
  lockDemoFencing: () => api.post('/api/v1/locks/demo/fencing'),

  // ── Bloom Filter ──────────────────────────────────────────────────────────
  bloomCreateFilter: (data) => api.post('/api/v1/bloom-filter/filters', data),
  bloomAdd: (name, item) => api.post(`/api/v1/bloom-filter/filters/${name}/add`, { item }),
  bloomCheck: (name, item) => api.post(`/api/v1/bloom-filter/filters/${name}/check`, { item }),
  bloomGetStats: (name) => api.get(`/api/v1/bloom-filter/filters/${name}/stats`),
  bloomRunDemo: (data) => api.post('/api/v1/bloom-filter/demo', data),
  bloomUrlDedup: (urls) => api.post('/api/v1/bloom-filter/url-dedup', { urls }),
  bloomUrlDedupStats: () => api.get('/api/v1/bloom-filter/url-dedup/stats'),
  bloomBenchmark: (data) => api.post('/api/v1/bloom-filter/benchmark', data),

  // ── Leader Election ───────────────────────────────────────────────────────
  leaderCurrent: () => api.get('/api/v1/leader/current'),
  leaderIsLeader: () => api.get('/api/v1/leader/is-leader'),
  leaderElect: () => api.post('/api/v1/leader/elect'),
  leaderResign: () => api.post('/api/v1/leader/resign'),
  leaderStats: () => api.get('/api/v1/leader/stats'),
  leaderDemo: () => api.post('/api/v1/leader/demo'),

  // ── Message Queue ─────────────────────────────────────────────────────────
  queueEnqueue: (name, data) => api.post(`/api/v1/queue/${name}/enqueue`, data),
  queueDequeue: (name, timeout = 5) => api.post(`/api/v1/queue/${name}/dequeue`, { timeoutSeconds: timeout }),
  queueAck: (name, messageId) => api.post(`/api/v1/queue/${name}/acknowledge`, { messageId }),
  queueNack: (name, messageId, reason) => api.post(`/api/v1/queue/${name}/nack`, { messageId, reason }),
  queueSize: (name) => api.get(`/api/v1/queue/${name}/size`),
  queuePeek: (name, count = 10) => api.get(`/api/v1/queue/${name}/peek?count=${count}`),
  queueStats: (name) => api.get(`/api/v1/queue/${name}/stats`),
  queueDlqSize: (name) => api.get(`/api/v1/queue/${name}/dlq/size`),
  queueDemo: (data) => api.post('/api/v1/queue/demo', data),

  // ── Benchmarks ────────────────────────────────────────────────────────────
  benchmarkRun: (config) => {
    const p = new URLSearchParams();
    if (config.threads) p.append('threads', config.threads);
    if (config.requestsPerThread) p.append('requestsPerThread', config.requestsPerThread);
    if (config.endpoint) p.append('endpoint', config.endpoint);
    return api.post(`/api/v1/benchmark/run?${p.toString()}`);
  },
  benchmarkFull: (config) => {
    const p = new URLSearchParams();
    if (config.threads) p.append('threads', config.threads);
    if (config.requestsPerThread) p.append('requestsPerThread', config.requestsPerThread);
    if (config.endpoint) p.append('endpoint', config.endpoint);
    return api.post(`/api/v1/benchmark/full?${p.toString()}`);
  },
  benchmarkCompare: (config) => {
    const p = new URLSearchParams();
    if (config.threads) p.append('threads', config.threads);
    if (config.requestsPerThread) p.append('requestsPerThread', config.requestsPerThread);
    if (config.endpoint) p.append('endpoint', config.endpoint);
    return api.post(`/api/v1/benchmark/compare?${p.toString()}`);
  },
  benchmarkResults: () => api.get('/api/v1/benchmark/results'),
  benchmarkLatest: () => api.get('/api/v1/benchmark/latest'),
  benchmarkSystemHealth: () => api.get('/api/v1/benchmark/system-health'),

  // ── Observability & Dashboard ─────────────────────────────────────────────
  getDashboard: () => api.get('/api/v1/observability/dashboard'),
  getAlerts: () => api.get('/api/v1/observability/alerts'),
  getCircuitBreakers: () => api.get('/api/v1/observability/circuit-breakers'),
  resetCircuitBreaker: (route) => api.post(`/api/v1/observability/circuit-breakers/${route}/reset`)
};

export default api;
