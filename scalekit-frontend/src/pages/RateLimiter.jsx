import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { 
  Play, 
  Zap, 
  Flame, 
  Sliders, 
  Activity, 
  RotateCcw, 
  Info, 
  AlertTriangle, 
  CheckCircle,
  HelpCircle,
  HelpCircle as QuestionIcon
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Tabs from '../components/ui/Tabs';
import Alert from '../components/ui/Alert';
import Spinner from '../components/ui/Spinner';
import BarChart from '../components/charts/BarChart';
import { apiService } from '../services/api';
import useApi from '../hooks/useApi';
import { formatNumber, formatPercent } from '../utils/formatters';

const RateLimiter = () => {
  const [activeSubTab, setActiveSubTab] = useState('visualizer');

  // ── VISUALIZER STATE ──────────────────────────────────────────────────────
  const [selectedAlgo, setSelectedAlgo] = useState('TOKEN_BUCKET'); // 'TOKEN_BUCKET' | 'SLIDING_WINDOW'
  const [speedMultiplier, setSpeedMultiplier] = useState(1); // 1x, 2x, 5x

  // Token Bucket Simulation State
  const [tbTokens, setTbTokens] = useState(10);
  const tbCapacity = 10;
  const tbRefillRate = 1; // 1 token per second base
  const [tbAllowed, setTbAllowed] = useState(0);
  const [tbRejected, setTbRejected] = useState(0);
  const [tbFlash, setTbFlash] = useState(false);

  // Sliding Window Simulation State
  const [swRequests, setSwRequests] = useState([]); // array of timestamps (ms)
  const swLimit = 10;
  const swWindowMs = 20000; // 20 seconds window for faster visual feedback
  const [swAllowed, setSwAllowed] = useState(0);
  const [swRejected, setSwRejected] = useState(0);
  const [swFlash, setSwFlash] = useState(false);

  // Local ticker for time elapsed
  const [currentTime, setCurrentTime] = useState(Date.now());

  // ── COMPARE STATE ─────────────────────────────────────────────────────────
  const [compareLogs, setCompareLogs] = useState([]); // array of { time, id, tbStatus, swStatus }
  const [compareRunning, setCompareRunning] = useState(false);

  // ── BENCHMARK STATE ───────────────────────────────────────────────────────
  const [benchRequests, setBenchRequests] = useState(1000);
  const [benchConcurrency, setBenchConcurrency] = useState(10);
  const [benchmarkResult, setBenchmarkResult] = useState(null);
  const { loading: benchLoading, error: benchError, execute: runCompareApi } = useApi(apiService.compareRateLimiters);

  // ── 1. TICKER FOR SIMULATIONS ─────────────────────────────────────────────
  useEffect(() => {
    const interval = setInterval(() => {
      const now = Date.now();
      setCurrentTime(now);

      // A. Refill Token Bucket
      setTbTokens((prev) => {
        const refillAmount = (tbRefillRate * 0.1 * speedMultiplier);
        return Math.min(tbCapacity, prev + refillAmount);
      });

      // B. Expire Sliding Window requests
      setSwRequests((prev) => {
        const cutoff = now - swWindowMs;
        return prev.filter((t) => t.time > cutoff);
      });
    }, 100);

    return () => clearInterval(interval);
  }, [speedMultiplier]);

  // ── 2. VISUALIZER ACTIONS ─────────────────────────────────────────────────
  const sendRequest = (count = 1) => {
    const now = Date.now();
    
    if (selectedAlgo === 'TOKEN_BUCKET') {
      let allowed = 0;
      let rejected = 0;
      
      setTbTokens((prev) => {
        let current = prev;
        for (let i = 0; i < count; i++) {
          if (current >= 1) {
            current -= 1;
            allowed++;
          } else {
            rejected++;
          }
        }
        
        if (rejected > 0) {
          setTbFlash(true);
          setTimeout(() => setTbFlash(false), 300);
        }
        
        setTbAllowed((a) => a + allowed);
        setTbRejected((r) => r + rejected);
        return current;
      });
    } else {
      // SLIDING WINDOW
      let allowed = 0;
      let rejected = 0;

      setSwRequests((prev) => {
        let updated = [...prev];
        const cutoff = now - swWindowMs;
        
        for (let i = 0; i < count; i++) {
          // Filter active in window
          const activeInWindow = updated.filter(t => t.time > cutoff && t.allowed);
          if (activeInWindow.length < swLimit) {
            updated.push({ time: now + (i * 20), allowed: true });
            allowed++;
          } else {
            updated.push({ time: now + (i * 20), allowed: false });
            rejected++;
          }
        }

        if (rejected > 0) {
          setSwFlash(true);
          setTimeout(() => setSwFlash(false), 300);
        }

        setSwAllowed((a) => a + allowed);
        setSwRejected((r) => r + rejected);
        return updated;
      });
    }
  };

  const handleResetVisualizer = () => {
    setTbTokens(10);
    setTbAllowed(0);
    setTbRejected(0);
    setSwRequests([]);
    setSwAllowed(0);
    setSwRejected(0);
  };

  // ── 3. COMPARE ACTIONS ────────────────────────────────────────────────────
  const runCompareBurst = async (requestsCount = 15) => {
    if (compareRunning) return;
    setCompareRunning(true);
    setCompareLogs([]);

    // Temporary variables for synchronous state estimation
    let tempTbTokens = tbTokens;
    let tempSwRequests = [...swRequests];

    const logs = [];
    const now = Date.now();
    const cutoff = now - swWindowMs;

    for (let i = 0; i < requestsCount; i++) {
      const timeStr = new Date(now + i * 50).toLocaleTimeString([], { 
        hour: '2-digit', 
        minute: '2-digit', 
        second: '2-digit',
        fractionalSecondDigits: 3 
      });

      // Token Bucket evaluation
      let tbStatus = 'REJECTED';
      if (tempTbTokens >= 1) {
        tempTbTokens -= 1;
        tbStatus = 'ALLOWED';
      }

      // Sliding Window evaluation
      let swStatus = 'REJECTED';
      const activeSw = tempSwRequests.filter(t => t.time > cutoff && t.allowed);
      if (activeSw.length < swLimit) {
        tempSwRequests.push({ time: now + i * 50, allowed: true });
        swStatus = 'ALLOWED';
      } else {
        tempSwRequests.push({ time: now + i * 50, allowed: false });
      }

      logs.push({
        id: i + 1,
        time: timeStr,
        tbStatus,
        swStatus
      });

      // Stagger updates for visual satisfaction
      await new Promise(resolve => setTimeout(resolve, 80));
      setCompareLogs([...logs]);
      
      // Update actual simulation counters
      if (selectedAlgo === 'TOKEN_BUCKET') {
        if (tbStatus === 'ALLOWED') {
          setTbTokens(t => Math.max(0, t - 1));
          setTbAllowed(a => a + 1);
        } else {
          setTbRejected(r => r + 1);
        }
      } else {
        setSwRequests(prev => {
          const next = [...prev, { time: now + i * 50, allowed: swStatus === 'ALLOWED' }];
          return next;
        });
        if (swStatus === 'ALLOWED') {
          setSwAllowed(a => a + 1);
        } else {
          setSwRejected(r => r + 1);
        }
      }
    }
    setCompareRunning(false);
  };

  // ── 4. BENCHMARK ACTIONS ──────────────────────────────────────────────────
  const handleRunBenchmark = async (e) => {
    e.preventDefault();
    setBenchmarkResult(null);

    const payload = {
      threads: benchConcurrency,
      requestsPerThread: Math.round(benchRequests / benchConcurrency),
      endpoint: 'api-global'
    };

    try {
      const res = await runCompareApi(payload);
      if (res) {
        setBenchmarkResult(res);
      }
    } catch (err) {
      console.error("Benchmark failed", err);
    }
  };

  // Format benchmark chart data
  const getChartData = () => {
    if (!benchmarkResult || !benchmarkResult.results) return [];
    return Object.entries(benchmarkResult.results).map(([key, value]) => {
      const lat = value.latency || {};
      return {
        name: key.replace('_', ' '),
        p50: lat.p50Ms || 0,
        p95: lat.p95Ms || 0,
        p99: lat.p99Ms || 0
      };
    });
  };

  return (
    <div className="space-y-8">
      {/* Subtab Selector */}
      <Tabs
        tabs={[
          { id: 'visualizer', label: 'Algorithm Visualizer' },
          { id: 'compare', label: 'Compare Dynamics' },
          { id: 'benchmark', label: 'Load Benchmarks' }
        ]}
        activeTab={activeSubTab}
        onChange={setActiveSubTab}
      />

      <div className="min-h-[400px]">
        {/* TAB 1: VISUALIZER */}
        {activeSubTab === 'visualizer' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
            {/* Visual Screen Card */}
            <div className="lg:col-span-8 space-y-6">
              <Card>
                {/* Algorithm and Speed Control Header */}
                <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 border-b border-[#374151]/50 pb-4 mb-6">
                  <div className="flex gap-2 bg-[#111827] p-1 rounded-lg border border-[#374151]">
                    <button
                      onClick={() => setSelectedAlgo('TOKEN_BUCKET')}
                      className={`px-3 py-1.5 rounded text-xs font-semibold transition-all ${
                        selectedAlgo === 'TOKEN_BUCKET' 
                          ? 'bg-[#3B82F6] text-white shadow' 
                          : 'text-[#9CA3AF] hover:text-[#F9FAFB]'
                      }`}
                    >
                      Token Bucket
                    </button>
                    <button
                      onClick={() => setSelectedAlgo('SLIDING_WINDOW')}
                      className={`px-3 py-1.5 rounded text-xs font-semibold transition-all ${
                        selectedAlgo === 'SLIDING_WINDOW' 
                          ? 'bg-[#3B82F6] text-white shadow' 
                          : 'text-[#9CA3AF] hover:text-[#F9FAFB]'
                      }`}
                    >
                      Sliding Window
                    </button>
                  </div>

                  <div className="flex items-center gap-3 w-full sm:w-auto">
                    <span className="text-xs text-[#9CA3AF] whitespace-nowrap">Refill Speed:</span>
                    <input
                      type="range"
                      min="1"
                      max="5"
                      step="1"
                      value={speedMultiplier}
                      onChange={(e) => setSpeedMultiplier(parseInt(e.target.value))}
                      className="w-24 accent-[#3B82F6]"
                    />
                    <span className="text-xs font-bold text-[#3B82F6] font-number w-6">{speedMultiplier}x</span>
                    <button
                      onClick={handleResetVisualizer}
                      className="p-2 text-[#9CA3AF] hover:text-[#F9FAFB] hover:bg-[#1F2937] rounded-lg transition-colors ml-2"
                      title="Reset Stats"
                    >
                      <RotateCcw size={15} />
                    </button>
                  </div>
                </div>

                {/* VISUALIZER WINDOW */}
                <div className="relative h-[340px] bg-[#0A0E1A]/80 border border-[#374151] rounded-xl flex items-center justify-center p-6 overflow-hidden">
                  
                  {/* Token Bucket Visual */}
                  {selectedAlgo === 'TOKEN_BUCKET' && (
                    <div className="relative flex flex-col items-center justify-center w-full h-full">
                      {/* Glassmorphic Bucket */}
                      <motion.div 
                        animate={tbFlash ? { scale: [1, 0.97, 1.02, 1], borderColor: '#EF4444' } : {}}
                        className={`w-64 h-64 border-4 ${tbFlash ? 'border-[#EF4444]' : 'border-[#374151]'} border-t-transparent rounded-b-[4rem] relative overflow-hidden bg-white/5 backdrop-blur-sm flex flex-wrap content-end justify-center p-4 gap-2`}
                      >
                        {/* Shimmer liquid effect representing capacity */}
                        <div 
                          className="absolute bottom-0 left-0 right-0 bg-[#3B82F6]/10 border-t border-[#3B82F6]/30 transition-all duration-300 pointer-events-none"
                          style={{ height: `${(tbTokens / tbCapacity) * 100}%` }}
                        />

                        {/* Animated Balls */}
                        <AnimatePresence>
                          {Array.from({ length: Math.floor(tbTokens) }).map((_, idx) => (
                            <motion.div
                              key={idx}
                              initial={{ scale: 0, y: -50 }}
                              animate={{ scale: 1, y: 0 }}
                              exit={{ scale: 0, opacity: 0 }}
                              className="h-8 w-8 rounded-full bg-gradient-to-tr from-[#2563EB] to-[#60A5FA] border border-[#93C5FD]/30 shadow-[0_0_10px_rgba(59,130,246,0.5)] flex items-center justify-center text-[10px] font-bold text-white font-number"
                            >
                              T
                            </motion.div>
                          ))}
                        </AnimatePresence>
                      </motion.div>
                      <span className="text-xs text-[#6B7280] uppercase tracking-widest mt-4 font-semibold">Bucket Capacity: 10 Tokens</span>
                    </div>
                  )}

                  {/* Sliding Window Visual */}
                  {selectedAlgo === 'SLIDING_WINDOW' && (
                    <div className="w-full flex flex-col justify-between h-full py-4">
                      {/* Boundary lines */}
                      <div className="flex justify-between items-center text-xs text-[#6B7280] border-b border-[#374151]/50 pb-2">
                        <span>Window Start: -20s</span>
                        <Badge variant="INFO">Sliding Window Limit: 10</Badge>
                        <span>Current Time</span>
                      </div>

                      {/* Moving timeline area */}
                      <div className="relative flex-1 border-y border-[#374151]/30 my-4 bg-white/5 rounded overflow-hidden flex items-center">
                        {/* Shifting background grid */}
                        <div className="absolute inset-0 bg-[linear-gradient(to_right,#374151_1px,transparent_1px)] bg-[size:40px_100%] opacity-15" />
                        
                        {/* Timeline axis */}
                        <div className="absolute left-0 right-0 h-0.5 bg-[#374151]" />
                        
                        {/* Sliding Data Points */}
                        {swRequests.map((req, idx) => {
                          const elapsed = currentTime - req.time;
                          const percent = 100 - (elapsed / swWindowMs) * 100;
                          
                          if (percent < 0) return null;

                          return (
                            <motion.div
                              key={idx}
                              className="absolute"
                              style={{ left: `${percent}%` }}
                            >
                              <div className="flex flex-col items-center -translate-x-1/2">
                                <motion.div 
                                  initial={{ scale: 0 }}
                                  animate={{ scale: 1 }}
                                  className={`h-4 w-4 rounded-full ${
                                    req.allowed 
                                      ? 'bg-gradient-to-tr from-[#10B981] to-[#34D399] shadow-[0_0_8px_rgba(16,185,129,0.6)]' 
                                      : 'bg-gradient-to-tr from-[#EF4444] to-[#F87171] shadow-[0_0_8px_rgba(239,68,68,0.6)]'
                                  }`} 
                                />
                                <span className={`text-[9px] font-mono mt-1 ${req.allowed ? 'text-[#10B981]' : 'text-[#EF4444]'}`}>
                                  {req.allowed ? 'ALLOW' : 'DROP'}
                                </span>
                              </div>
                            </motion.div>
                          );
                        })}
                      </div>

                      {/* Window Metrics Info */}
                      <div className="flex justify-between items-center text-xs">
                        <span className="text-[#9CA3AF]">
                          Active inside window: <strong className="text-white font-number">{swRequests.filter(r => r.allowed).length}</strong>
                        </span>
                        <span className="text-[#6B7280]">Timestamps slide off to the left and expire</span>
                      </div>
                    </div>
                  )}

                </div>
              </Card>
            </div>

            {/* Controls Panel */}
            <div className="lg:col-span-4 space-y-6">
              {/* Trigger Card */}
              <Card title="Traffic Controller" subtitle="Trigger requests to evaluate rate limits">
                <div className="space-y-4 mt-2">
                  <Button
                    variant="outline"
                    className="w-full flex items-center justify-between hover:bg-[#3B82F6] hover:text-white"
                    onClick={() => sendRequest(1)}
                  >
                    <span>Send 1 Request</span>
                    <Play size={14} />
                  </Button>
                  
                  <Button
                    variant="outline"
                    className="w-full flex items-center justify-between hover:bg-[#3B82F6] hover:text-white"
                    onClick={() => sendRequest(10)}
                  >
                    <span>Send 10 Requests</span>
                    <Zap size={14} className="text-[#F59E0B]" />
                  </Button>

                  <Button
                    variant="outline"
                    className="w-full flex items-center justify-between hover:bg-[#EF4444] hover:text-white"
                    onClick={() => sendRequest(50)}
                  >
                    <span>Burst 50 Requests</span>
                    <Flame size={14} className="text-[#EF4444] animate-pulse" />
                  </Button>
                </div>
              </Card>

              {/* Status Stats Card */}
              <Card title="Telemetry Counters" subtitle="Metrics for current visual session">
                <div className="space-y-4 mt-2">
                  {selectedAlgo === 'TOKEN_BUCKET' ? (
                    <>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Tokens Remaining</span>
                        <span className="font-bold text-[#F9FAFB] font-number">
                          {tbTokens.toFixed(1)} / {tbCapacity}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Requests Allowed</span>
                        <span className="font-bold text-[#10B981] font-number">{tbAllowed}</span>
                      </div>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Requests Rejected</span>
                        <span className="font-bold text-[#EF4444] font-number">{tbRejected}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Rejection Rate</span>
                        <span className="font-bold text-[#F59E0B] font-number">
                          {formatPercent((tbAllowed + tbRejected) === 0 ? 0 : tbRejected / (tbAllowed + tbRejected))}
                        </span>
                      </div>
                    </>
                  ) : (
                    <>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Requests in Window</span>
                        <span className="font-bold text-[#F9FAFB] font-number">
                          {swRequests.filter(r => r.allowed).length} / {swLimit}
                        </span>
                      </div>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Requests Allowed</span>
                        <span className="font-bold text-[#10B981] font-number">{swAllowed}</span>
                      </div>
                      <div className="flex justify-between border-b border-[#374151]/30 pb-2 text-xs">
                        <span className="text-[#9CA3AF]">Requests Rejected</span>
                        <span className="font-bold text-[#EF4444] font-number">{swRejected}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Rejection Rate</span>
                        <span className="font-bold text-[#F59E0B] font-number">
                          {formatPercent((swAllowed + swRejected) === 0 ? 0 : swRejected / (swAllowed + swRejected))}
                        </span>
                      </div>
                    </>
                  )}
                </div>
              </Card>
            </div>
          </div>
        )}

        {/* TAB 2: COMPARE */}
        {activeSubTab === 'compare' && (
          <div className="space-y-6">
            <Card title="Multi-Threaded Algorithmic Dynamic Compare" subtitle="Fires a concurrent sequence of 15 requests staggered at 50ms intervals to evaluate allowed and dropped actions side-by-side.">
              <div className="absolute top-6 right-6">
                <Button
                  onClick={() => runCompareBurst(15)}
                  loading={compareRunning}
                  className="h-10 px-6 font-semibold"
                >
                  <Flame size={14} className="mr-2" /> Trigger Burst Comparison
                </Button>
              </div>

              {/* Side-by-Side description */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4 mb-6">
                <div className="p-4 bg-[#111827] border border-[#374151] rounded-lg text-xs leading-relaxed text-[#9CA3AF]">
                  <h4 className="font-bold text-[#F9FAFB] mb-1">Token Bucket</h4>
                  Allows immediate consumption of tokens up to the burst limit. The bucket drains rapidly but starts refilling instantly. Refill rate is continuous ($O(1)$ constant lookup).
                </div>
                <div className="p-4 bg-[#111827] border border-[#374151] rounded-lg text-xs leading-relaxed text-[#9CA3AF]">
                  <h4 className="font-bold text-[#F9FAFB] mb-1">Sliding Window</h4>
                  Evaluates requests against an exact rolling sliding window (using ZSET cardinalities). Rejects all requests exceeding the capacity bounds strictly within the sliding frame ($O(N)$ linear space complexity).
                </div>
              </div>

              {/* Logs Table */}
              <div className="overflow-x-auto border border-[#374151] rounded-lg">
                <table className="w-full text-left text-sm text-[#9CA3AF]">
                  <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                    <tr>
                      <th className="p-3">Req ID</th>
                      <th className="p-3">Timestamp</th>
                      <th className="p-3 text-center">Token Bucket Status</th>
                      <th className="p-3 text-center">Sliding Window Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#374151]/50 bg-[#1F2937]/10 font-mono text-xs">
                    {compareLogs.length > 0 ? (
                      compareLogs.map((log) => (
                        <tr key={log.id} className="hover:bg-[#1F2937]/20 transition-all">
                          <td className="p-3">#{log.id}</td>
                          <td className="p-3">{log.time}</td>
                          <td className="p-3 text-center">
                            <Badge variant={log.tbStatus === 'ALLOWED' ? 'SAFE' : 'DANGER'}>
                              {log.tbStatus}
                            </Badge>
                          </td>
                          <td className="p-3 text-center">
                            <Badge variant={log.swStatus === 'ALLOWED' ? 'SAFE' : 'DANGER'}>
                              {log.swStatus}
                            </Badge>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan="4" className="p-8 text-center text-[#6B7280] font-sans">
                          Click "Trigger Burst Comparison" above to fire request sequences.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        )}

        {/* TAB 3: BENCHMARK */}
        {activeSubTab === 'benchmark' && (
          <div className="space-y-6">
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
              {/* Form Config Panel */}
              <div className="lg:col-span-4">
                <Card title="Load Configuration" subtitle="Define stress parameters for concurrent testing">
                  <form onSubmit={handleRunBenchmark} className="space-y-6 mt-4">
                    {/* Total requests slider */}
                    <div className="space-y-2">
                      <div className="flex justify-between text-xs font-semibold">
                        <span className="text-[#9CA3AF]">Total Stress Requests</span>
                        <span className="text-[#3B82F6] font-number">{benchRequests}</span>
                      </div>
                      <input
                        type="range"
                        min="100"
                        max="5000"
                        step="100"
                        value={benchRequests}
                        onChange={(e) => setBenchRequests(parseInt(e.target.value))}
                        className="w-full accent-[#3B82F6]"
                      />
                      <span className="text-[10px] text-[#6B7280] block leading-tight">
                        Total requests divided equally among threads.
                      </span>
                    </div>

                    {/* Concurrency slider */}
                    <div className="space-y-2">
                      <div className="flex justify-between text-xs font-semibold">
                        <span className="text-[#9CA3AF]">Concurrency (Threads)</span>
                        <span className="text-[#3B82F6] font-number">{benchConcurrency}</span>
                      </div>
                      <input
                        type="range"
                        min="1"
                        max="50"
                        step="1"
                        value={benchConcurrency}
                        onChange={(e) => setBenchConcurrency(parseInt(e.target.value))}
                        className="w-full accent-[#3B82F6]"
                      />
                      <span className="text-[10px] text-[#6B7280] block leading-tight">
                        Fires request workloads concurrently.
                      </span>
                    </div>

                    {benchError && (
                      <Alert type="error" message="Benchmark Run Failed">
                        {benchError.message || "Ensure Redis and Spring Boot are active."}
                      </Alert>
                    )}

                    <Button
                      type="submit"
                      className="w-full h-11 font-semibold"
                      loading={benchLoading}
                    >
                      Run Stress Benchmark
                    </Button>
                  </form>
                </Card>
              </div>

              {/* Output Results Panel */}
              <div className="lg:col-span-8">
                {benchLoading ? (
                  <Card className="h-full flex flex-col items-center justify-center py-20 text-center">
                    <Spinner size="lg" className="mb-4" />
                    <h3 className="text-lg font-semibold text-[#F9FAFB]">Generating Concurrent Load Traffic</h3>
                    <p className="text-xs text-[#9CA3AF] max-w-sm mt-1">
                      Spawning {benchConcurrency} worker threads executing {Math.round(benchRequests / benchConcurrency)} requests each against redis rate limiter...
                    </p>
                  </Card>
                ) : benchmarkResult ? (
                  <motion.div
                    initial={{ opacity: 0, y: 15 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="space-y-6"
                  >
                    {/* Winner Badge and suggestion */}
                    <Card className="border-l-4 border-l-[#10B981]">
                      <div className="flex items-start gap-4">
                        <div className="p-3 bg-[#10B981]/15 text-[#10B981] rounded-lg border border-[#10B981]/20">
                          <CheckCircle size={24} />
                        </div>
                        <div>
                          <h4 className="text-base font-bold text-[#F9FAFB] flex items-center gap-2">
                            Benchmark Champion: 
                            <span className="text-transparent bg-clip-text bg-gradient-to-r from-[#10B981] to-[#34D399]">
                              {benchmarkResult.winner?.replace('_', ' ')}
                            </span>
                          </h4>
                          <p className="text-xs text-[#9CA3AF] mt-1 leading-relaxed">
                            {benchmarkResult.recommendation}
                          </p>
                        </div>
                      </div>
                    </Card>

                    {/* Chart Card */}
                    <Card title="Percentile Latency Analysis (ms)" subtitle="Lower values represent faster execution times.">
                      <BarChart
                        data={getChartData()}
                        xAxisKey="name"
                        series={[
                          { key: 'p50', color: '#10B981', name: 'p50 Median' },
                          { key: 'p95', color: '#F59E0B', name: 'p95 Peak' },
                          { key: 'p99', color: '#EF4444', name: 'p99 Extreme' }
                        ]}
                        height={260}
                      />
                    </Card>
                  </motion.div>
                ) : (
                  <div className="h-full flex items-center justify-center p-8 border-2 border-dashed border-[#374151] rounded-xl text-center text-[#6B7280]">
                    <div className="space-y-2">
                      <Activity size={32} className="mx-auto text-[#374151] animate-pulse" />
                      <p className="text-sm font-medium">No benchmark ran yet</p>
                      <p className="text-xs max-w-[240px] mx-auto">
                        Configure thread counts on the left and stress test the cluster algorithms to view percentile speed curves.
                      </p>
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default RateLimiter;
