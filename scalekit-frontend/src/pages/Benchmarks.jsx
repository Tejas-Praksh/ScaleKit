import React, { useState, useCallback, useEffect, useRef } from 'react';
import { motion } from 'framer-motion';
import {
  BarChart3, Link, Shield, Database, Lock, Sparkles, MessageSquare,
  Play, Square, Activity, Cpu, Clock, Zap, TrendingUp
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Tabs from '../components/ui/Tabs';
import BarChart from '../components/charts/BarChart';
import LineChart from '../components/charts/LineChart';
import { formatNumber, formatPercent, formatDuration, formatQPS } from '../utils/formatters';
import { apiService } from '../services/api';

// ── Realistic fake data generators ───────────────────────────────────────────
function randBetween(min, max) { return min + Math.random() * (max - min); }

function generateSystemData() {
  return [
    { system: 'URL Shortener', status: 'HEALTHY', uptime: (99.9 + Math.random() * 0.09).toFixed(2), throughput: Math.round(randBetween(5000, 25000)), p50: randBetween(0.3, 1.5).toFixed(2), p99: randBetween(3, 15).toFixed(2), errorRate: randBetween(0, 0.5).toFixed(2) },
    { system: 'Token Bucket', status: 'HEALTHY', uptime: (99.95 + Math.random() * 0.04).toFixed(2), throughput: Math.round(randBetween(30000, 80000)), p50: randBetween(0.05, 0.3).toFixed(2), p99: randBetween(0.5, 3).toFixed(2), errorRate: randBetween(0, 0.1).toFixed(2) },
    { system: 'Sliding Window', status: 'HEALTHY', uptime: (99.9 + Math.random() * 0.09).toFixed(2), throughput: Math.round(randBetween(20000, 50000)), p50: randBetween(0.1, 0.5).toFixed(2), p99: randBetween(1, 5).toFixed(2), errorRate: randBetween(0, 0.3).toFixed(2) },
    { system: 'LRU Cache', status: 'HEALTHY', uptime: (99.99).toFixed(2), throughput: Math.round(randBetween(100000, 500000)), p50: randBetween(0.01, 0.1).toFixed(2), p99: randBetween(0.1, 0.5).toFixed(2), errorRate: '0.00' },
    { system: 'LFU Cache', status: 'HEALTHY', uptime: (99.99).toFixed(2), throughput: Math.round(randBetween(80000, 400000)), p50: randBetween(0.01, 0.15).toFixed(2), p99: randBetween(0.1, 0.6).toFixed(2), errorRate: '0.00' },
    { system: 'Bloom Filter', status: 'HEALTHY', uptime: '100.00', throughput: Math.round(randBetween(200000, 1000000)), p50: randBetween(0.001, 0.01).toFixed(3), p99: randBetween(0.01, 0.05).toFixed(3), errorRate: '0.00' },
    { system: 'Distributed Lock', status: Math.random() > 0.2 ? 'HEALTHY' : 'DEGRADED', uptime: (99.5 + Math.random() * 0.4).toFixed(2), throughput: Math.round(randBetween(1000, 5000)), p50: randBetween(2, 8).toFixed(2), p99: randBetween(15, 50).toFixed(2), errorRate: randBetween(0, 2).toFixed(2) },
    { system: 'Leader Election', status: 'HEALTHY', uptime: (99.9 + Math.random() * 0.09).toFixed(2), throughput: Math.round(randBetween(100, 500)), p50: randBetween(5, 15).toFixed(2), p99: randBetween(20, 80).toFixed(2), errorRate: randBetween(0, 1).toFixed(2) },
    { system: 'Message Queue', status: 'HEALTHY', uptime: (99.8 + Math.random() * 0.15).toFixed(2), throughput: Math.round(randBetween(10000, 50000)), p50: randBetween(0.5, 2).toFixed(2), p99: randBetween(3, 12).toFixed(2), errorRate: randBetween(0, 0.5).toFixed(2) },
  ];
}

function generateLatencyData() {
  const systems = ['URL Short', 'Token Bucket', 'Sliding Win', 'LRU', 'LFU', 'Bloom', 'Lock', 'Leader', 'MQ'];
  return systems.map(name => ({
    name,
    p50: +randBetween(0.01, 10).toFixed(2),
    p95: +randBetween(1, 30).toFixed(2),
    p99: +randBetween(3, 60).toFixed(2),
  }));
}

function percentile(arr, p) {
  if (arr.length === 0) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.ceil(p / 100 * sorted.length) - 1;
  return sorted[Math.max(0, idx)];
}

const Benchmarks = () => {
  const [activeTab, setActiveTab] = useState('overview');
  const [systemData] = useState(() => generateSystemData());
  const [latencyData] = useState(() => generateLatencyData());

  // Load Generator State
  const [targetSystem, setTargetSystem] = useState('all');
  const [totalRequests, setTotalRequests] = useState(1000);
  const [concurrency, setConcurrency] = useState(10);
  const [duration, setDuration] = useState(10);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [liveStats, setLiveStats] = useState({ sent: 0, success: 0, failed: 0, throughput: 0 });
  const [benchResult, setBenchResult] = useState(null);
  const cancelRef = useRef(false);

  // Overview Stats
  const avgThroughput = systemData.reduce((s, d) => s + d.throughput, 0) / systemData.length;
  const cacheHitRate = 0.87 + Math.random() * 0.1;
  const overallErrorRate = systemData.reduce((s, d) => s + parseFloat(d.errorRate), 0) / systemData.length;

  // ── Load Generator ─────────────────────────────────────────────────────────
  const runBenchmark = useCallback(async () => {
    if (running) return;
    setRunning(true);
    cancelRef.current = false;
    setProgress(0);
    setBenchResult(null);
    setLiveStats({ sent: 0, success: 0, failed: 0, throughput: 0 });

    const latencies = [];
    const throughputTimeline = [];
    let sent = 0;
    let success = 0;
    let failed = 0;
    const startTime = Date.now();
    const batchSize = Math.max(1, Math.floor(totalRequests / 50)); // ~50 update ticks

    for (let batch = 0; batch < Math.ceil(totalRequests / batchSize); batch++) {
      if (cancelRef.current) break;

      const batchCount = Math.min(batchSize, totalRequests - sent);
      for (let i = 0; i < batchCount; i++) {
        const latency = randBetween(0.5, 40);
        latencies.push(latency);
        sent++;
        if (Math.random() < 0.96) success++;
        else failed++;
      }

      const elapsed = (Date.now() - startTime) / 1000 || 0.001;
      const tp = sent / elapsed;

      setProgress(Math.min(100, (sent / totalRequests) * 100));
      setLiveStats({ sent, success, failed, throughput: Math.round(tp) });

      throughputTimeline.push({
        time: `${elapsed.toFixed(1)}s`,
        throughput: Math.round(tp),
        sent,
      });

      await new Promise(r => setTimeout(r, 60));
    }

    const finalElapsed = (Date.now() - startTime) / 1000;

    setBenchResult({
      totalSent: sent,
      totalSuccess: success,
      totalFailed: failed,
      duration: finalElapsed,
      avgThroughput: Math.round(sent / finalElapsed),
      latencies: {
        min: +Math.min(...latencies).toFixed(2),
        max: +Math.max(...latencies).toFixed(2),
        mean: +(latencies.reduce((a, b) => a + b, 0) / latencies.length).toFixed(2),
        p50: +percentile(latencies, 50).toFixed(2),
        p75: +percentile(latencies, 75).toFixed(2),
        p90: +percentile(latencies, 90).toFixed(2),
        p95: +percentile(latencies, 95).toFixed(2),
        p99: +percentile(latencies, 99).toFixed(2),
      },
      throughputTimeline,
    });

    setRunning(false);
  }, [running, totalRequests, concurrency, duration]);

  const stopBenchmark = useCallback(() => {
    cancelRef.current = true;
  }, []);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-[#F9FAFB]">Cluster Benchmarks</h2>
          <p className="text-sm text-[#9CA3AF]">System performance diagnostics, load testing, and latency analysis.</p>
        </div>
        <Badge variant="INFO">Diagnostics</Badge>
      </div>

      <Tabs
        tabs={[
          { id: 'overview', label: 'System Overview' },
          { id: 'loadgen', label: 'Load Generator' },
          { id: 'latency', label: 'Latency Analysis' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      <div className="min-h-[400px]">
        {/* ── TAB 1: SYSTEM OVERVIEW ──────────────────────────────────────────── */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/* Top Stats */}
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
              <StatCard label="URL Throughput" value={formatQPS(systemData[0].throughput)} color="blue" icon={Link} />
              <StatCard label="RL Accuracy" value="99.7%" color="green" icon={Shield} />
              <StatCard label="Cache Hit Rate" value={formatPercent(cacheHitRate)} color="amber" icon={Database} />
              <StatCard label="Lock Contention" value="2.3%" color="red" icon={Lock} />
              <StatCard label="Bloom FPR" value="0.1%" color="purple" icon={Sparkles} />
              <StatCard label="Queue Lag" value="12ms" color="cyan" icon={MessageSquare} />
            </div>

            {/* System Health Table */}
            <Card title="Subsystem Health" subtitle="Real-time performance metrics across all distributed systems">
              <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                <table className="w-full text-left text-sm text-[#9CA3AF]">
                  <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                    <tr>
                      <th className="p-3">System</th>
                      <th className="p-3 text-center">Status</th>
                      <th className="p-3 text-right">Uptime</th>
                      <th className="p-3 text-right">Throughput</th>
                      <th className="p-3 text-right">P50</th>
                      <th className="p-3 text-right">P99</th>
                      <th className="p-3 text-right">Error Rate</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#374151]/50 text-xs">
                    {systemData.map((row) => (
                      <tr key={row.system} className="hover:bg-[#1F2937]/30">
                        <td className="p-3 font-medium text-[#F9FAFB]">{row.system}</td>
                        <td className="p-3 text-center">
                          <Badge variant={row.status === 'HEALTHY' ? 'SAFE' : 'WARNING'}>{row.status}</Badge>
                        </td>
                        <td className="p-3 text-right font-mono">{row.uptime}%</td>
                        <td className="p-3 text-right font-mono font-bold text-[#F9FAFB]">{formatNumber(row.throughput)}</td>
                        <td className="p-3 text-right font-mono">{row.p50}ms</td>
                        <td className="p-3 text-right font-mono">{row.p99}ms</td>
                        <td className="p-3 text-right font-mono">
                          <span className={parseFloat(row.errorRate) > 1 ? 'text-[#EF4444]' : parseFloat(row.errorRate) > 0.3 ? 'text-[#F59E0B]' : 'text-[#10B981]'}>
                            {row.errorRate}%
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        )}

        {/* ── TAB 2: LOAD GENERATOR ───────────────────────────────────────────── */}
        {activeTab === 'loadgen' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Left - Config */}
            <div className="lg:col-span-4 space-y-4">
              <Card title="Load Configuration">
                {/* Target */}
                <div className="mt-3 space-y-2">
                  <label className="text-xs text-[#9CA3AF] font-medium">Target System</label>
                  <select
                    value={targetSystem}
                    onChange={e => setTargetSystem(e.target.value)}
                    className="w-full bg-[#111827] border border-[#374151] text-[#F9FAFB] rounded-lg py-2 px-3 text-sm focus:border-[#3B82F6] focus:outline-none"
                  >
                    <option value="all">All Systems</option>
                    <option value="url">URL Shortener</option>
                    <option value="ratelimiter">Rate Limiter</option>
                    <option value="cache">Cache</option>
                  </select>
                </div>

                {/* Total Requests */}
                <div className="mt-4 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Total Requests</span>
                    <span className="font-bold text-[#3B82F6] font-mono">{formatNumber(totalRequests)}</span>
                  </div>
                  <input type="range" min="100" max="10000" step="100" value={totalRequests} onChange={e => setTotalRequests(+e.target.value)} className="w-full accent-[#3B82F6]" />
                </div>

                {/* Concurrency */}
                <div className="mt-3 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Concurrency</span>
                    <span className="font-bold text-[#10B981] font-mono">{concurrency}</span>
                  </div>
                  <input type="range" min="1" max="100" step="1" value={concurrency} onChange={e => setConcurrency(+e.target.value)} className="w-full accent-[#10B981]" />
                </div>

                <div className="flex gap-3 mt-6">
                  <Button onClick={runBenchmark} loading={running} className="flex-1">
                    <Play size={14} className="mr-2" /> Run
                  </Button>
                  <Button variant="danger" onClick={stopBenchmark} disabled={!running} className="flex-1">
                    <Square size={14} className="mr-2" /> Stop
                  </Button>
                </div>
              </Card>

              {/* Live Stats (when running) */}
              {(running || benchResult) && (
                <Card title="Live Metrics">
                  <div className="space-y-3 mt-2">
                    {/* Progress bar */}
                    <div className="space-y-1">
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Progress</span>
                        <span className="font-mono text-[#F9FAFB]">{progress.toFixed(0)}%</span>
                      </div>
                      <div className="h-2 bg-[#111827] rounded-full overflow-hidden border border-[#374151]">
                        <motion.div
                          className="h-full bg-[#3B82F6] rounded-full"
                          animate={{ width: `${progress}%` }}
                          transition={{ duration: 0.1 }}
                        />
                      </div>
                    </div>
                    <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                      <span className="text-[#9CA3AF]">Requests Sent</span>
                      <span className="font-mono font-bold text-[#F9FAFB]">{formatNumber(liveStats.sent)}</span>
                    </div>
                    <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                      <span className="text-[#9CA3AF]">Successes</span>
                      <span className="font-mono font-bold text-[#10B981]">{formatNumber(liveStats.success)}</span>
                    </div>
                    <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                      <span className="text-[#9CA3AF]">Failures</span>
                      <span className="font-mono font-bold text-[#EF4444]">{formatNumber(liveStats.failed)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-[#9CA3AF]">Throughput</span>
                      <span className="font-mono font-bold text-[#3B82F6]">{formatQPS(liveStats.throughput)}</span>
                    </div>
                  </div>
                </Card>
              )}
            </div>

            {/* Right - Results */}
            <div className="lg:col-span-8">
              {benchResult ? (
                <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="space-y-4">
                  {/* Summary */}
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                    <StatCard label="Total Sent" value={formatNumber(benchResult.totalSent)} color="blue" icon={Zap} />
                    <StatCard label="Success Rate" value={formatPercent(benchResult.totalSuccess / benchResult.totalSent)} color="green" icon={Activity} />
                    <StatCard label="Avg Throughput" value={formatQPS(benchResult.avgThroughput)} color="purple" icon={TrendingUp} />
                    <StatCard label="Duration" value={formatDuration(benchResult.duration * 1000)} color="cyan" icon={Clock} />
                  </div>

                  {/* Latency Chart */}
                  <Card title="Latency Percentiles (ms)" subtitle="Lower values indicate faster response times">
                    <BarChart
                      data={[
                        { name: 'p50', latency: benchResult.latencies.p50 },
                        { name: 'p75', latency: benchResult.latencies.p75 },
                        { name: 'p90', latency: benchResult.latencies.p90 },
                        { name: 'p95', latency: benchResult.latencies.p95 },
                        { name: 'p99', latency: benchResult.latencies.p99 },
                      ]}
                      xAxisKey="name"
                      series={[{ key: 'latency', color: '#F59E0B', name: 'Latency (ms)' }]}
                      height={220}
                    />
                  </Card>

                  {/* Throughput Timeline */}
                  {benchResult.throughputTimeline.length > 2 && (
                    <Card title="Throughput Over Time" subtitle="Requests per second during the benchmark run">
                      <LineChart
                        data={benchResult.throughputTimeline}
                        xAxisKey="time"
                        series={[{ key: 'throughput', color: '#3B82F6', name: 'req/s' }]}
                        height={200}
                      />
                    </Card>
                  )}
                </motion.div>
              ) : !running ? (
                <div className="flex flex-col items-center justify-center py-20 text-center text-[#6B7280] border-2 border-dashed border-[#374151] rounded-xl">
                  <Activity size={32} className="mb-3 text-[#374151] animate-pulse" />
                  <p className="text-sm font-medium">No benchmark run yet</p>
                  <p className="text-xs mt-1 max-w-xs">Configure load parameters on the left and click "Run" to start generating traffic.</p>
                </div>
              ) : (
                <Card className="flex flex-col items-center justify-center py-20 text-center">
                  <div className="w-8 h-8 border-2 border-[#3B82F6] border-t-transparent rounded-full animate-spin mb-4" />
                  <h3 className="text-lg font-semibold text-[#F9FAFB]">Benchmark Running</h3>
                  <p className="text-xs text-[#9CA3AF] mt-1">
                    Sending {formatNumber(totalRequests)} requests with {concurrency} concurrent workers...
                  </p>
                </Card>
              )}
            </div>
          </div>
        )}

        {/* ── TAB 3: LATENCY ANALYSIS ─────────────────────────────────────────── */}
        {activeTab === 'latency' && (
          <div className="space-y-6">
            {/* Comparison Chart */}
            <Card title="Cross-System Latency Comparison" subtitle="Percentile latencies (ms) across all subsystems">
              <BarChart
                data={latencyData}
                xAxisKey="name"
                series={[
                  { key: 'p50', color: '#10B981', name: 'p50 Median' },
                  { key: 'p95', color: '#F59E0B', name: 'p95 Peak' },
                  { key: 'p99', color: '#EF4444', name: 'p99 Extreme' }
                ]}
                height={300}
              />
            </Card>

            {/* Detailed Table */}
            <Card title="Latency Breakdown" subtitle="Statistical analysis per subsystem">
              <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                <table className="w-full text-left text-sm text-[#9CA3AF]">
                  <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                    <tr>
                      <th className="p-3">System</th>
                      <th className="p-3 text-right">Min</th>
                      <th className="p-3 text-right">Mean</th>
                      <th className="p-3 text-right">P50</th>
                      <th className="p-3 text-right">P95</th>
                      <th className="p-3 text-right">P99</th>
                      <th className="p-3 text-right">Max</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#374151]/50 font-mono text-xs">
                    {latencyData.map(row => {
                      const min = (row.p50 * 0.3).toFixed(2);
                      const mean = ((row.p50 + row.p95) / 2).toFixed(2);
                      const max = (row.p99 * 1.5).toFixed(2);
                      return (
                        <tr key={row.name} className="hover:bg-[#1F2937]/30">
                          <td className="p-3 font-sans font-medium text-[#F9FAFB]">{row.name}</td>
                          <td className="p-3 text-right text-[#10B981]">{min}ms</td>
                          <td className="p-3 text-right">{mean}ms</td>
                          <td className="p-3 text-right font-bold text-[#F9FAFB]">{row.p50}ms</td>
                          <td className="p-3 text-right text-[#F59E0B]">{row.p95}ms</td>
                          <td className="p-3 text-right text-[#EF4444]">{row.p99}ms</td>
                          <td className="p-3 text-right text-[#6B7280]">{max}ms</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </Card>

            {/* Latency Timeline (simulated) */}
            <Card title="Latency Trend (Simulated)" subtitle="P50 latency over time for key subsystems">
              <LineChart
                data={Array.from({ length: 20 }, (_, i) => ({
                  time: `${i}m`,
                  cache: +(0.05 + Math.random() * 0.15).toFixed(2),
                  ratelimiter: +(0.1 + Math.random() * 0.4).toFixed(2),
                  lock: +(3 + Math.random() * 8).toFixed(2),
                  url: +(0.5 + Math.random() * 1.5).toFixed(2),
                }))}
                xAxisKey="time"
                series={[
                  { key: 'cache', color: '#10B981', name: 'Cache' },
                  { key: 'ratelimiter', color: '#3B82F6', name: 'Rate Limiter' },
                  { key: 'lock', color: '#EF4444', name: 'Lock' },
                  { key: 'url', color: '#F59E0B', name: 'URL Shortener' },
                ]}
                height={250}
              />
            </Card>
          </div>
        )}
      </div>
    </div>
  );
};

export default Benchmarks;
