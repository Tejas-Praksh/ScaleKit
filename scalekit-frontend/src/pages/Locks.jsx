import React, { useState, useCallback, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Lock, Unlock, Shield, Clock, CheckCircle, XCircle,
  Play, Server, AlertTriangle, ArrowRight, RotateCcw, Eye
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Tabs from '../components/ui/Tabs';
import { formatNumber, formatDuration } from '../utils/formatters';

let fencingCounter = 0;
function nextFencingToken() { return ++fencingCounter; }
function genLockId() { return 'lk-' + Math.random().toString(36).slice(2, 10); }

const Locks = () => {
  const [activeTab, setActiveTab] = useState('manager');

  // ── Lock Manager State ─────────────────────────────────────────────────────
  const [locks, setLocks] = useState([]);
  const [resourceInput, setResourceInput] = useState('');
  const [ttlInput, setTtlInput] = useState(30);
  const [totalAcquired, setTotalAcquired] = useState(0);
  const [totalFailed, setTotalFailed] = useState(0);

  // ── Redlock Simulation State ───────────────────────────────────────────────
  const [redlockNodes, setRedlockNodes] = useState([
    { name: 'Redis-1', healthy: true, locked: false },
    { name: 'Redis-2', healthy: true, locked: false },
    { name: 'Redis-3', healthy: true, locked: false },
    { name: 'Redis-4', healthy: true, locked: false },
    { name: 'Redis-5', healthy: true, locked: false },
  ]);
  const [redlockLog, setRedlockLog] = useState([]);
  const [redlockResult, setRedlockResult] = useState(null);
  const [redlockRunning, setRedlockRunning] = useState(false);
  const [redlockLocked, setRedlockLocked] = useState(false);

  // ── Fencing Token Demo State ───────────────────────────────────────────────
  const [fencingStep, setFencingStep] = useState(0);
  const [fencingRunning, setFencingRunning] = useState(false);
  const [fencingLog, setFencingLog] = useState([]);

  // ── TTL Countdown ──────────────────────────────────────────────────────────
  useEffect(() => {
    const interval = setInterval(() => {
      setLocks(prev => prev.map(lock => {
        if (lock.status === 'HELD') {
          const elapsed = Date.now() - lock.acquiredAt;
          const remaining = lock.ttlMs - elapsed;
          if (remaining <= 0) {
            return { ...lock, status: 'EXPIRED', remaining: 0 };
          }
          return { ...lock, remaining };
        }
        return lock;
      }));
    }, 200);
    return () => clearInterval(interval);
  }, []);

  // ── Lock Manager Actions ───────────────────────────────────────────────────
  const acquireLock = useCallback(() => {
    const resource = resourceInput.trim() || `resource-${locks.length + 1}`;

    // Check for existing held lock on same resource
    const existing = locks.find(l => l.resource === resource && l.status === 'HELD');
    if (existing) {
      setTotalFailed(f => f + 1);
      return;
    }

    const lock = {
      id: genLockId(),
      resource,
      owner: `client-${Math.floor(Math.random() * 100)}`,
      fencingToken: nextFencingToken(),
      ttlMs: ttlInput * 1000,
      acquiredAt: Date.now(),
      status: 'HELD',
      remaining: ttlInput * 1000,
    };

    setLocks(prev => [lock, ...prev]);
    setTotalAcquired(a => a + 1);
    setResourceInput('');
  }, [resourceInput, ttlInput, locks]);

  const releaseLock = useCallback((lockId) => {
    setLocks(prev => prev.map(l =>
      l.id === lockId ? { ...l, status: 'RELEASED', remaining: 0 } : l
    ));
  }, []);

  const activeLocks = locks.filter(l => l.status === 'HELD');
  const avgHoldTime = activeLocks.length > 0
    ? activeLocks.reduce((sum, l) => sum + (Date.now() - l.acquiredAt), 0) / activeLocks.length
    : 0;

  // ── Redlock Simulation ─────────────────────────────────────────────────────
  const runRedlock = useCallback(async () => {
    if (redlockRunning) return;
    setRedlockRunning(true);
    setRedlockLog([]);
    setRedlockResult(null);

    const log = [];
    const nodesState = [...redlockNodes];
    let acquired = 0;
    const quorum = Math.floor(nodesState.length / 2) + 1;
    const startTime = Date.now();

    for (let i = 0; i < nodesState.length; i++) {
      const node = nodesState[i];
      const latency = 10 + Math.floor(Math.random() * 90);

      await new Promise(r => setTimeout(r, latency));

      const step = {
        step: i + 1,
        node: node.name,
        action: 'SET NX',
        latencyMs: latency,
      };

      if (!node.healthy) {
        step.result = 'FAILED (node down)';
        step.status = 'fail';
      } else if (node.locked) {
        step.result = 'FAILED (already locked)';
        step.status = 'fail';
      } else {
        step.result = 'ACQUIRED';
        step.status = 'ok';
        acquired++;
        nodesState[i] = { ...node, locked: true };
      }

      log.push(step);
      setRedlockLog([...log]);
    }

    const totalTime = Date.now() - startTime;
    const success = acquired >= quorum;

    if (!success) {
      // Release any acquired locks
      nodesState.forEach((n, i) => {
        if (n.locked && redlockNodes[i].healthy) {
          nodesState[i] = { ...n, locked: false };
        }
      });
    }

    setRedlockNodes(nodesState);
    setRedlockLocked(success);
    setRedlockResult({
      success,
      acquired,
      total: nodesState.length,
      quorum,
      totalTime,
    });
    setRedlockRunning(false);
  }, [redlockRunning, redlockNodes]);

  const releaseRedlock = useCallback(() => {
    setRedlockNodes(prev => prev.map(n => ({ ...n, locked: false })));
    setRedlockLocked(false);
    setRedlockResult(null);
    setRedlockLog([]);
  }, []);

  const toggleNodeHealth = useCallback((idx) => {
    setRedlockNodes(prev => prev.map((n, i) =>
      i === idx ? { ...n, healthy: !n.healthy, locked: false } : n
    ));
  }, []);

  // ── Fencing Token Demo ─────────────────────────────────────────────────────
  const FENCING_STEPS = [
    { actor: 'Client A', action: 'Acquires lock', detail: 'Gets fencing token #33', token: 33, type: 'acquire' },
    { actor: 'Client A', action: 'Starts work', detail: 'Processing request...', type: 'work' },
    { actor: 'Client A', action: 'GC Pause!', detail: 'JVM stop-the-world pause (3s)', type: 'pause' },
    { actor: 'System', action: 'Lock expires', detail: 'TTL reached. Lock auto-released.', type: 'expire' },
    { actor: 'Client B', action: 'Acquires lock', detail: 'Gets fencing token #34', token: 34, type: 'acquire' },
    { actor: 'Client B', action: 'Writes to storage', detail: 'Token #34 accepted ✓', token: 34, type: 'write-ok' },
    { actor: 'Client A', action: 'Resumes, writes', detail: 'Token #33 rejected ✗ (< #34)', token: 33, type: 'write-fail' },
    { actor: 'System', action: 'Safety preserved', detail: 'Stale write prevented by fencing token', type: 'safe' },
  ];

  const runFencingDemo = useCallback(async () => {
    if (fencingRunning) return;
    setFencingRunning(true);
    setFencingStep(0);
    setFencingLog([]);

    for (let i = 0; i < FENCING_STEPS.length; i++) {
      await new Promise(r => setTimeout(r, 800));
      setFencingStep(i + 1);
      setFencingLog(prev => [...prev, FENCING_STEPS[i]]);
    }

    setFencingRunning(false);
  }, [fencingRunning]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-[#F9FAFB]">Distributed Locks Manager</h2>
          <p className="text-sm text-[#9CA3AF]">Redlock algorithm dashboard, fencing tokens, and watchdog simulation.</p>
        </div>
        <Badge variant="INFO">Interactive</Badge>
      </div>

      <Tabs
        tabs={[
          { id: 'manager', label: 'Lock Manager' },
          { id: 'redlock', label: 'Redlock Simulation' },
          { id: 'fencing', label: 'Fencing Tokens' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      <div className="min-h-[400px]">
        {/* ── TAB 1: LOCK MANAGER ─────────────────────────────────────────────── */}
        {activeTab === 'manager' && (
          <div className="space-y-6">
            {/* Stats */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard label="Active Locks" value={activeLocks.length} color="blue" icon={Lock} />
              <StatCard label="Total Acquired" value={totalAcquired} color="green" icon={CheckCircle} />
              <StatCard label="Failed" value={totalFailed} color="red" icon={XCircle} />
              <StatCard label="Avg Hold Time" value={formatDuration(avgHoldTime)} color="amber" icon={Clock} />
            </div>

            {/* Acquire Lock */}
            <Card title="Acquire Lock">
              <div className="grid grid-cols-1 md:grid-cols-12 gap-4 mt-2">
                <div className="md:col-span-5">
                  <Input
                    value={resourceInput}
                    onChange={e => setResourceInput(e.target.value)}
                    placeholder="resource name"
                    label="Resource"
                    onKeyDown={e => e.key === 'Enter' && acquireLock()}
                  />
                </div>
                <div className="md:col-span-4 space-y-1">
                  <label className="text-xs text-[#9CA3AF] font-medium block">TTL: {ttlInput}s</label>
                  <input
                    type="range" min="1" max="60" step="1"
                    value={ttlInput}
                    onChange={e => setTtlInput(+e.target.value)}
                    className="w-full accent-[#3B82F6]"
                  />
                </div>
                <div className="md:col-span-3 flex items-end">
                  <Button onClick={acquireLock} className="w-full">
                    <Lock size={14} className="mr-2" /> Acquire
                  </Button>
                </div>
              </div>
            </Card>

            {/* Active Locks Table */}
            <Card title="Lock Registry" subtitle={`${locks.length} total, ${activeLocks.length} active`}>
              <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                <table className="w-full text-left text-sm text-[#9CA3AF]">
                  <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                    <tr>
                      <th className="p-3">Resource</th>
                      <th className="p-3">Lock ID</th>
                      <th className="p-3">Owner</th>
                      <th className="p-3">Token</th>
                      <th className="p-3">TTL Left</th>
                      <th className="p-3 text-center">Status</th>
                      <th className="p-3 text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#374151]/50 text-xs">
                    {locks.length > 0 ? locks.map(lock => (
                      <tr key={lock.id} className="hover:bg-[#1F2937]/30">
                        <td className="p-3 font-medium text-[#F9FAFB]">{lock.resource}</td>
                        <td className="p-3 font-mono text-[#6B7280]">{lock.id}</td>
                        <td className="p-3">{lock.owner}</td>
                        <td className="p-3 font-mono font-bold text-[#F9FAFB]">#{lock.fencingToken}</td>
                        <td className="p-3 font-mono">
                          {lock.status === 'HELD' ? (
                            <span className={lock.remaining < 5000 ? 'text-[#EF4444]' : 'text-[#F9FAFB]'}>
                              {(lock.remaining / 1000).toFixed(1)}s
                            </span>
                          ) : '—'}
                        </td>
                        <td className="p-3 text-center">
                          <Badge variant={
                            lock.status === 'HELD' ? 'SAFE' :
                            lock.status === 'EXPIRED' ? 'DANGER' :
                            lock.status === 'RELEASED' ? 'MUTED' : 'WARNING'
                          }>
                            {lock.status}
                          </Badge>
                        </td>
                        <td className="p-3 text-right">
                          {lock.status === 'HELD' && (
                            <Button variant="ghost" onClick={() => releaseLock(lock.id)} className="text-xs px-2 py-1">
                              <Unlock size={12} className="mr-1" /> Release
                            </Button>
                          )}
                        </td>
                      </tr>
                    )) : (
                      <tr><td colSpan="7" className="p-8 text-center text-[#6B7280] font-sans">No locks acquired yet.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        )}

        {/* ── TAB 2: REDLOCK SIMULATION ───────────────────────────────────────── */}
        {activeTab === 'redlock' && (
          <div className="space-y-6">
            {/* Redis Nodes */}
            <div className="grid grid-cols-5 gap-3">
              {redlockNodes.map((node, idx) => (
                <Card key={node.name} className="!p-4 text-center">
                  <div className="flex items-center justify-center gap-2 mb-2">
                    <span className={`w-2 h-2 rounded-full ${node.healthy ? 'bg-[#10B981]' : 'bg-[#EF4444]'}`} />
                    <span className="text-xs font-bold text-[#F9FAFB]">{node.name}</span>
                  </div>
                  <div className="mb-2">
                    <Badge variant={node.locked ? 'WARNING' : node.healthy ? 'SAFE' : 'DANGER'}>
                      {node.locked ? 'LOCKED' : node.healthy ? 'READY' : 'DOWN'}
                    </Badge>
                  </div>
                  <button
                    onClick={() => toggleNodeHealth(idx)}
                    className="text-[10px] text-[#6B7280] hover:text-[#F9FAFB] transition-all underline"
                  >
                    {node.healthy ? 'Simulate failure' : 'Bring back up'}
                  </button>
                </Card>
              ))}
            </div>

            {/* Controls */}
            <div className="flex gap-3">
              <Button onClick={runRedlock} loading={redlockRunning} disabled={redlockLocked}>
                <Lock size={14} className="mr-2" /> Acquire (Redlock)
              </Button>
              <Button variant="secondary" onClick={releaseRedlock} disabled={!redlockLocked && !redlockResult}>
                <Unlock size={14} className="mr-2" /> Release All
              </Button>
            </div>

            {/* Result Banner */}
            {redlockResult && (
              <motion.div
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                className={`p-4 rounded-lg border ${
                  redlockResult.success
                    ? 'bg-[#10B981]/10 border-[#10B981]/30'
                    : 'bg-[#EF4444]/10 border-[#EF4444]/30'
                }`}
              >
                <div className="flex items-center gap-3">
                  {redlockResult.success ? <CheckCircle size={20} className="text-[#10B981]" /> : <XCircle size={20} className="text-[#EF4444]" />}
                  <div>
                    <span className="font-bold text-[#F9FAFB] text-sm">
                      {redlockResult.success ? 'Lock ACQUIRED' : 'Lock FAILED'}
                    </span>
                    <span className="text-xs text-[#9CA3AF] ml-3">
                      Acquired {redlockResult.acquired}/{redlockResult.total} nodes.
                      Quorum: {redlockResult.quorum}.
                      Time: {redlockResult.totalTime}ms
                    </span>
                  </div>
                </div>
              </motion.div>
            )}

            {/* Algorithm Log */}
            <Card title="Algorithm Execution Log">
              <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                <table className="w-full text-left text-xs text-[#9CA3AF]">
                  <thead className="bg-[#111827] text-[10px] uppercase text-[#F9FAFB] border-b border-[#374151]">
                    <tr>
                      <th className="p-2">Step</th>
                      <th className="p-2">Node</th>
                      <th className="p-2">Action</th>
                      <th className="p-2 text-center">Result</th>
                      <th className="p-2 text-right">Latency</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#374151]/50 font-mono">
                    {redlockLog.length > 0 ? redlockLog.map((entry, i) => (
                      <motion.tr
                        key={i}
                        initial={{ opacity: 0, x: -10 }}
                        animate={{ opacity: 1, x: 0 }}
                        className="hover:bg-[#1F2937]/30"
                      >
                        <td className="p-2">#{entry.step}</td>
                        <td className="p-2 text-[#F9FAFB]">{entry.node}</td>
                        <td className="p-2">{entry.action}</td>
                        <td className="p-2 text-center">
                          <Badge variant={entry.status === 'ok' ? 'SAFE' : 'DANGER'}>
                            {entry.result}
                          </Badge>
                        </td>
                        <td className="p-2 text-right">{entry.latencyMs}ms</td>
                      </motion.tr>
                    )) : (
                      <tr><td colSpan="5" className="p-6 text-center text-[#6B7280] font-sans">Click "Acquire (Redlock)" to run the algorithm.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        )}

        {/* ── TAB 3: FENCING TOKENS ───────────────────────────────────────────── */}
        {activeTab === 'fencing' && (
          <div className="space-y-6">
            <Card title="Fencing Token Safety Demo" subtitle="Demonstrates how fencing tokens prevent stale writes after lock expiry">
              <Button onClick={runFencingDemo} loading={fencingRunning} className="mt-3">
                <Play size={14} className="mr-2" /> {fencingStep > 0 ? 'Replay Demo' : 'Play Demo'}
              </Button>
            </Card>

            {/* Timeline */}
            {fencingLog.length > 0 && (
              <div className="space-y-3">
                <AnimatePresence>
                  {fencingLog.map((step, i) => {
                    const isClientA = step.actor === 'Client A';
                    const isClientB = step.actor === 'Client B';
                    const isSystem = step.actor === 'System';

                    let borderColor = 'border-[#374151]';
                    let bgColor = '';
                    if (step.type === 'acquire') { borderColor = 'border-l-[#10B981]'; bgColor = 'bg-[#10B981]/5'; }
                    if (step.type === 'pause') { borderColor = 'border-l-[#F59E0B]'; bgColor = 'bg-[#F59E0B]/5'; }
                    if (step.type === 'expire') { borderColor = 'border-l-[#EF4444]'; bgColor = 'bg-[#EF4444]/5'; }
                    if (step.type === 'write-ok') { borderColor = 'border-l-[#10B981]'; bgColor = 'bg-[#10B981]/5'; }
                    if (step.type === 'write-fail') { borderColor = 'border-l-[#EF4444]'; bgColor = 'bg-[#EF4444]/5'; }
                    if (step.type === 'safe') { borderColor = 'border-l-[#3B82F6]'; bgColor = 'bg-[#3B82F6]/5'; }

                    return (
                      <motion.div
                        key={i}
                        initial={{ opacity: 0, x: -20 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ duration: 0.15 }}
                        className={`p-4 rounded-lg border border-l-4 ${borderColor} ${bgColor}`}
                      >
                        <div className="flex items-center gap-3">
                          <Badge variant={isClientA ? 'INFO' : isClientB ? 'PURPLE' : 'MUTED'}>
                            {step.actor}
                          </Badge>
                          <span className="font-medium text-[#F9FAFB] text-sm">{step.action}</span>
                          {step.token && (
                            <span className="font-mono font-bold text-[#F59E0B] text-sm">Token #{step.token}</span>
                          )}
                        </div>
                        <p className="text-xs text-[#9CA3AF] mt-1 ml-1">{step.detail}</p>
                      </motion.div>
                    );
                  })}
                </AnimatePresence>
              </div>
            )}

            {fencingLog.length === 0 && (
              <div className="flex flex-col items-center justify-center py-16 text-center text-[#6B7280]">
                <Shield size={32} className="mb-3 text-[#374151]" />
                <p className="text-sm font-medium">Fencing Token Demo</p>
                <p className="text-xs mt-1 max-w-sm">
                  Click "Play Demo" to see how fencing tokens prevent data corruption
                  when a client holds a stale lock after a GC pause.
                </p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default Locks;
