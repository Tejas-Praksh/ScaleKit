import React, { useState, useCallback, useMemo } from 'react';
import { motion } from 'framer-motion';
import {
  Sparkles, Plus, Search, Play, RotateCcw,
  Hash, Percent, Grid3X3, FlaskConical
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Tabs from '../components/ui/Tabs';
import BarChart from '../components/charts/BarChart';
import { formatNumber, formatPercent } from '../utils/formatters';

// ── Hash Functions (double hashing for independence) ─────────────────────────
function bloomHash(str, seed) {
  let h = seed;
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) - h + str.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

function getHashIndices(element, k, m) {
  const indices = [];
  for (let i = 0; i < k; i++) {
    const idx = bloomHash(element, i * 31 + 7) % m;
    indices.push(idx);
  }
  return indices;
}

// Theoretical FPR: (1 - e^(-kn/m))^k
function theoreticalFPR(k, n, m) {
  if (n === 0 || m === 0) return 0;
  return Math.pow(1 - Math.exp(-k * n / m), k);
}

// Optimal k for given n,m
function optimalK(n, m) {
  if (n === 0) return 1;
  return Math.max(1, Math.round((m / n) * Math.LN2));
}

const QUICK_ITEMS = ['apple', 'banana', 'cherry', 'date', 'elderberry'];
const HASH_COLORS = ['#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6', '#06B6D4', '#EC4899'];

const BloomFilter = () => {
  const [activeTab, setActiveTab] = useState('bitarray');
  const [m, setM] = useState(64);   // bit array size
  const [k, setK] = useState(3);    // hash function count
  const [bits, setBits] = useState(() => new Array(64).fill(0));
  const [elements, setElements] = useState([]);
  const [addInput, setAddInput] = useState('');
  const [checkInput, setCheckInput] = useState('');
  const [checkResult, setCheckResult] = useState(null);
  const [highlightBits, setHighlightBits] = useState([]); // indices to highlight
  const [highlightType, setHighlightType] = useState(null); // 'add' | 'check-found' | 'check-miss'
  const [hashInput, setHashInput] = useState('');

  // FPR test state
  const [fprN, setFprN] = useState(50);
  const [fprM, setFprM] = useState(256);
  const [fprK, setFprK] = useState(3);
  const [fprResult, setFprResult] = useState(null);
  const [fprRunning, setFprRunning] = useState(false);

  // Stats
  const bitsSet = useMemo(() => bits.filter(b => b === 1).length, [bits]);
  const fillRatio = m > 0 ? bitsSet / m : 0;
  const theoFPR = theoreticalFPR(k, elements.length, m);

  // ── Add Element ────────────────────────────────────────────────────────────
  const handleAdd = useCallback((item) => {
    const el = item || addInput.trim();
    if (!el) return;

    const indices = getHashIndices(el, k, m);
    const newBits = [...bits];
    indices.forEach(idx => { newBits[idx] = 1; });
    setBits(newBits);
    setElements(prev => prev.includes(el) ? prev : [...prev, el]);
    setHighlightBits(indices);
    setHighlightType('add');
    setAddInput('');
    setCheckResult(null);

    // Clear highlight after animation
    setTimeout(() => {
      setHighlightBits([]);
      setHighlightType(null);
    }, 1200);
  }, [addInput, k, m, bits]);

  // ── Check Element ──────────────────────────────────────────────────────────
  const handleCheck = useCallback((item) => {
    const el = item || checkInput.trim();
    if (!el) return;

    const indices = getHashIndices(el, k, m);
    const allSet = indices.every(idx => bits[idx] === 1);

    setCheckResult({
      element: el,
      exists: allSet,
      indices,
      actuallyAdded: elements.includes(el)
    });
    setHighlightBits(indices);
    setHighlightType(allSet ? 'check-found' : 'check-miss');
    setCheckInput('');

    setTimeout(() => {
      setHighlightBits([]);
      setHighlightType(null);
    }, 2000);
  }, [checkInput, k, m, bits, elements]);

  // ── Reset ──────────────────────────────────────────────────────────────────
  const handleReset = useCallback((newM) => {
    const size = newM || m;
    setBits(new Array(size).fill(0));
    setElements([]);
    setCheckResult(null);
    setHighlightBits([]);
    setHighlightType(null);
  }, [m]);

  // ── Resize handlers ────────────────────────────────────────────────────────
  const handleMChange = (newM) => {
    setM(newM);
    handleReset(newM);
  };

  // ── FPR Test ───────────────────────────────────────────────────────────────
  const runFprTest = useCallback(async () => {
    if (fprRunning) return;
    setFprRunning(true);

    const testBits = new Array(fprM).fill(0);
    const insertedSet = new Set();

    // Insert N random elements
    for (let i = 0; i < fprN; i++) {
      const el = `test-element-${i}-${Math.random().toString(36).slice(2)}`;
      insertedSet.add(el);
      const indices = getHashIndices(el, fprK, fprM);
      indices.forEach(idx => { testBits[idx] = 1; });
    }

    // Check M non-existing elements
    const checksCount = fprN * 2;
    let falsePositives = 0;
    for (let i = 0; i < checksCount; i++) {
      const el = `nonexistent-${i}-${Math.random().toString(36).slice(2)}`;
      if (insertedSet.has(el)) continue;
      const indices = getHashIndices(el, fprK, fprM);
      if (indices.every(idx => testBits[idx] === 1)) {
        falsePositives++;
      }
    }

    const actualFPR = checksCount > 0 ? falsePositives / checksCount : 0;
    const expectedFPR = theoreticalFPR(fprK, fprN, fprM);

    setFprResult({
      inserted: fprN,
      checked: checksCount,
      falsePositives,
      actualFPR,
      expectedFPR,
      optK: optimalK(fprN, fprM),
      fillRatio: testBits.filter(b => b === 1).length / fprM
    });
    setFprRunning(false);
  }, [fprN, fprM, fprK, fprRunning]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-[#F9FAFB]">Probabilistic Bloom Filter</h2>
          <p className="text-sm text-[#9CA3AF]">Interactive bit array visualization with hash function mapping and false positive analysis.</p>
        </div>
        <Badge variant="PURPLE">Probabilistic</Badge>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        <StatCard label="Elements" value={elements.length} color="blue" icon={Plus} />
        <StatCard label="Bits Set" value={`${bitsSet} / ${m}`} color="green" icon={Grid3X3} />
        <StatCard label="Fill Ratio" value={formatPercent(fillRatio)} color={fillRatio > 0.5 ? 'amber' : 'cyan'} icon={Percent} />
        <StatCard label="Theo. FPR" value={theoFPR < 0.001 ? '<0.1%' : formatPercent(theoFPR)} color={theoFPR > 0.1 ? 'red' : 'green'} icon={Sparkles} />
        <StatCard label="Optimal k" value={optimalK(elements.length || 1, m)} color="purple" icon={Hash} />
      </div>

      <Tabs
        tabs={[
          { id: 'bitarray', label: 'Bit Array' },
          { id: 'hashes', label: 'Hash Functions' },
          { id: 'fpr', label: 'False Positive Analysis' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      <div className="min-h-[400px]">
        {/* ── TAB 1: BIT ARRAY ────────────────────────────────────────────────── */}
        {activeTab === 'bitarray' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Left - Controls */}
            <div className="lg:col-span-4 space-y-4">
              <Card title="Filter Configuration">
                {/* Array Size */}
                <div className="mt-2 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Array Size (m)</span>
                    <span className="font-bold text-[#3B82F6] font-mono">{m}</span>
                  </div>
                  <input
                    type="range" min="16" max="256" step="8"
                    value={m}
                    onChange={(e) => handleMChange(parseInt(e.target.value))}
                    className="w-full accent-[#3B82F6]"
                  />
                </div>
                {/* Hash Functions */}
                <div className="mt-3 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Hash Functions (k)</span>
                    <span className="font-bold text-[#8B5CF6] font-mono">{k}</span>
                  </div>
                  <input
                    type="range" min="1" max="7" step="1"
                    value={k}
                    onChange={(e) => { setK(parseInt(e.target.value)); handleReset(); }}
                    className="w-full accent-[#8B5CF6]"
                  />
                </div>

                <Button variant="ghost" onClick={() => handleReset()} className="w-full mt-3 text-xs">
                  <RotateCcw size={12} className="mr-2" /> Reset Filter
                </Button>
              </Card>

              {/* Add Element */}
              <Card title="Add Element">
                <div className="flex gap-2 mt-2">
                  <Input
                    value={addInput}
                    onChange={(e) => setAddInput(e.target.value)}
                    placeholder="element name"
                    onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
                  />
                  <Button onClick={() => handleAdd()} className="shrink-0">
                    <Plus size={14} />
                  </Button>
                </div>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {QUICK_ITEMS.map(item => (
                    <button
                      key={item}
                      onClick={() => handleAdd(item)}
                      className={`text-[10px] px-2 py-1 rounded border transition-all ${
                        elements.includes(item)
                          ? 'border-[#10B981]/50 bg-[#10B981]/10 text-[#10B981]'
                          : 'border-[#374151] text-[#9CA3AF] hover:border-[#4B5563]'
                      }`}
                    >
                      {elements.includes(item) ? '✓ ' : ''}{item}
                    </button>
                  ))}
                </div>
              </Card>

              {/* Check Membership */}
              <Card title="Check Membership">
                <div className="flex gap-2 mt-2">
                  <Input
                    value={checkInput}
                    onChange={(e) => setCheckInput(e.target.value)}
                    placeholder="query element"
                    onKeyDown={(e) => e.key === 'Enter' && handleCheck()}
                  />
                  <Button variant="secondary" onClick={() => handleCheck()} className="shrink-0">
                    <Search size={14} />
                  </Button>
                </div>
                {checkResult && (
                  <motion.div
                    initial={{ opacity: 0, y: -4 }}
                    animate={{ opacity: 1, y: 0 }}
                    className={`mt-3 p-3 rounded-lg border text-xs ${
                      checkResult.exists
                        ? 'bg-[#F59E0B]/10 border-[#F59E0B]/30'
                        : 'bg-[#10B981]/10 border-[#10B981]/30'
                    }`}
                  >
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-mono font-bold text-[#F9FAFB]">"{checkResult.element}"</span>
                      <Badge variant={checkResult.exists ? 'WARNING' : 'SAFE'}>
                        {checkResult.exists ? 'PROBABLY IN SET' : 'DEFINITELY NOT IN SET'}
                      </Badge>
                    </div>
                    <div className="text-[#9CA3AF]">
                      Bits checked: [{checkResult.indices.join(', ')}]
                      {checkResult.exists && !checkResult.actuallyAdded && (
                        <span className="ml-2 text-[#EF4444] font-bold">⚠ FALSE POSITIVE</span>
                      )}
                    </div>
                  </motion.div>
                )}
              </Card>
            </div>

            {/* Right - Bit Array Grid */}
            <div className="lg:col-span-8">
              <Card title="Bit Array Visualization" subtitle={`${m} bits, ${bitsSet} set`}>
                <div className="mt-3 flex flex-wrap gap-1">
                  {bits.map((bit, idx) => {
                    const isHighlighted = highlightBits.includes(idx);
                    let bgColor = bit === 1 ? 'bg-[#3B82F6]' : 'bg-[#1F2937]';
                    let borderColor = 'border-[#374151]';

                    if (isHighlighted) {
                      if (highlightType === 'add') {
                        bgColor = 'bg-[#10B981]';
                        borderColor = 'border-[#10B981]';
                      } else if (highlightType === 'check-found') {
                        bgColor = bit === 1 ? 'bg-[#F59E0B]' : 'bg-[#EF4444]';
                        borderColor = bit === 1 ? 'border-[#F59E0B]' : 'border-[#EF4444]';
                      } else if (highlightType === 'check-miss') {
                        bgColor = bit === 1 ? 'bg-[#10B981]' : 'bg-[#EF4444]';
                        borderColor = bit === 1 ? 'border-[#10B981]' : 'border-[#EF4444]';
                      }
                    }

                    return (
                      <motion.div
                        key={idx}
                        animate={isHighlighted ? { scale: [1, 1.3, 1] } : {}}
                        transition={{ duration: 0.3 }}
                        className={`w-5 h-5 rounded-sm border ${bgColor} ${borderColor} flex items-center justify-center cursor-default transition-all duration-150`}
                        title={`Bit ${idx}: ${bit}`}
                      >
                        <span className="text-[7px] font-mono text-white/60">{bit}</span>
                      </motion.div>
                    );
                  })}
                </div>

                {/* Elements list */}
                <div className="mt-4 border-t border-[#374151] pt-3">
                  <span className="text-xs text-[#9CA3AF] font-medium">Added Elements:</span>
                  <div className="flex flex-wrap gap-1.5 mt-2">
                    {elements.length > 0 ? elements.map(el => (
                      <span key={el} className="text-[10px] px-2 py-0.5 rounded bg-[#3B82F6]/10 text-[#3B82F6] border border-[#3B82F6]/30 font-mono">
                        {el}
                      </span>
                    )) : (
                      <span className="text-xs text-[#6B7280]">No elements added yet.</span>
                    )}
                  </div>
                </div>
              </Card>
            </div>
          </div>
        )}

        {/* ── TAB 2: HASH FUNCTIONS ───────────────────────────────────────────── */}
        {activeTab === 'hashes' && (
          <div className="space-y-6">
            <Card title="Hash Function Inspector" subtitle="See which bits each hash function maps to for a given element">
              <div className="flex gap-2 mt-3">
                <Input
                  value={hashInput}
                  onChange={(e) => setHashInput(e.target.value)}
                  placeholder="element to hash"
                />
                <Button variant="outline" onClick={() => setHashInput(hashInput)} className="shrink-0">
                  <Hash size={14} className="mr-1" /> Hash
                </Button>
              </div>

              {hashInput.trim() && (
                <div className="mt-4">
                  <div className="overflow-x-auto border border-[#374151] rounded-lg">
                    <table className="w-full text-left text-sm text-[#9CA3AF]">
                      <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                        <tr>
                          <th className="p-3">Hash #</th>
                          <th className="p-3">Raw Hash</th>
                          <th className="p-3">Bit Index (mod {m})</th>
                          <th className="p-3 text-center">Bit State</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-[#374151]/50 font-mono text-xs">
                        {Array.from({ length: k }, (_, i) => {
                          const raw = bloomHash(hashInput.trim(), i * 31 + 7);
                          const idx = raw % m;
                          return (
                            <tr key={i} className="hover:bg-[#1F2937]/30">
                              <td className="p-3">
                                <span className="inline-flex items-center gap-2">
                                  <span className="w-3 h-3 rounded-full" style={{ backgroundColor: HASH_COLORS[i % HASH_COLORS.length] }} />
                                  h{i}
                                </span>
                              </td>
                              <td className="p-3 text-[#6B7280]">0x{raw.toString(16).padStart(8, '0')}</td>
                              <td className="p-3 text-[#F9FAFB] font-bold">{idx}</td>
                              <td className="p-3 text-center">
                                <Badge variant={bits[idx] === 1 ? 'SAFE' : 'MUTED'}>
                                  {bits[idx]}
                                </Badge>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  {/* Colored bit grid */}
                  <div className="mt-4">
                    <span className="text-xs text-[#9CA3AF] font-medium block mb-2">Bit mapping overlay</span>
                    <div className="flex flex-wrap gap-1">
                      {bits.map((bit, idx) => {
                        const hashIdx = Array.from({ length: k }, (_, i) => i).find(
                          i => bloomHash(hashInput.trim(), i * 31 + 7) % m === idx
                        );
                        const isTarget = hashIdx !== undefined;
                        return (
                          <div
                            key={idx}
                            className={`w-5 h-5 rounded-sm border flex items-center justify-center text-[7px] font-mono ${
                              isTarget
                                ? 'border-transparent'
                                : bit === 1 ? 'bg-[#3B82F6]/30 border-[#374151]' : 'bg-[#1F2937] border-[#374151]'
                            }`}
                            style={isTarget ? { backgroundColor: HASH_COLORS[hashIdx % HASH_COLORS.length], borderColor: HASH_COLORS[hashIdx % HASH_COLORS.length] } : {}}
                          >
                            {isTarget ? `h${hashIdx}` : bit}
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {/* Legend */}
                  <div className="mt-3 flex flex-wrap gap-3">
                    {Array.from({ length: k }, (_, i) => (
                      <span key={i} className="flex items-center gap-1.5 text-[10px] text-[#9CA3AF]">
                        <span className="w-3 h-3 rounded-full" style={{ backgroundColor: HASH_COLORS[i % HASH_COLORS.length] }} />
                        Hash Function {i}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </Card>
          </div>
        )}

        {/* ── TAB 3: FALSE POSITIVE ANALYSIS ──────────────────────────────────── */}
        {activeTab === 'fpr' && (
          <div className="space-y-6">
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              {/* Calculator */}
              <div className="lg:col-span-5">
                <Card title="FPR Calculator" subtitle="Estimate false positive rate">
                  <div className="mt-3 space-y-4">
                    <div className="space-y-1">
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Elements (n)</span>
                        <span className="font-bold text-[#3B82F6] font-mono">{fprN}</span>
                      </div>
                      <input type="range" min="1" max="500" step="1" value={fprN} onChange={e => setFprN(+e.target.value)} className="w-full accent-[#3B82F6]" />
                    </div>
                    <div className="space-y-1">
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Bit Array Size (m)</span>
                        <span className="font-bold text-[#10B981] font-mono">{fprM}</span>
                      </div>
                      <input type="range" min="16" max="2048" step="16" value={fprM} onChange={e => setFprM(+e.target.value)} className="w-full accent-[#10B981]" />
                    </div>
                    <div className="space-y-1">
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Hash Functions (k)</span>
                        <span className="font-bold text-[#8B5CF6] font-mono">{fprK}</span>
                      </div>
                      <input type="range" min="1" max="15" step="1" value={fprK} onChange={e => setFprK(+e.target.value)} className="w-full accent-[#8B5CF6]" />
                    </div>

                    {/* Calculated results */}
                    <div className="space-y-2 pt-3 border-t border-[#374151]">
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Theoretical FPR</span>
                        <span className="font-bold font-mono text-[#F9FAFB]">{(theoreticalFPR(fprK, fprN, fprM) * 100).toFixed(4)}%</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Optimal k for this n,m</span>
                        <span className="font-bold font-mono text-[#8B5CF6]">{optimalK(fprN, fprM)}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-[#9CA3AF]">Bits per element (m/n)</span>
                        <span className="font-bold font-mono text-[#6B7280]">{(fprM / fprN).toFixed(1)}</span>
                      </div>
                    </div>

                    <Button onClick={runFprTest} loading={fprRunning} className="w-full mt-2">
                      <FlaskConical size={14} className="mr-2" /> Run FPR Test
                    </Button>
                  </div>
                </Card>
              </div>

              {/* Results */}
              <div className="lg:col-span-7">
                {fprResult ? (
                  <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="space-y-4">
                    <div className="grid grid-cols-2 gap-3">
                      <StatCard label="Elements Inserted" value={fprResult.inserted} color="blue" icon={Plus} />
                      <StatCard label="Non-Members Checked" value={fprResult.checked} color="purple" icon={Search} />
                    </div>

                    <Card title="FPR Test Results">
                      <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                        <table className="w-full text-left text-sm text-[#9CA3AF]">
                          <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                            <tr>
                              <th className="p-3">Metric</th>
                              <th className="p-3 text-right">Value</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-[#374151]/50 text-xs">
                            <tr><td className="p-3">False Positives</td><td className="p-3 text-right font-mono font-bold text-[#EF4444]">{fprResult.falsePositives}</td></tr>
                            <tr><td className="p-3">Actual FPR</td><td className="p-3 text-right font-mono font-bold text-[#F9FAFB]">{(fprResult.actualFPR * 100).toFixed(3)}%</td></tr>
                            <tr><td className="p-3">Expected FPR</td><td className="p-3 text-right font-mono text-[#6B7280]">{(fprResult.expectedFPR * 100).toFixed(3)}%</td></tr>
                            <tr><td className="p-3">Fill Ratio</td><td className="p-3 text-right font-mono text-[#F59E0B]">{formatPercent(fprResult.fillRatio)}</td></tr>
                            <tr><td className="p-3">Optimal k</td><td className="p-3 text-right font-mono text-[#8B5CF6]">{fprResult.optK}</td></tr>
                          </tbody>
                        </table>
                      </div>
                    </Card>

                    <Card title="Actual vs Expected FPR">
                      <BarChart
                        data={[
                          { name: 'Expected', fpr: +(fprResult.expectedFPR * 100).toFixed(2) },
                          { name: 'Actual', fpr: +(fprResult.actualFPR * 100).toFixed(2) }
                        ]}
                        xAxisKey="name"
                        series={[{ key: 'fpr', color: '#EF4444', name: 'FPR %' }]}
                        height={200}
                      />
                    </Card>
                  </motion.div>
                ) : (
                  <div className="flex flex-col items-center justify-center py-20 text-center text-[#6B7280]">
                    <FlaskConical size={32} className="mb-3 text-[#374151]" />
                    <p className="text-sm font-medium">No FPR test run yet</p>
                    <p className="text-xs mt-1 max-w-xs">Configure parameters and click "Run FPR Test" to empirically measure false positive rates against the theoretical prediction.</p>
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

export default BloomFilter;
