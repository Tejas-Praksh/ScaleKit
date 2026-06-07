import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { 
  Link2, 
  Gauge, 
  Database, 
  Lock, 
  AlertTriangle, 
  Trash2, 
  Hash, 
  Server, 
  ArrowRightLeft, 
  RefreshCcw,
  Sparkles,
  Layers,
  ArrowRight,
  TrendingUp,
  Cpu
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import Spinner from '../components/ui/Spinner';
import LineChart from '../components/charts/LineChart';
import { apiService } from '../services/api';
import usePolling from '../hooks/usePolling';
import { formatNumber, formatPercent, formatQPS, timeAgo } from '../utils/formatters';

const Dashboard = () => {
  const navigate = useNavigate();
  const [qpsHistory, setQpsHistory] = useState([]);
  const [cacheHistory, setCacheHistory] = useState([]);
  const [activeLocksCount, setActiveLocksCount] = useState(0);

  // Poll dashboard snapshot every 3 seconds
  const { data: snapshotResponse, loading, error, refresh } = usePolling(
    apiService.getDashboard,
    3000,
    true
  );

  const snapshot = snapshotResponse?.data?.data || null;

  // Poll active locks count
  useEffect(() => {
    const fetchLocks = async () => {
      try {
        const res = await apiService.lockGetActive();
        if (res.data?.success) {
          setActiveLocksCount(res.data.data.length);
        }
      } catch (err) {
        // Fallback silently
      }
    };
    fetchLocks();
    const id = setInterval(fetchLocks, 3000);
    return () => clearInterval(id);
  }, []);

  // Update real-time charts when new snapshot is received
  useEffect(() => {
    if (snapshot) {
      const time = new Date().toLocaleTimeString([], { 
        hour: '2-digit', 
        minute: '2-digit', 
        second: '2-digit',
        hour12: false
      });

      const qps = snapshot.system?.requestsPerSecond || 0;
      const hitRate = snapshot.urls?.cacheHitRate || 0.0;

      setQpsHistory((prev) => {
        const next = [...prev, { time, qps }];
        return next.slice(-30);
      });

      setCacheHistory((prev) => {
        const next = [...prev, { time, hitRate: hitRate * 100 }];
        return next.slice(-30);
      });
    }
  }, [snapshot]);

  // Handle resetting a circuit breaker
  const handleResetCircuitBreaker = async (route) => {
    try {
      await apiService.resetCircuitBreaker(route);
      refresh();
    } catch (err) {
      console.error("Failed to reset circuit breaker", err);
    }
  };

  if (loading && !snapshot) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] gap-6 text-center max-w-md mx-auto">
        <Spinner size="lg" />
        <div className="space-y-2">
          <h2 className="text-xl font-semibold text-[#F9FAFB]">Waking up the Backend</h2>
          <p className="text-sm text-[#9CA3AF]">
            ScaleKit is hosted on Render's free tier, which puts the backend to sleep when not in use.
          </p>
          <p className="text-sm text-[#F59E0B] font-medium animate-pulse mt-4 bg-[#F59E0B]/10 p-3 rounded-lg border border-[#F59E0B]/30">
            Booting up Spring Boot... this can take up to 2 minutes. Please don't refresh!
          </p>
        </div>
      </div>
    );
  }

  if (error && !snapshot) {
    return (
      <Alert type="warning" message="Server Warming Up" className="my-6">
        <div className="flex flex-col gap-2">
          <p>The backend server is waking up — this takes about 30–60 seconds on the free hosting tier. Please click retry in a moment!</p>
          <Button variant="outline" className="w-fit" onClick={refresh}>
            <RefreshCcw size={14} className="mr-2" /> Retry Connection
          </Button>
        </div>
      </Alert>
    );
  }

  const system = snapshot?.system || {};
  const urls = snapshot?.urls || {};
  const rateLimiter = snapshot?.rateLimiter || {};
  const cache = snapshot?.cache || {};
  const hashRing = snapshot?.hashRing || {};
  const bloomFilter = snapshot?.bloomFilter || {};
  const queue = snapshot?.queue || {};
  const activeAlerts = snapshot?.activeAlerts || [];

  // Algorithm items config
  const algorithmsList = [
    { 
      name: 'URL Shortener', 
      path: '/urls', 
      icon: Link2, 
      status: urls.totalUrls > 0 ? 'SAFE' : 'INFO', 
      desc: 'Base62 compression engine' 
    },
    { 
      name: 'Rate Limiter', 
      path: '/rate-limiter', 
      icon: Gauge, 
      status: rateLimiter.rejectionRate > 0.3 ? 'WARNING' : 'SAFE', 
      desc: 'Token Bucket & Sliding Window' 
    },
    { 
      name: 'Cache Strategy', 
      path: '/cache', 
      icon: Database, 
      status: cache.overallHitRate < 0.5 ? 'WARNING' : 'SAFE', 
      desc: 'LRU & LFU eviction states' 
    },
    { 
      name: 'Consistent Hashing', 
      path: '/hash-ring', 
      icon: RefreshCcw, 
      status: hashRing.nodeCount === 0 ? 'DANGER' : 'SAFE', 
      desc: 'Dynamic hash ring balance' 
    },
    { 
      name: 'Bloom Filter', 
      path: '/bloom-filter', 
      icon: Sparkles, 
      status: bloomFilter.avgFillRatio > 0.8 ? 'WARNING' : 'SAFE', 
      desc: 'Deduplication FPR bounds' 
    },
    { 
      name: 'Distributed Mutex', 
      path: '/locks', 
      icon: Lock, 
      status: 'SAFE', 
      desc: 'Redlock coordination & watchdog' 
    },
    { 
      name: 'Message Queue', 
      path: '/benchmarks', 
      icon: Layers, 
      status: queue.totalDLQ > 0 ? 'WARNING' : 'SAFE', 
      desc: 'Kafka-like producer/consumer' 
    }
  ];

  return (
    <div className="space-y-6">
      {/* ⚠️ Active Alerts Banner */}
      {activeAlerts.length > 0 && (
        <Alert type="error" message={`${activeAlerts.length} Active System Alert(s) Detected`} className="shadow-lg">
          <div className="space-y-1.5 mt-2">
            {activeAlerts.map((alert, idx) => (
              <div key={idx} className="flex justify-between items-center bg-black/20 p-2 rounded text-xs border border-[#EF4444]/20">
                <span className="flex items-center gap-1 text-[#EF4444]">
                  <AlertTriangle size={12} />
                  <strong>[{alert.component.toUpperCase()}]</strong> {alert.message}
                </span>
                <span className="text-[#6B7280] font-mono">{timeAgo(alert.detectedAt)}</span>
              </div>
            ))}
          </div>
        </Alert>
      )}

      {/* Row 1: Stat Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <StatCard
          value={formatNumber(urls.totalUrls)}
          label="Total URLs Shortened"
          trend="up"
          trendValue={`+${formatNumber(urls.clicksLastHour)} clicks/hr`}
          color="blue"
          icon={Link2}
        />
        <StatCard
          value={formatQPS(system.requestsPerSecond)}
          label="System Throughput"
          trend={system.requestsPerSecond > 50 ? 'up' : 'neutral'}
          trendValue={`${formatNumber(system.activeThreads)} threads`}
          color="cyan"
          icon={Cpu}
        />
        <StatCard
          value={formatPercent(urls.cacheHitRate)}
          label="Cache Hit Rate"
          trend={urls.cacheHitRate >= 0.7 ? 'up' : 'down'}
          trendValue={`${formatNumber(cache.totalEvictions)} evictions`}
          color="green"
          icon={Database}
        />
        <StatCard
          value={formatNumber(activeLocksCount)}
          label="Active Distributed Locks"
          trend="neutral"
          trendValue="0 deadlocks"
          color="purple"
          icon={Lock}
        />
      </div>

      {/* Row 2: Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card title="System Throughput (QPS)" subtitle="Live requests rate tracked via Micrometer metrics collector">
          <div className="pt-2">
            <LineChart
              data={qpsHistory}
              xAxisKey="time"
              series={[{ key: 'qps', color: '#06B6D4', name: 'Requests/sec' }]}
              height={260}
            />
          </div>
        </Card>
        
        <Card title="Cache Hit Rate (%)" subtitle="Rolling percentage of cache hits versus total lookups">
          <div className="pt-2">
            <LineChart
              data={cacheHistory}
              xAxisKey="time"
              series={[{ key: 'hitRate', color: '#10B981', name: 'Hit Rate %' }]}
              height={260}
            />
          </div>
        </Card>
      </div>

      {/* Row 3: Subsystem Breakdowns */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Circuit Breakers */}
        <Card title="Circuit Breakers" subtitle="Route failure tripwires">
          <div className="space-y-3.5 mt-2">
            {snapshot?.circuitBreakerStats && Object.keys(snapshot.circuitBreakerStats).length > 0 ? (
              Object.entries(snapshot.circuitBreakerStats).map(([name, cb]) => (
                <div key={name} className="flex justify-between items-center border-b border-[#374151]/50 pb-2.5 last:border-0 last:pb-0">
                  <div className="flex flex-col">
                    <span className="font-semibold text-[#F9FAFB] text-sm">{cb.routeName}</span>
                    <span className="text-[11px] text-[#6B7280] font-number">{cb.failures} failures</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <Badge variant={cb.state === 'OPEN' ? 'DANGER' : cb.state === 'HALF_OPEN' ? 'WARNING' : 'SAFE'}>
                      {cb.state}
                    </Badge>
                    {cb.state === 'OPEN' && (
                      <Button
                        variant="outline"
                        className="py-1 px-2 text-[10px] h-7"
                        onClick={() => handleResetCircuitBreaker(name)}
                      >
                        Reset
                      </Button>
                    )}
                  </div>
                </div>
              ))
            ) : (
              <div className="text-center text-sm text-[#6B7280] py-6">No circuit breakers registered.</div>
            )}
          </div>
        </Card>

        {/* Rate Limiter Status */}
        <Card title="Rate Limiter Core" subtitle="Rolling sliding-window performance metrics">
          <div className="space-y-4 py-2">
            <div className="flex justify-between border-b border-[#374151]/30 pb-2">
              <span className="text-sm text-[#9CA3AF]">Allowed Requests</span>
              <span className="font-bold text-[#F9FAFB] font-number">{formatNumber(rateLimiter.requestsAllowed)}</span>
            </div>
            <div className="flex justify-between border-b border-[#374151]/30 pb-2">
              <span className="text-sm text-[#9CA3AF]">Rejected Requests</span>
              <span className="font-bold text-[#EF4444] font-number">{formatNumber(rateLimiter.requestsRejected)}</span>
            </div>
            <div className="flex justify-between border-b border-[#374151]/30 pb-2">
              <span className="text-sm text-[#9CA3AF]">Rejection Rate</span>
              <span className={rateLimiter.rejectionRate > 0.05 ? "font-bold text-[#F59E0B] font-number" : "font-bold text-[#10B981] font-number"}>
                {formatPercent(rateLimiter.rejectionRate)}
              </span>
            </div>
            <div className="text-xs text-[#6B7280] leading-relaxed">
              Rate limiting enforces capacity bounds. Under heavy load, rejection rate scales to preserve backend service integrity.
            </div>
          </div>
        </Card>

        {/* Message Queue Depths */}
        <Card title="Mini Message Queue" subtitle="Kafka-like asynchronous backpressure buffers">
          <div className="space-y-4 py-2">
            <div className="flex justify-between border-b border-[#374151]/30 pb-2">
              <span className="text-sm text-[#9CA3AF]">Buffered Messages</span>
              <span className={queue.totalPending > 50 ? "font-bold text-[#F59E0B] font-number" : "font-bold text-[#3B82F6] font-number"}>
                {formatNumber(queue.totalPending)}
              </span>
            </div>
            <div className="flex justify-between border-b border-[#374151]/30 pb-2">
              <span className="text-sm text-[#9CA3AF]">Dead Letter Queue (DLQ)</span>
              <span className={queue.totalDLQ > 0 ? "font-bold text-[#EF4444] font-number" : "font-bold text-[#6B7280] font-number"}>
                {formatNumber(queue.totalDLQ)}
              </span>
            </div>
            <div className="text-xs text-[#6B7280] leading-relaxed">
              Failed messages undergo up to 3 consumer retries with exponential backoff before routing to the DLQ to prevent blocking the broker.
            </div>
          </div>
        </Card>
      </div>

      {/* Row 4: Algorithm Health Grid */}
      <Card title="Distributed Algorithms Health Grid" subtitle="Live health statuses and fast navigation endpoints">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-2">
          {algorithmsList.map((algo) => {
            const Icon = algo.icon;
            return (
              <div 
                key={algo.name}
                onClick={() => navigate(algo.path)}
                className="flex items-center justify-between p-4 bg-[#111827] border border-[#374151] rounded-lg cursor-pointer hover:border-[#3B82F6] hover:bg-[#1F2937]/35 transition-all group"
              >
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded bg-[#0A0E1A] text-[#9CA3AF] group-hover:text-[#3B82F6] transition-colors">
                    <Icon size={18} />
                  </div>
                  <div>
                    <h4 className="text-sm font-semibold text-[#F9FAFB]">{algo.name}</h4>
                    <p className="text-[11px] text-[#6B7280] line-clamp-1">{algo.desc}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge variant={algo.status === 'SAFE' ? 'SAFE' : algo.status === 'WARNING' ? 'WARNING' : algo.status === 'DANGER' ? 'DANGER' : 'INFO'}>
                    {algo.status}
                  </Badge>
                  <ArrowRight size={14} className="text-[#6B7280] group-hover:translate-x-1 transition-transform opacity-0 group-hover:opacity-100" />
                </div>
              </div>
            );
          })}
        </div>
      </Card>
    </div>
  );
};

export default Dashboard;
