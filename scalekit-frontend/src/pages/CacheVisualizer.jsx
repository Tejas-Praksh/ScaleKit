import React, { useState, useCallback, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Database, ArrowRight, Trash2, Search, Play, RotateCcw,
  ChevronRight, Layers, Zap, BarChart3, Plus
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Tabs from '../components/ui/Tabs';
import BarChart from '../components/charts/BarChart';
import { formatNumber, formatPercent } from '../utils/formatters';

// ── LRU Cache Engine ─────────────────────────────────────────────────────────
class LRUEngine {
  constructor(capacity = 5) {
    this.capacity = capacity;
    this.cache = new Map();
    this.hits = 0;
    this.misses = 0;
    this.evictions = 0;
    this.ops = 0;
    this.log = [];
  }

  get(key) {
    this.ops++;
    if (this.cache.has(key)) {
      const val = this.cache.get(key);
      this.cache.delete(key);
      this.cache.set(key, val);
      this.hits++;
      this._log('GET', key, 'HIT');
      return { hit: true, value: val };
    }
    this.misses++;
    this._log('GET', key, 'MISS');
    return { hit: false, value: null };
  }

  put(key, value) {
    this.ops++;
    let evicted = null;
    if (this.cache.has(key)) {
      this.cache.delete(key);
      this._log('PUT', key, 'UPDATE');
    } else {
      if (this.cache.size >= this.capacity) {
        const firstKey = this.cache.keys().next().value;
        this.cache.delete(firstKey);
        this.evictions++;
        evicted = firstKey;
        this._log('PUT', key, 'EVICT:' + firstKey);
      } else {
        this._log('PUT', key, 'INSERT');
      }
    }
    this.cache.set(key, value);
    return { evicted };
  }

  entries() {
    return [...this.cache.entries()].reverse(); // MRU first
  }

  _log(op, key, result) {
    this.log.unshift({ op, key, result, time: Date.now(), id: this.ops });
    if (this.log.length > 30) this.log.pop();
  }

  hitRate() {
    return this.ops === 0 ? 0 : this.hits / (this.hits + this.misses);
  }

  reset(cap) {
    this.capacity = cap || this.capacity;
    this.cache.clear();
    this.hits = this.misses = this.evictions = this.ops = 0;
    this.log = [];
  }
}

// ── LFU Cache Engine ─────────────────────────────────────────────────────────
class LFUEngine {
  constructor(capacity = 5) {
    this.capacity = capacity;
    this.cache = new Map(); // key -> { value, freq }
    this.hits = 0;
    this.misses = 0;
    this.evictions = 0;
    this.ops = 0;
    this.log = [];
  }

  get(key) {
    this.ops++;
    if (this.cache.has(key)) {
      const entry = this.cache.get(key);
      entry.freq++;
      this.hits++;
      this._log('GET', key, 'HIT');
      return { hit: true, value: entry.value };
    }
    this.misses++;
    this._log('GET', key, 'MISS');
    return { hit: false, value: null };
  }

  put(key, value) {
    this.ops++;
    let evicted = null;
    if (this.cache.has(key)) {
      const entry = this.cache.get(key);
      entry.value = value;
      entry.freq++;
      this._log('PUT', key, 'UPDATE');
    } else {
      if (this.cache.size >= this.capacity) {
        // Find min frequency key (oldest among ties)
        let minFreq = Infinity;
        let minKey = null;
        for (const [k, v] of this.cache) {
          if (v.freq < minFreq) {
            minFreq = v.freq;
            minKey = k;
          }
        }
        if (minKey !== null) {
          this.cache.delete(minKey);
          this.evictions++;
          evicted = minKey;
          this._log('PUT', key, 'EVICT:' + minKey);
        }
      } else {
        this._log('PUT', key, 'INSERT');
      }
      this.cache.set(key, { value, freq: 1 });
    }
    return { evicted };
  }

  entries() {
    return [...this.cache.entries()]
      .sort((a, b) => b[1].freq - a[1].freq)
      .map(([k, v]) => [k, v.value, v.freq]);
  }

  _log(op, key, result) {
    this.log.unshift({ op, key, result, time: Date.now(), id: this.ops });
    if (this.log.length > 30) this.log.pop();
  }

  hitRate() {
    return this.ops === 0 ? 0 : this.hits / (this.hits + this.misses);
  }

  reset(cap) {
    this.capacity = cap || this.capacity;
    this.cache.clear();
    this.hits = this.misses = this.evictions = this.ops = 0;
    this.log = [];
  }
}

// ── Preset Patterns ──────────────────────────────────────────────────────────
const PRESETS = {
  sequential: { name: 'Sequential', pattern: ['A','B','C','D','E','F','G','H','I','J','A','B','C','D','E'] },
  zipfian: { name: 'Zipfian (Skewed)', pattern: ['A','A','A','B','B','A','C','A','B','A','D','A','B','A','A','E','A','B','C','A'] },
  cyclic: { name: 'Cyclic', pattern: ['A','B','C','D','E','A','B','C','D','E','A','B','C','D','E'] },
  random: { name: 'Random', pattern: Array.from({length:20}, () => String.fromCharCode(65 + Math.floor(Math.random()*10))) },
};

const QUICK_KEYS = ['A', 'B', 'C', 'D', 'E', 'F'];

const CacheVisualizer = () => {
  const [activeTab, setActiveTab] = useState('lru');
  const [capacity, setCapacity] = useState(5);
  const [getKey, setGetKey] = useState('');
  const [putKey, setPutKey] = useState('');
  const [putValue, setPutValue] = useState('');
  const [lastResult, setLastResult] = useState(null);

  // Engines (use refs to persist across renders)
  const lruRef = useRef(new LRUEngine(5));
  const lfuRef = useRef(new LFUEngine(5));
  const [, forceRender] = useState(0);
  const rerender = () => forceRender(n => n + 1);

  // Comparison state
  const [comparePattern, setComparePattern] = useState(null);
  const [compareRunning, setCompareRunning] = useState(false);
  const [compareStep, setCompareStep] = useState(0);
  const compareLruRef = useRef(new LRUEngine(5));
  const compareLfuRef = useRef(new LFUEngine(5));

  const engine = activeTab === 'lru' ? lruRef.current : lfuRef.current;

  // ── Actions ────────────────────────────────────────────────────────────────
  const handleGet = useCallback((key) => {
    const k = key || getKey.trim();
    if (!k) return;
    const result = engine.get(k);
    setLastResult({ type: 'GET', key: k, hit: result.hit });
    setGetKey('');
    rerender();
  }, [getKey, engine]);

  const handlePut = useCallback((key, value) => {
    const k = key || putKey.trim();
    const v = value || putValue.trim() || `v${engine.ops + 1}`;
    if (!k) return;
    const result = engine.put(k, v);
    setLastResult({ type: 'PUT', key: k, evicted: result.evicted });
    setPutKey('');
    setPutValue('');
    rerender();
  }, [putKey, putValue, engine]);

  const handleReset = useCallback(() => {
    engine.reset(capacity);
    setLastResult(null);
    rerender();
  }, [engine, capacity]);

  useEffect(() => {
    lruRef.current.capacity = capacity;
    lfuRef.current.capacity = capacity;
  }, [capacity]);

  // ── Compare ────────────────────────────────────────────────────────────────
  const runCompare = useCallback(async (presetKey) => {
    const preset = PRESETS[presetKey];
    if (!preset || compareRunning) return;
    setCompareRunning(true);
    setComparePattern(preset);
    setCompareStep(0);

    compareLruRef.current = new LRUEngine(capacity);
    compareLfuRef.current = new LFUEngine(capacity);

    for (let i = 0; i < preset.pattern.length; i++) {
      const k = preset.pattern[i];
      // PUT if not in cache, GET if in cache
      compareLruRef.current.get(k) || compareLruRef.current.put(k, `v${k}`);
      // Reset and redo: actually just do GET for all to compare hit behavior
      compareLruRef.current = compareLruRef.current; // keep ref
      compareLfuRef.current = compareLfuRef.current;
    }

    // Actually run step by step
    compareLruRef.current = new LRUEngine(capacity);
    compareLfuRef.current = new LFUEngine(capacity);

    for (let i = 0; i < preset.pattern.length; i++) {
      const k = preset.pattern[i];
      // First PUT to populate, then subsequent accesses are GETs
      const lruHas = compareLruRef.current.cache.has(k);
      const lfuHas = compareLfuRef.current.cache.has(k);

      if (!lruHas) compareLruRef.current.put(k, `v${k}`);
      else compareLruRef.current.get(k);

      if (!lfuHas) compareLfuRef.current.put(k, `v${k}`);
      else compareLfuRef.current.get(k);

      setCompareStep(i + 1);
      rerender();
      await new Promise(r => setTimeout(r, 150));
    }

    setCompareRunning(false);
  }, [capacity, compareRunning]);

  // ── Result Badge helper ────────────────────────────────────────────────────
  const resultBadge = (result) => {
    if (result.startsWith('HIT')) return <Badge variant="SAFE">HIT</Badge>;
    if (result.startsWith('MISS')) return <Badge variant="DANGER">MISS</Badge>;
    if (result.startsWith('EVICT')) return <Badge variant="WARNING">EVICT {result.split(':')[1]}</Badge>;
    if (result === 'INSERT') return <Badge variant="INFO">INSERT</Badge>;
    if (result === 'UPDATE') return <Badge variant="CYAN">UPDATE</Badge>;
    return <Badge variant="MUTED">{result}</Badge>;
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-[#F9FAFB]">Cache Strategy Visualizer</h2>
          <p className="text-sm text-[#9CA3AF]">Interactive LRU and LFU cache simulation with eviction visualization.</p>
        </div>
        <Badge variant="INFO">Interactive</Badge>
      </div>

      <Tabs
        tabs={[
          { id: 'lru', label: 'LRU Simulator' },
          { id: 'lfu', label: 'LFU Simulator' },
          { id: 'compare', label: 'Strategy Comparison' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      <div className="min-h-[400px]">
        {/* ── TAB 1 & 2: LRU / LFU ───────────────────────────────────────────── */}
        {(activeTab === 'lru' || activeTab === 'lfu') && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Left - Controls */}
            <div className="lg:col-span-4 space-y-4">
              {/* Stats */}
              <div className="grid grid-cols-2 gap-3">
                <StatCard label="Hits" value={engine.hits} color="green" icon={Zap} />
                <StatCard label="Misses" value={engine.misses} color="red" icon={Search} />
              </div>

              <Card>
                <div className="space-y-3">
                  <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                    <span className="text-[#9CA3AF]">Hit Rate</span>
                    <span className="font-bold font-mono text-[#10B981]">{formatPercent(engine.hitRate())}</span>
                  </div>
                  <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                    <span className="text-[#9CA3AF]">Evictions</span>
                    <span className="font-bold font-mono text-[#F59E0B]">{engine.evictions}</span>
                  </div>
                  <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                    <span className="text-[#9CA3AF]">Total Ops</span>
                    <span className="font-bold font-mono text-[#F9FAFB]">{engine.ops}</span>
                  </div>
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Size / Capacity</span>
                    <span className="font-bold font-mono text-[#F9FAFB]">{engine.cache.size} / {capacity}</span>
                  </div>
                </div>
              </Card>

              {/* Capacity */}
              <Card title="Cache Configuration">
                <div className="mt-2 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Capacity</span>
                    <span className="font-bold text-[#3B82F6] font-mono">{capacity}</span>
                  </div>
                  <input
                    type="range" min="2" max="20" step="1"
                    value={capacity}
                    onChange={(e) => setCapacity(parseInt(e.target.value))}
                    className="w-full accent-[#3B82F6]"
                  />
                </div>

                {/* GET */}
                <div className="mt-4 space-y-2">
                  <label className="text-xs text-[#9CA3AF] font-medium">Access Key (GET)</label>
                  <div className="flex gap-2">
                    <Input
                      value={getKey}
                      onChange={(e) => setGetKey(e.target.value)}
                      placeholder="key"
                      onKeyDown={(e) => e.key === 'Enter' && handleGet()}
                    />
                    <Button variant="outline" onClick={() => handleGet()} className="shrink-0">
                      <Search size={14} />
                    </Button>
                  </div>
                </div>

                {/* PUT */}
                <div className="mt-3 space-y-2">
                  <label className="text-xs text-[#9CA3AF] font-medium">Insert (PUT)</label>
                  <div className="flex gap-2">
                    <Input
                      value={putKey}
                      onChange={(e) => setPutKey(e.target.value)}
                      placeholder="key"
                    />
                    <Input
                      value={putValue}
                      onChange={(e) => setPutValue(e.target.value)}
                      placeholder="val"
                    />
                    <Button onClick={() => handlePut()} className="shrink-0">
                      <Plus size={14} />
                    </Button>
                  </div>
                </div>

                {/* Quick Keys */}
                <div className="mt-3">
                  <label className="text-xs text-[#9CA3AF] font-medium block mb-2">Quick Access</label>
                  <div className="flex gap-1.5 flex-wrap">
                    {QUICK_KEYS.map(k => (
                      <button
                        key={k}
                        onClick={() => {
                          if (engine.cache.has(k)) handleGet(k);
                          else handlePut(k, `v${k}`);
                        }}
                        className="w-8 h-8 rounded border border-[#374151] text-xs font-bold text-[#9CA3AF] hover:text-[#F9FAFB] hover:border-[#3B82F6] transition-all font-mono"
                      >
                        {k}
                      </button>
                    ))}
                  </div>
                </div>

                <Button variant="ghost" onClick={handleReset} className="w-full mt-4 text-xs">
                  <RotateCcw size={12} className="mr-2" /> Reset Cache
                </Button>
              </Card>
            </div>

            {/* Right - Visualization */}
            <div className="lg:col-span-8 space-y-4">
              {/* Last Result Banner */}
              {lastResult && (
                <motion.div
                  initial={{ opacity: 0, y: -8 }}
                  animate={{ opacity: 1, y: 0 }}
                  className={`p-3 rounded-lg border text-sm flex items-center gap-3 ${
                    lastResult.hit
                      ? 'bg-[#10B981]/10 border-[#10B981]/30 text-[#10B981]'
                      : lastResult.evicted
                        ? 'bg-[#F59E0B]/10 border-[#F59E0B]/30 text-[#F59E0B]'
                        : 'bg-[#EF4444]/10 border-[#EF4444]/30 text-[#EF4444]'
                  }`}
                >
                  <span className="font-mono font-bold">{lastResult.type}</span>
                  <span className="font-mono">"{lastResult.key}"</span>
                  <span className="text-xs">→</span>
                  {lastResult.hit !== undefined && (
                    <Badge variant={lastResult.hit ? 'SAFE' : 'DANGER'}>
                      {lastResult.hit ? 'CACHE HIT' : 'CACHE MISS'}
                    </Badge>
                  )}
                  {lastResult.evicted && (
                    <Badge variant="WARNING">EVICTED: {lastResult.evicted}</Badge>
                  )}
                </motion.div>
              )}

              {/* Cache Visual - Linked List */}
              <Card title={activeTab === 'lru' ? 'LRU Linked List (MRU → LRU)' : 'LFU Frequency Buckets (High → Low)'}>
                <div className="mt-3 flex items-center gap-1 overflow-x-auto pb-2">
                  {activeTab === 'lru' ? (
                    <>
                      {lruRef.current.entries().length === 0 ? (
                        <div className="w-full py-8 text-center text-[#6B7280] text-sm">Cache is empty. Add items using the controls.</div>
                      ) : (
                        <AnimatePresence>
                          {lruRef.current.entries().map(([key, val], idx) => (
                            <React.Fragment key={key}>
                              {idx > 0 && (
                                <ChevronRight size={14} className="text-[#4B5563] shrink-0" />
                              )}
                              <motion.div
                                layout
                                initial={{ scale: 0.8, opacity: 0 }}
                                animate={{ scale: 1, opacity: 1 }}
                                exit={{ scale: 0.8, opacity: 0, backgroundColor: '#EF4444' }}
                                transition={{ duration: 0.15 }}
                                className="shrink-0 border border-[#374151] rounded-lg p-3 bg-[#111827] min-w-[80px] text-center"
                              >
                                <div className="text-xs font-bold text-[#F9FAFB] font-mono">{key}</div>
                                <div className="text-[10px] text-[#6B7280] mt-0.5">{val}</div>
                                {idx === 0 && (
                                  <div className="mt-1"><Badge variant="INFO" className="text-[8px]">MRU</Badge></div>
                                )}
                                {idx === lruRef.current.entries().length - 1 && (
                                  <div className="mt-1"><Badge variant="DANGER" className="text-[8px]">LRU</Badge></div>
                                )}
                              </motion.div>
                            </React.Fragment>
                          ))}
                        </AnimatePresence>
                      )}
                    </>
                  ) : (
                    <>
                      {lfuRef.current.entries().length === 0 ? (
                        <div className="w-full py-8 text-center text-[#6B7280] text-sm">Cache is empty. Add items using the controls.</div>
                      ) : (
                        <AnimatePresence>
                          {lfuRef.current.entries().map(([key, val, freq], idx) => (
                            <React.Fragment key={key}>
                              {idx > 0 && (
                                <ChevronRight size={14} className="text-[#4B5563] shrink-0" />
                              )}
                              <motion.div
                                layout
                                initial={{ scale: 0.8, opacity: 0 }}
                                animate={{ scale: 1, opacity: 1 }}
                                exit={{ scale: 0.8, opacity: 0 }}
                                transition={{ duration: 0.15 }}
                                className="shrink-0 border border-[#374151] rounded-lg p-3 bg-[#111827] min-w-[80px] text-center"
                              >
                                <div className="text-xs font-bold text-[#F9FAFB] font-mono">{key}</div>
                                <div className="text-[10px] text-[#6B7280] mt-0.5">{val}</div>
                                <div className="mt-1">
                                  <Badge variant="PURPLE" className="text-[8px]">freq: {freq}</Badge>
                                </div>
                              </motion.div>
                            </React.Fragment>
                          ))}
                        </AnimatePresence>
                      )}
                    </>
                  )}
                </div>
              </Card>

              {/* Operation Log */}
              <Card title="Operation Log" subtitle="Last 20 operations">
                <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2 max-h-[280px] overflow-y-auto">
                  <table className="w-full text-left text-xs text-[#9CA3AF]">
                    <thead className="bg-[#111827] text-[10px] uppercase text-[#F9FAFB] border-b border-[#374151] sticky top-0">
                      <tr>
                        <th className="p-2">Op#</th>
                        <th className="p-2">Type</th>
                        <th className="p-2">Key</th>
                        <th className="p-2 text-center">Result</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[#374151]/50 font-mono">
                      {engine.log.length > 0 ? (
                        engine.log.slice(0, 20).map((entry) => (
                          <tr key={entry.id} className="hover:bg-[#1F2937]/30">
                            <td className="p-2 text-[#6B7280]">#{entry.id}</td>
                            <td className="p-2 text-[#F9FAFB]">{entry.op}</td>
                            <td className="p-2">{entry.key}</td>
                            <td className="p-2 text-center">{resultBadge(entry.result)}</td>
                          </tr>
                        ))
                      ) : (
                        <tr><td colSpan="4" className="p-6 text-center text-[#6B7280] font-sans">No operations yet.</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </Card>
            </div>
          </div>
        )}

        {/* ── TAB 3: COMPARISON ───────────────────────────────────────────────── */}
        {activeTab === 'compare' && (
          <div className="space-y-6">
            <Card title="Access Pattern Selector" subtitle="Run the same pattern on both LRU and LFU to compare hit rates">
              <div className="flex flex-wrap gap-3 mt-3">
                {Object.entries(PRESETS).map(([key, preset]) => (
                  <Button
                    key={key}
                    variant="outline"
                    onClick={() => runCompare(key)}
                    loading={compareRunning}
                    disabled={compareRunning}
                  >
                    <Play size={12} className="mr-2" /> {preset.name}
                  </Button>
                ))}
              </div>
              {comparePattern && (
                <div className="mt-3 flex flex-wrap gap-1">
                  {comparePattern.pattern.map((k, i) => (
                    <span
                      key={i}
                      className={`inline-block px-1.5 py-0.5 text-[10px] font-mono rounded border transition-all ${
                        i < compareStep
                          ? 'border-[#3B82F6]/50 bg-[#3B82F6]/10 text-[#3B82F6]'
                          : 'border-[#374151] text-[#6B7280]'
                      }`}
                    >
                      {k}
                    </span>
                  ))}
                </div>
              )}
            </Card>

            {comparePattern && !compareRunning && (
              <>
                {/* Results Comparison */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  <Card title="LRU Results" className="border-l-4 border-l-[#3B82F6]">
                    <div className="space-y-3 mt-2">
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Total Hits</span>
                        <span className="font-bold font-mono text-[#10B981]">{compareLruRef.current.hits}</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Total Misses</span>
                        <span className="font-bold font-mono text-[#EF4444]">{compareLruRef.current.misses}</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Hit Rate</span>
                        <span className="font-bold font-mono text-[#3B82F6]">{formatPercent(compareLruRef.current.hitRate())}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Evictions</span>
                        <span className="font-bold font-mono text-[#F59E0B]">{compareLruRef.current.evictions}</span>
                      </div>
                    </div>
                  </Card>

                  <Card title="LFU Results" className="border-l-4 border-l-[#8B5CF6]">
                    <div className="space-y-3 mt-2">
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Total Hits</span>
                        <span className="font-bold font-mono text-[#10B981]">{compareLfuRef.current.hits}</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Total Misses</span>
                        <span className="font-bold font-mono text-[#EF4444]">{compareLfuRef.current.misses}</span>
                      </div>
                      <div className="flex justify-between text-xs border-b border-[#374151]/30 pb-2">
                        <span className="text-[#9CA3AF]">Hit Rate</span>
                        <span className="font-bold font-mono text-[#8B5CF6]">{formatPercent(compareLfuRef.current.hitRate())}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Evictions</span>
                        <span className="font-bold font-mono text-[#F59E0B]">{compareLfuRef.current.evictions}</span>
                      </div>
                    </div>
                  </Card>
                </div>

                {/* Chart */}
                <Card title="Hit Rate Comparison">
                  <BarChart
                    data={[
                      { name: 'LRU', hitRate: +(compareLruRef.current.hitRate() * 100).toFixed(1), evictions: compareLruRef.current.evictions },
                      { name: 'LFU', hitRate: +(compareLfuRef.current.hitRate() * 100).toFixed(1), evictions: compareLfuRef.current.evictions }
                    ]}
                    xAxisKey="name"
                    series={[
                      { key: 'hitRate', color: '#10B981', name: 'Hit Rate %' },
                      { key: 'evictions', color: '#F59E0B', name: 'Evictions' }
                    ]}
                    height={220}
                  />
                </Card>
              </>
            )}

            {!comparePattern && (
              <div className="flex flex-col items-center justify-center py-16 text-center text-[#6B7280]">
                <Layers size={32} className="mb-3 text-[#374151]" />
                <p className="text-sm font-medium">Select an access pattern above</p>
                <p className="text-xs mt-1 max-w-xs">Run a preset pattern to see how LRU and LFU behave differently under the same workload.</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};



export default CacheVisualizer;
