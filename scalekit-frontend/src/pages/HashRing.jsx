import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import * as d3 from 'd3';
import {
  Plus, Trash2, Hash, Server, ArrowRightLeft, RefreshCw,
  Circle, Target, AlertTriangle, Search
} from 'lucide-react';
import Card from '../components/ui/Card';
import StatCard from '../components/ui/StatCard';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Tabs from '../components/ui/Tabs';
import BarChart from '../components/charts/BarChart';
import { formatNumber, formatPercent } from '../utils/formatters';

// ── Hash Function ────────────────────────────────────────────────────────────
function hashCode(str) {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash + str.charCodeAt(i)) >>> 0;
  }
  return hash;
}

const HASH_SPACE = 0xFFFFFFFF;
const NODE_COLORS = ['#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6', '#06B6D4', '#EC4899', '#14B8A6', '#F97316', '#6366F1'];

// ── Ring Engine ──────────────────────────────────────────────────────────────
function buildRing(nodes, vnodeCount) {
  const ring = [];
  nodes.forEach((node, idx) => {
    for (let i = 0; i < vnodeCount; i++) {
      const vname = `${node}#${i}`;
      const h = hashCode(vname);
      ring.push({ hash: h, node, vindex: i, color: NODE_COLORS[idx % NODE_COLORS.length] });
    }
  });
  ring.sort((a, b) => a.hash - b.hash);
  return ring;
}

function lookupKey(ring, key) {
  if (ring.length === 0) return null;
  const h = hashCode(key);
  for (const entry of ring) {
    if (entry.hash >= h) return { ...entry, keyHash: h };
  }
  return { ...ring[0], keyHash: h }; // wrap around
}

function getDistribution(ring, keys) {
  const dist = {};
  keys.forEach(k => {
    const result = lookupKey(ring, k);
    if (result) {
      dist[result.node] = (dist[result.node] || 0) + 1;
    }
  });
  return dist;
}

function calcStdDev(values) {
  if (values.length === 0) return 0;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const sq = values.map(v => (v - mean) ** 2);
  return Math.sqrt(sq.reduce((a, b) => a + b, 0) / values.length);
}

const HashRing = () => {
  const [activeTab, setActiveTab] = useState('visualizer');
  const [nodes, setNodes] = useState(['node-alpha', 'node-beta', 'node-gamma']);
  const [vnodeCount, setVnodeCount] = useState(150);
  const [newNodeName, setNewNodeName] = useState('');
  const [keys, setKeys] = useState(['user:1001', 'session:abc', 'order:5432', 'cart:xyz', 'product:99']);
  const [newKey, setNewKey] = useState('');
  const [selectedKey, setSelectedKey] = useState(null);
  const svgRef = useRef(null);
  const containerRef = useRef(null);

  // Rebalance state
  const [rebalanceAction, setRebalanceAction] = useState(null); // { type: 'add'|'remove', node }
  const [rebalanceData, setRebalanceData] = useState(null);

  const ring = useMemo(() => buildRing(nodes, vnodeCount), [nodes, vnodeCount]);

  const keyAssignments = useMemo(() => {
    return keys.map(k => {
      const result = lookupKey(ring, k);
      return {
        key: k,
        hash: result ? result.keyHash : 0,
        assignedNode: result ? result.node : 'none',
        color: result ? result.color : '#6B7280',
        angle: result ? (result.keyHash / HASH_SPACE) * 360 : 0
      };
    });
  }, [ring, keys]);

  const distribution = useMemo(() => getDistribution(ring, keys), [ring, keys]);

  const distributionChartData = useMemo(() => {
    return nodes.map((n, i) => ({
      name: n.replace('node-', ''),
      keys: distribution[n] || 0
    }));
  }, [nodes, distribution]);

  const totalVnodes = nodes.length * vnodeCount;
  const loadStdDev = useMemo(() => {
    const counts = nodes.map(n => distribution[n] || 0);
    return calcStdDev(counts);
  }, [nodes, distribution]);

  // ── Add / Remove Nodes ─────────────────────────────────────────────────────
  const addNode = useCallback(() => {
    const name = newNodeName.trim() || `node-${String.fromCharCode(97 + nodes.length)}`;
    if (nodes.includes(name)) return;
    setNodes(prev => [...prev, name]);
    setNewNodeName('');
  }, [newNodeName, nodes]);

  const removeNode = useCallback((name) => {
    setNodes(prev => prev.filter(n => n !== name));
  }, []);

  const addKey = useCallback(() => {
    const k = newKey.trim();
    if (!k || keys.includes(k)) return;
    setKeys(prev => [...prev, k]);
    setNewKey('');
  }, [newKey, keys]);

  // ── Rebalance Analysis ─────────────────────────────────────────────────────
  const runRebalance = useCallback((type, nodeName) => {
    const beforeRing = buildRing(nodes, vnodeCount);
    const afterNodes = type === 'add'
      ? [...nodes, nodeName]
      : nodes.filter(n => n !== nodeName);
    const afterRing = buildRing(afterNodes, vnodeCount);

    const migrations = keys.map(k => {
      const before = lookupKey(beforeRing, k);
      const after = lookupKey(afterRing, k);
      return {
        key: k,
        prevNode: before ? before.node : 'none',
        newNode: after ? after.node : 'none',
        migrated: (before?.node || '') !== (after?.node || '')
      };
    });

    const migratedCount = migrations.filter(m => m.migrated).length;
    setRebalanceAction({ type, node: nodeName });
    setRebalanceData({
      migrations,
      migratedCount,
      migrationPct: keys.length > 0 ? (migratedCount / keys.length) : 0,
      beforeNodes: nodes.length,
      afterNodes: afterNodes.length
    });
    setActiveTab('rebalance');
  }, [nodes, vnodeCount, keys]);

  // ── D3 Ring Drawing ────────────────────────────────────────────────────────
  useEffect(() => {
    if (activeTab !== 'visualizer' || !svgRef.current) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const width = containerRef.current?.clientWidth || 500;
    const size = Math.min(width, 500);
    const cx = size / 2;
    const cy = size / 2;
    const radius = size * 0.38;

    svg.attr('viewBox', `0 0 ${size} ${size}`);

    // Ring circle
    svg.append('circle')
      .attr('cx', cx).attr('cy', cy).attr('r', radius)
      .attr('fill', 'none').attr('stroke', '#374151').attr('stroke-width', 2);

    // Hash marks (light ticks every 30 degrees)
    for (let deg = 0; deg < 360; deg += 30) {
      const rad = (deg - 90) * Math.PI / 180;
      const x1 = cx + (radius - 6) * Math.cos(rad);
      const y1 = cy + (radius - 6) * Math.sin(rad);
      const x2 = cx + (radius + 6) * Math.cos(rad);
      const y2 = cy + (radius + 6) * Math.sin(rad);
      svg.append('line')
        .attr('x1', x1).attr('y1', y1).attr('x2', x2).attr('y2', y2)
        .attr('stroke', '#4B5563').attr('stroke-width', 1);
    }

    // Virtual nodes (small dots)
    const vnodeGroup = svg.append('g');
    ring.forEach(entry => {
      const angle = (entry.hash / HASH_SPACE) * 360 - 90;
      const rad = angle * Math.PI / 180;
      const x = cx + radius * Math.cos(rad);
      const y = cy + radius * Math.sin(rad);
      vnodeGroup.append('circle')
        .attr('cx', x).attr('cy', y).attr('r', 2)
        .attr('fill', entry.color).attr('opacity', 0.3);
    });

    // Primary node labels (first vnode of each node)
    const primaryPositions = {};
    ring.forEach(entry => {
      if (!(entry.node in primaryPositions)) {
        primaryPositions[entry.node] = entry;
      }
    });

    Object.entries(primaryPositions).forEach(([nodeName, entry]) => {
      const angle = (entry.hash / HASH_SPACE) * 360 - 90;
      const rad = angle * Math.PI / 180;
      const x = cx + radius * Math.cos(rad);
      const y = cy + radius * Math.sin(rad);
      const lx = cx + (radius + 28) * Math.cos(rad);
      const ly = cy + (radius + 28) * Math.sin(rad);

      svg.append('circle')
        .attr('cx', x).attr('cy', y).attr('r', 7)
        .attr('fill', entry.color).attr('stroke', '#111827').attr('stroke-width', 2);

      svg.append('text')
        .attr('x', lx).attr('y', ly)
        .attr('text-anchor', 'middle').attr('dominant-baseline', 'middle')
        .attr('fill', entry.color).attr('font-size', '9px').attr('font-weight', '600')
        .attr('font-family', 'Inter, sans-serif')
        .text(nodeName.replace('node-', ''));
    });

    // Keys on the ring
    keyAssignments.forEach(ka => {
      const angle = (ka.hash / HASH_SPACE) * 360 - 90;
      const rad = angle * Math.PI / 180;
      const x = cx + (radius - 20) * Math.cos(rad);
      const y = cy + (radius - 20) * Math.sin(rad);

      // Diamond marker
      const dSize = 5;
      svg.append('polygon')
        .attr('points', `${x},${y - dSize} ${x + dSize},${y} ${x},${y + dSize} ${x - dSize},${y}`)
        .attr('fill', ka.color).attr('stroke', '#111827').attr('stroke-width', 1);

      // Line to assigned node on ring
      const assigned = lookupKey(ring, ka.key);
      if (assigned) {
        const aAngle = (assigned.hash / HASH_SPACE) * 360 - 90;
        const aRad = aAngle * Math.PI / 180;
        const ax = cx + radius * Math.cos(aRad);
        const ay = cy + radius * Math.sin(aRad);
        svg.append('line')
          .attr('x1', x).attr('y1', y).attr('x2', ax).attr('y2', ay)
          .attr('stroke', ka.color).attr('stroke-width', 1).attr('opacity', 0.35)
          .attr('stroke-dasharray', '3,3');
      }

      // Key label
      const kx = cx + (radius - 38) * Math.cos(rad);
      const ky = cy + (radius - 38) * Math.sin(rad);
      svg.append('text')
        .attr('x', kx).attr('y', ky)
        .attr('text-anchor', 'middle').attr('dominant-baseline', 'middle')
        .attr('fill', '#9CA3AF').attr('font-size', '8px')
        .attr('font-family', '"JetBrains Mono", monospace')
        .text(ka.key.length > 10 ? ka.key.slice(0, 8) + '..' : ka.key);
    });

    // Center info
    svg.append('text')
      .attr('x', cx).attr('y', cy - 10)
      .attr('text-anchor', 'middle').attr('fill', '#F9FAFB')
      .attr('font-size', '14px').attr('font-weight', '700')
      .attr('font-family', '"JetBrains Mono", monospace')
      .text(`${nodes.length} nodes`);
    svg.append('text')
      .attr('x', cx).attr('y', cy + 10)
      .attr('text-anchor', 'middle').attr('fill', '#6B7280')
      .attr('font-size', '10px')
      .attr('font-family', 'Inter, sans-serif')
      .text(`${formatNumber(totalVnodes)} vnodes`);

  }, [activeTab, ring, keyAssignments, nodes, totalVnodes]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-[#F9FAFB]">Consistent Hashing Ring</h2>
          <p className="text-sm text-[#9CA3AF]">Visualize virtual node distribution and key assignment across servers.</p>
        </div>
        <Badge variant="INFO">Interactive</Badge>
      </div>

      {/* Stats Row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Physical Nodes" value={nodes.length} color="blue" icon={Server} />
        <StatCard label="Virtual Nodes" value={formatNumber(totalVnodes)} color="purple" icon={Circle} />
        <StatCard label="Total Keys" value={keys.length} color="green" icon={Hash} />
        <StatCard label="Load Std Dev" value={loadStdDev.toFixed(2)} color={loadStdDev > 3 ? 'amber' : 'cyan'} icon={Target} />
      </div>

      <Tabs
        tabs={[
          { id: 'visualizer', label: 'Ring Visualizer' },
          { id: 'distribution', label: 'Key Distribution' },
          { id: 'rebalance', label: 'Rebalancing' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      <div className="min-h-[400px]">
        {/* ── TAB 1: RING VISUALIZER ──────────────────────────────────────────── */}
        {activeTab === 'visualizer' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Left Panel - Controls */}
            <div className="lg:col-span-5 space-y-4">
              {/* Add Node */}
              <Card title="Manage Nodes">
                <div className="flex gap-2 mt-2">
                  <Input
                    value={newNodeName}
                    onChange={(e) => setNewNodeName(e.target.value)}
                    placeholder="node-delta"
                    onKeyDown={(e) => e.key === 'Enter' && addNode()}
                  />
                  <Button onClick={addNode} className="shrink-0">
                    <Plus size={14} className="mr-1" /> Add
                  </Button>
                </div>

                {/* Vnode slider */}
                <div className="mt-4 space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="text-[#9CA3AF]">Virtual Nodes per Server</span>
                    <span className="font-bold text-[#3B82F6] font-mono">{vnodeCount}</span>
                  </div>
                  <input
                    type="range" min="1" max="300" step="1"
                    value={vnodeCount}
                    onChange={(e) => setVnodeCount(parseInt(e.target.value))}
                    className="w-full accent-[#3B82F6]"
                  />
                </div>

                {/* Node Table */}
                <div className="mt-4 overflow-x-auto border border-[#374151] rounded-lg">
                  <table className="w-full text-left text-xs text-[#9CA3AF]">
                    <thead className="bg-[#111827] text-[10px] uppercase text-[#F9FAFB] border-b border-[#374151]">
                      <tr>
                        <th className="p-2">Node</th>
                        <th className="p-2 text-center">Keys</th>
                        <th className="p-2 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[#374151]/50">
                      {nodes.map((n, i) => (
                        <tr key={n} className="hover:bg-[#1F2937]/50">
                          <td className="p-2 flex items-center gap-2">
                            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: NODE_COLORS[i % NODE_COLORS.length] }} />
                            <span className="font-medium text-[#F9FAFB]">{n}</span>
                          </td>
                          <td className="p-2 text-center font-mono text-[#F9FAFB]">{distribution[n] || 0}</td>
                          <td className="p-2 text-right">
                            <div className="flex gap-1 justify-end">
                              <button
                                onClick={() => runRebalance('remove', n)}
                                className="text-[#F59E0B] hover:text-[#F59E0B]/80 p-1"
                                title="Simulate removal"
                              >
                                <ArrowRightLeft size={12} />
                              </button>
                              <button
                                onClick={() => removeNode(n)}
                                className="text-[#EF4444] hover:text-[#EF4444]/80 p-1"
                                title="Remove node"
                                disabled={nodes.length <= 1}
                              >
                                <Trash2 size={12} />
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>

              {/* Add Key */}
              <Card title="Test Keys">
                <div className="flex gap-2 mt-2">
                  <Input
                    value={newKey}
                    onChange={(e) => setNewKey(e.target.value)}
                    placeholder="cache:user:42"
                    onKeyDown={(e) => e.key === 'Enter' && addKey()}
                  />
                  <Button variant="secondary" onClick={addKey} className="shrink-0">
                    <Plus size={14} className="mr-1" /> Add
                  </Button>
                </div>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {keys.map(k => {
                    const assign = lookupKey(ring, k);
                    return (
                      <button
                        key={k}
                        onClick={() => setSelectedKey(selectedKey === k ? null : k)}
                        className={`text-[10px] px-2 py-1 rounded border transition-all font-mono ${
                          selectedKey === k
                            ? 'border-[#3B82F6] bg-[#3B82F6]/15 text-[#3B82F6]'
                            : 'border-[#374151] text-[#9CA3AF] hover:border-[#4B5563]'
                        }`}
                      >
                        {k} → {assign?.node?.replace('node-', '') || '?'}
                      </button>
                    );
                  })}
                </div>
              </Card>
            </div>

            {/* Right Panel - D3 Ring */}
            <div className="lg:col-span-7">
              <Card title="Hash Ring" subtitle={`${formatNumber(HASH_SPACE)} hash space (2³²)`}>
                <div ref={containerRef} className="w-full flex justify-center">
                  <svg ref={svgRef} className="w-full max-w-[500px]" style={{ aspectRatio: '1' }} />
                </div>
              </Card>
            </div>
          </div>
        )}

        {/* ── TAB 2: KEY DISTRIBUTION ─────────────────────────────────────────── */}
        {activeTab === 'distribution' && (
          <div className="space-y-6">
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              <div className="lg:col-span-7">
                <Card title="Key Assignment Table" subtitle="Hash value and node mapping for each key">
                  <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                    <table className="w-full text-left text-sm text-[#9CA3AF]">
                      <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                        <tr>
                          <th className="p-3">Key</th>
                          <th className="p-3">Hash Value</th>
                          <th className="p-3">Position (°)</th>
                          <th className="p-3">Assigned Node</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-[#374151]/50 font-mono text-xs">
                        {keyAssignments.map(ka => (
                          <tr key={ka.key} className="hover:bg-[#1F2937]/30">
                            <td className="p-3 text-[#F9FAFB]">{ka.key}</td>
                            <td className="p-3 text-[#6B7280]">0x{ka.hash.toString(16).padStart(8, '0')}</td>
                            <td className="p-3">{ka.angle.toFixed(1)}°</td>
                            <td className="p-3">
                              <span className="flex items-center gap-2">
                                <span className="w-2 h-2 rounded-full" style={{ backgroundColor: ka.color }} />
                                <span className="text-[#F9FAFB]">{ka.assignedNode}</span>
                              </span>
                            </td>
                          </tr>
                        ))}
                        {keyAssignments.length === 0 && (
                          <tr><td colSpan="4" className="p-8 text-center text-[#6B7280] font-sans">No keys added yet.</td></tr>
                        )}
                      </tbody>
                    </table>
                  </div>
                </Card>
              </div>

              <div className="lg:col-span-5">
                <Card title="Distribution by Node" subtitle="Keys per physical node">
                  <div className="mt-2">
                    <BarChart
                      data={distributionChartData}
                      xAxisKey="name"
                      series={[{ key: 'keys', color: '#3B82F6', name: 'Keys' }]}
                      height={220}
                    />
                  </div>
                  <div className="mt-4 space-y-2">
                    {nodes.map((n, i) => {
                      const count = distribution[n] || 0;
                      const pct = keys.length > 0 ? (count / keys.length) * 100 : 0;
                      return (
                        <div key={n} className="flex items-center gap-3 text-xs">
                          <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: NODE_COLORS[i % NODE_COLORS.length] }} />
                          <span className="text-[#9CA3AF] flex-1">{n}</span>
                          <span className="font-mono text-[#F9FAFB] font-bold">{count}</span>
                          <span className="text-[#6B7280] w-12 text-right">{pct.toFixed(0)}%</span>
                        </div>
                      );
                    })}
                  </div>
                </Card>
              </div>
            </div>
          </div>
        )}

        {/* ── TAB 3: REBALANCING ──────────────────────────────────────────────── */}
        {activeTab === 'rebalance' && (
          <div className="space-y-6">
            {/* Simulate Controls */}
            <Card title="Simulate Rebalancing" subtitle="See what happens when a node is added or removed">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-3">
                <div>
                  <label className="text-xs text-[#9CA3AF] block mb-2">Add a new node</label>
                  <Button variant="outline" onClick={() => {
                    const name = `node-${String.fromCharCode(97 + nodes.length)}`;
                    runRebalance('add', name);
                  }} className="w-full">
                    <Plus size={14} className="mr-2" /> Simulate Node Addition
                  </Button>
                </div>
                <div>
                  <label className="text-xs text-[#9CA3AF] block mb-2">Remove an existing node</label>
                  <div className="flex gap-2 flex-wrap">
                    {nodes.map(n => (
                      <Button key={n} variant="outline" onClick={() => runRebalance('remove', n)} className="text-xs">
                        <Trash2 size={12} className="mr-1" /> {n}
                      </Button>
                    ))}
                  </div>
                </div>
              </div>
            </Card>

            {rebalanceData ? (
              <>
                {/* Summary */}
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  <StatCard
                    label="Action"
                    value={rebalanceAction?.type === 'add' ? 'Add' : 'Remove'}
                    color={rebalanceAction?.type === 'add' ? 'green' : 'red'}
                    icon={rebalanceAction?.type === 'add' ? Plus : Trash2}
                  />
                  <StatCard label="Target Node" value={rebalanceAction?.node || '—'} color="blue" icon={Server} />
                  <StatCard label="Keys Migrated" value={rebalanceData.migratedCount} color="amber" icon={ArrowRightLeft} />
                  <StatCard label="Migration %" value={formatPercent(rebalanceData.migrationPct)} color="purple" icon={Target} />
                </div>

                {/* Migration Table */}
                <Card title="Migration Details" subtitle={`${rebalanceData.beforeNodes} → ${rebalanceData.afterNodes} nodes`}>
                  <div className="overflow-x-auto border border-[#374151] rounded-lg mt-2">
                    <table className="w-full text-left text-sm text-[#9CA3AF]">
                      <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                        <tr>
                          <th className="p-3">Key</th>
                          <th className="p-3">Previous Node</th>
                          <th className="p-3">New Node</th>
                          <th className="p-3 text-center">Migrated</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-[#374151]/50 text-xs">
                        {rebalanceData.migrations.map(m => (
                          <tr key={m.key} className={`hover:bg-[#1F2937]/30 ${m.migrated ? 'bg-[#F59E0B]/5' : ''}`}>
                            <td className="p-3 font-mono text-[#F9FAFB]">{m.key}</td>
                            <td className="p-3">{m.prevNode}</td>
                            <td className="p-3">{m.newNode}</td>
                            <td className="p-3 text-center">
                              <Badge variant={m.migrated ? 'WARNING' : 'MUTED'}>
                                {m.migrated ? 'MIGRATED' : 'STABLE'}
                              </Badge>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>
              </>
            ) : (
              <div className="flex flex-col items-center justify-center py-16 text-center text-[#6B7280]">
                <ArrowRightLeft size={32} className="mb-3 text-[#374151]" />
                <p className="text-sm font-medium">No rebalance simulation run yet</p>
                <p className="text-xs mt-1 max-w-xs">Click a simulate button above or use the rebalance icon in the node table on the Visualizer tab.</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default HashRing;
