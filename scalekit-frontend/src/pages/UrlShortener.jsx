import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { 
  Link2, 
  Copy, 
  Check, 
  QrCode, 
  Share2, 
  BarChart3, 
  Calendar, 
  Lock, 
  Eye, 
  EyeOff, 
  Search, 
  Play, 
  ArrowRight,
  TrendingUp,
  Globe,
  Smartphone,
  Info,
  AlertCircle,
  RefreshCw
} from 'lucide-react';
import Card from '../components/ui/Card';
import Badge from '../components/ui/Badge';
import Input from '../components/ui/Input';
import Button from '../components/ui/Button';
import Tabs from '../components/ui/Tabs';
import Alert from '../components/ui/Alert';
import Spinner from '../components/ui/Spinner';
import BarChart from '../components/charts/BarChart';
import DonutChart from '../components/charts/DonutChart';
import { apiService } from '../services/api';
import useApi from '../hooks/useApi';
import { formatNumber, formatDuration, timeAgo } from '../utils/formatters';

const UrlShortener = () => {
  const [activeTab, setActiveTab] = useState('create');
  
  // ── Create Tab State ──────────────────────────────────────────────────────
  const [longUrl, setLongUrl] = useState('');
  const [customAlias, setCustomAlias] = useState('');
  const [expiryDays, setExpiryDays] = useState('');
  const [password, setPassword] = useState('');
  const [showPasswordInput, setShowPasswordInput] = useState(false);
  const [createdUrlData, setCreatedUrlData] = useState(null);
  const [qrCodeData, setQrCodeData] = useState(null);
  const [copied, setCopied] = useState(false);

  // ── Analytics Tab State ───────────────────────────────────────────────────
  const [analyticsCode, setAnalyticsCode] = useState('');
  const [analyticsData, setAnalyticsData] = useState(null);
  const [searchError, setSearchError] = useState(null);

  // ── Top URLs Tab State ────────────────────────────────────────────────────
  const [topUrls, setTopUrls] = useState([]);
  const [topUrlsLoading, setTopUrlsLoading] = useState(false);

  // ── Live Redirect Demo State ──────────────────────────────────────────────
  const [demoCode, setDemoCode] = useState('');
  const [demoResult, setDemoResult] = useState(null);
  const [demoLoading, setDemoLoading] = useState(false);
  const [demoFlash, setDemoFlash] = useState(null); // 'HIT' | 'MISS'

  // API hooks
  const { loading: createLoading, error: createError, execute: runCreate } = useApi(apiService.createUrl);
  const { loading: qrLoading, execute: fetchQr } = useApi(() => Promise.resolve({ success: false }));
  const { loading: analyticsLoading, execute: fetchAnalytics } = useApi(apiService.getUrlAnalytics);

  // ── Copy Short URL Helper ─────────────────────────────────────────────────
  const handleCopy = (text) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // ── Create URL Handler ────────────────────────────────────────────────────
  const handleCreate = async (e) => {
    e.preventDefault();
    if (!longUrl) return;

    setCreatedUrlData(null);
    setQrCodeData(null);

    const payload = {
      originalUrl: longUrl,
      customAlias: customAlias || null,
      password: password || null,
      expiryDays: expiryDays ? parseInt(expiryDays) : null
    };

    try {
      const res = await runCreate(payload);
      if (res && res.success) {
        setCreatedUrlData(res.data);
        
        // Fetch matching QR Code
        try {
          const qrRes = await fetchQr(res.data.shortCode, 200);
          if (qrRes && qrRes.success) {
            setQrCodeData(qrRes.data.qrCodeBase64);
          }
        } catch (qrErr) {
          console.error("QR Code fetch failed", qrErr);
        }

        // Reset form
        setLongUrl('');
        setCustomAlias('');
        setPassword('');
        setExpiryDays('');
      }
    } catch (err) {
      console.error("Create URL failed", err);
    }
  };

  // ── Fetch Analytics Handler ───────────────────────────────────────────────
  const handleSearchAnalytics = async (e) => {
    e.preventDefault();
    if (!analyticsCode) return;
    setSearchError(null);
    setAnalyticsData(null);

    try {
      const res = await fetchAnalytics(analyticsCode);
      if (res && res.success) {
        setAnalyticsData(res.data);
      } else {
        setSearchError("No data found for this short code.");
      }
    } catch (err) {
      setSearchError(err.response?.data?.message || "URL not found.");
    }
  };

  // ── Fetch Top URLs Handler ────────────────────────────────────────────────
  const loadTopUrls = async () => {
    setTopUrlsLoading(true);
    try {
      const res = await apiService.getTopUrls(10);
      if (res.data?.success) {
        setTopUrls(res.data.data);
      }
    } catch (err) {
      console.error("Top URLs fetch failed", err);
    } finally {
      setTopUrlsLoading(false);
    }
  };

  // Trigger loading top URLs on tab click
  useEffect(() => {
    if (activeTab === 'top') {
      loadTopUrls();
    }
  }, [activeTab]);

  // ── Live Redirect Demo Handler ────────────────────────────────────────────
  const handleTestRedirect = async (e) => {
    e.preventDefault();
    if (!demoCode) return;

    setDemoLoading(true);
    setDemoResult(null);
    setDemoFlash(null);

    const startTime = performance.now();
    try {
      const res = await apiService.getUrl(demoCode);
      const durationMs = performance.now() - startTime;
      
      const cacheHit = res.headers['x-cache'] || 'MISS';
      
      setDemoResult({
        originalUrl: res.data?.data?.originalUrl,
        responseTime: durationMs,
        cacheHit: cacheHit
      });

      // Trigger flash animation
      setDemoFlash(cacheHit);
      setTimeout(() => setDemoFlash(null), 1000);
    } catch (err) {
      setDemoResult({
        error: err.response?.data?.message || "Invalid short code or server offline."
      });
    } finally {
      setDemoLoading(false);
    }
  };

  return (
    <div className="space-y-8">
      {/* Tab Selector */}
      <Tabs
        tabs={[
          { id: 'create', label: 'Create Short URL' },
          { id: 'analytics', label: 'Analytics Search' },
          { id: 'top', label: 'Top Performing URLs' }
        ]}
        activeTab={activeTab}
        onChange={setActiveTab}
      />

      {/* Tabs Content */}
      <div className="min-h-[400px]">
        {activeTab === 'create' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
            {/* Form Card */}
            <div className="lg:col-span-7">
              <Card title="Shorten a New URL" subtitle="Submit your destination link to compress and secure it.">
                <form onSubmit={handleCreate} className="space-y-5 mt-4">
                  <Input
                    label="Destination URL"
                    placeholder="https://example.com/deep/nested/page?ref=scalekit"
                    value={longUrl}
                    onChange={(e) => setLongUrl(e.target.value)}
                    icon={Link2}
                    required
                  />

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <Input
                      label="Custom Alias (Optional)"
                      placeholder="my-custom-link"
                      value={customAlias}
                      onChange={(e) => setCustomAlias(e.target.value)}
                    />
                    <Input
                      label="Expiry Days (Optional)"
                      type="number"
                      placeholder="e.g. 30"
                      value={expiryDays}
                      onChange={(e) => setExpiryDays(e.target.value)}
                      icon={Calendar}
                    />
                  </div>

                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-[#9CA3AF]">Password Protection</span>
                      <button
                        type="button"
                        onClick={() => setShowPasswordInput(!showPasswordInput)}
                        className="text-xs text-[#3B82F6] hover:underline"
                      >
                        {showPasswordInput ? "Remove Password" : "Add Password"}
                      </button>
                    </div>

                    {showPasswordInput && (
                      <Input
                        type="password"
                        placeholder="Secure passkey"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        icon={Lock}
                      />
                    )}
                  </div>

                  {createError && (
                    <Alert type="error" message="Creation Failed">
                      {createError.message || "Please check your inputs and try again."}
                    </Alert>
                  )}

                  <Button
                    type="submit"
                    className="w-full h-11"
                    loading={createLoading}
                  >
                    Shorten Link
                  </Button>
                </form>
              </Card>
            </div>

            {/* Results Card */}
            <div className="lg:col-span-5">
              <AnimatePresence mode="wait">
                {createdUrlData ? (
                  <motion.div
                    initial={{ opacity: 0, x: 20 }}
                    animate={{ opacity: 1, x: 0 }}
                    exit={{ opacity: 0, x: -20 }}
                    transition={{ duration: 0.3 }}
                    className="h-full"
                  >
                    <Card title="Shortened URL Ready" subtitle="Copy, share, or test your shortened URL.">
                      <div className="space-y-6 mt-4">
                        {/* URL box */}
                        <div className="flex items-center gap-2 p-3 bg-[#111827] rounded-lg border border-[#374151]">
                          <span className="text-sm font-mono text-[#F9FAFB] truncate flex-1 font-semibold select-all">
                            {createdUrlData.shortUrl}
                          </span>
                          <button
                            onClick={() => handleCopy(createdUrlData.shortUrl)}
                            className="p-2 text-[#9CA3AF] hover:text-[#3B82F6] hover:bg-[#1F2937] rounded-lg transition-colors"
                          >
                            {copied ? <Check size={16} className="text-[#10B981]" /> : <Copy size={16} />}
                          </button>
                        </div>

                        {/* QR Code and Meta Details */}
                        <div className="flex flex-col sm:flex-row items-center gap-6 justify-center">
                          {qrLoading ? (
                            <div className="h-[140px] w-[140px] flex items-center justify-center border border-[#374151] rounded-lg bg-[#111827]">
                              <Spinner size="sm" />
                            </div>
                          ) : qrCodeData ? (
                            <div className="bg-white p-2 rounded-lg shadow-inner">
                              <img
                                src={`data:image/png;base64,${qrCodeData}`}
                                alt="Short URL QR Code"
                                className="h-[130px] w-[130px]"
                              />
                            </div>
                          ) : (
                            <div className="h-[140px] w-[140px] flex flex-col items-center justify-center border border-[#374151] rounded-lg bg-[#111827] text-center p-2 text-xs text-[#6B7280]">
                              <QrCode size={24} className="mb-1" />
                              QR generation skipped
                            </div>
                          )}

                          <div className="flex-1 space-y-3 w-full">
                            <div className="flex justify-between border-b border-[#374151]/30 pb-1 text-xs">
                              <span className="text-[#9CA3AF]">Short Code</span>
                              <span className="font-mono text-[#F9FAFB] font-semibold">{createdUrlData.shortCode}</span>
                            </div>
                            <div className="flex justify-between border-b border-[#374151]/30 pb-1 text-xs">
                              <span className="text-[#9CA3AF]">Clicks</span>
                              <span className="font-mono text-[#F9FAFB]">{createdUrlData.clickCount || 0}</span>
                            </div>
                            <div className="flex justify-between border-b border-[#374151]/30 pb-1 text-xs">
                              <span className="text-[#9CA3AF]">Expiry</span>
                              <span className="text-[#F9FAFB]">{createdUrlData.expiresAt ? new Date(createdUrlData.expiresAt).toLocaleDateString() : 'Never'}</span>
                            </div>
                            {createdUrlData.title && (
                              <div className="text-[11px] text-[#6B7280]">
                                <span className="font-semibold block text-[#9CA3AF]">Resolved Title</span>
                                {createdUrlData.title}
                              </div>
                            )}
                          </div>
                        </div>
                      </div>
                    </Card>
                  </motion.div>
                ) : (
                  <div className="h-full flex items-center justify-center p-8 border-2 border-dashed border-[#374151] rounded-xl text-center text-[#6B7280]">
                    <div className="space-y-2">
                      <Link2 size={32} className="mx-auto text-[#374151] animate-pulse" />
                      <p className="text-sm font-medium">No URL shortened yet</p>
                      <p className="text-xs max-w-[200px] mx-auto">Fill the form to compress, secure, and visualize a long destination link.</p>
                    </div>
                  </div>
                )}
              </AnimatePresence>
            </div>
          </div>
        )}

        {activeTab === 'analytics' && (
          <div className="space-y-6">
            {/* Search Code Bar */}
            <Card>
              <form onSubmit={handleSearchAnalytics} className="flex gap-4">
                <Input
                  placeholder="Enter Short Code (e.g. abc1234)"
                  value={analyticsCode}
                  onChange={(e) => setAnalyticsCode(e.target.value)}
                  icon={Search}
                  className="max-w-md"
                  required
                />
                <Button type="submit" loading={analyticsLoading} className="h-10">
                  Search Analytics
                </Button>
              </form>

              {searchError && (
                <Alert type="error" message="Analytics Lookup Error" className="mt-4">
                  {searchError}
                </Alert>
              )}
            </Card>

            {/* Analytics Output */}
            {analyticsData && (
              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                className="space-y-6"
              >
                {/* Stats Row */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                  <Card className="p-4 py-5 text-center">
                    <span className="text-3xl font-bold text-[#F9FAFB] font-number block">
                      {formatNumber(analyticsData.totalClicks)}
                    </span>
                    <span className="text-xs text-[#9CA3AF] uppercase font-semibold mt-1 block">Total Click Count</span>
                  </Card>
                  <Card className="p-4 py-5 text-center">
                    <span className="text-3xl font-bold text-[#3B82F6] font-number block">
                      {formatNumber(analyticsData.uniqueClicks)}
                    </span>
                    <span className="text-xs text-[#9CA3AF] uppercase font-semibold mt-1 block">Unique Visitors</span>
                  </Card>
                  <Card className="p-4 py-5 text-center">
                    <span className="text-3xl font-bold text-[#10B981] font-number block">
                      {formatNumber(analyticsData.clicksLast24Hours)}
                    </span>
                    <span className="text-xs text-[#9CA3AF] uppercase font-semibold mt-1 block">Clicks (24h)</span>
                  </Card>
                  <Card className="p-4 py-5 text-center">
                    <span className="text-3xl font-bold text-[#F59E0B] font-number block">
                      {analyticsData.topCountry || 'N/A'}
                    </span>
                    <span className="text-xs text-[#9CA3AF] uppercase font-semibold mt-1 block">Top Country</span>
                  </Card>
                </div>

                {/* Charts Grid */}
                <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
                  {/* Clicks over time (30 Days) */}
                  <div className="lg:col-span-8">
                    <Card title="Traffic Trend" subtitle="Click volume history over the last 30 days">
                      {analyticsData.clickTimeSeries && analyticsData.clickTimeSeries.length > 0 ? (
                        <BarChart
                          data={analyticsData.clickTimeSeries}
                          xAxisKey="label"
                          series={[
                            { key: 'totalClicks', color: '#3B82F6', name: 'Total Clicks' },
                            { key: 'uniqueClicks', color: '#10B981', name: 'Unique Visitors' }
                          ]}
                          height={280}
                        />
                      ) : (
                        <div className="py-20 text-center text-[#6B7280] text-sm">No historical time-series available.</div>
                      )}
                    </Card>
                  </div>

                  {/* Clicks by Device */}
                  <div className="lg:col-span-4">
                    <Card title="Device Breakdown" subtitle="Client device segments">
                      {analyticsData.clicksByDevice && Object.keys(analyticsData.clicksByDevice).length > 0 ? (
                        <DonutChart
                          data={Object.entries(analyticsData.clicksByDevice).map(([name, value]) => ({ name, value }))}
                          height={280}
                        />
                      ) : (
                        <div className="py-20 text-center text-[#6B7280] text-sm">No device split available.</div>
                      )}
                    </Card>
                  </div>
                </div>

                {/* Countries List Card */}
                <Card title="Clicks By Country" subtitle="Breakdown of traffic origins by geographical country code">
                  <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
                    {analyticsData.clicksByCountry && Object.keys(analyticsData.clicksByCountry).length > 0 ? (
                      Object.entries(analyticsData.clicksByCountry).map(([country, val]) => (
                        <div key={country} className="flex justify-between items-center p-3 bg-[#111827] border border-[#374151] rounded-lg">
                          <span className="flex items-center gap-1.5 text-xs text-[#9CA3AF]">
                            <Globe size={14} className="text-[#3B82F6]" />
                            {country}
                          </span>
                          <span className="font-bold text-sm text-[#F9FAFB] font-number">{formatNumber(val)}</span>
                        </div>
                      ))
                    ) : (
                      <div className="col-span-full py-6 text-center text-sm text-[#6B7280]">No country clicks records available.</div>
                    )}
                  </div>
                </Card>
              </motion.div>
            )}
          </div>
        )}

        {activeTab === 'top' && (
          <Card title="Top 10 Performing URLs" subtitle="Ranking most accessed links by click volume">
            <div className="absolute top-6 right-6">
              <Button variant="outline" className="py-1 h-8" onClick={loadTopUrls}>
                <RefreshCw size={14} className="mr-1.5" /> Refresh
              </Button>
            </div>

            <div className="overflow-x-auto mt-4 border border-[#374151] rounded-lg">
              <table className="w-full text-left text-sm text-[#9CA3AF] border-collapse">
                <thead className="bg-[#111827] text-xs uppercase text-[#F9FAFB] border-b border-[#374151]">
                  <tr>
                    <th className="p-4 font-semibold">Short Code</th>
                    <th className="p-4 font-semibold">Original URL</th>
                    <th className="p-4 font-semibold text-right">Click Count</th>
                    <th className="p-4 font-semibold text-right">Unique Visitors</th>
                    <th className="p-4 font-semibold">Created Date</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#374151]/50 bg-[#1F2937]/20">
                  {topUrlsLoading ? (
                    <tr>
                      <td colSpan="5" className="p-8 text-center text-[#6B7280]">
                        <Spinner size="sm" className="mx-auto mb-2" />
                        Fetching top URL entries...
                      </td>
                    </tr>
                  ) : topUrls.length > 0 ? (
                    topUrls.map((url) => (
                      <tr 
                        key={url.shortCode}
                        className="hover:bg-[#1F2937]/50 cursor-pointer transition-colors"
                        onClick={() => {
                          setAnalyticsCode(url.shortCode);
                          setActiveTab('analytics');
                          // Manually fetch analytics for them
                          fetchAnalytics(url.shortCode).then(res => {
                            if (res && res.success) setAnalyticsData(res.data);
                          });
                        }}
                      >
                        <td className="p-4 font-mono font-bold text-[#3B82F6]">{url.shortCode}</td>
                        <td className="p-4 max-w-sm truncate text-[#9CA3AF] hover:text-[#F9FAFB]">{url.originalUrl}</td>
                        <td className="p-4 text-right font-number font-semibold text-[#F9FAFB]">{formatNumber(url.clickCount)}</td>
                        <td className="p-4 text-right font-number text-[#9CA3AF]">{formatNumber(url.uniqueClickCount || 0)}</td>
                        <td className="p-4 text-xs font-mono">{new Date(url.createdAt).toLocaleDateString()}</td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan="5" className="p-8 text-center text-[#6B7280]">No short URLs recorded in the cluster database yet.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        )}
      </div>

      {/* ⚡ Live Redirect Demo Section */}
      <Card title="Live Resolution & Caching Demo" subtitle="Resolve code to test redirection speed, memory lookup, and database safety bypass.">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4">
          <form onSubmit={handleTestRedirect} className="space-y-4">
            <Input
              placeholder="Short Code (e.g. abc1234)"
              value={demoCode}
              onChange={(e) => setDemoCode(e.target.value)}
              icon={Play}
              required
            />
            <Button
              type="submit"
              variant="outline"
              className="w-full text-center hover:bg-[#3B82F6] hover:text-white transition-all h-10"
              loading={demoLoading}
            >
              Test Redirect
            </Button>
          </form>

          {/* Flash Outcome Window */}
          <div className="relative overflow-hidden rounded-lg min-h-[120px] flex items-center justify-center p-6 border border-[#374151]">
            {/* Flash Background Effect */}
            <div
              className={`absolute inset-0 z-0 transition-opacity duration-700 opacity-0 pointer-events-none ${
                demoFlash === 'HIT' ? 'bg-[#10B981]/20 opacity-100' : ''
              } ${
                demoFlash === 'MISS' ? 'bg-[#F59E0B]/20 opacity-100' : ''
              }`}
            />
            
            <div className="z-10 text-center w-full">
              {demoResult ? (
                demoResult.error ? (
                  <div className="text-[#EF4444] text-sm font-semibold flex items-center gap-1 justify-center">
                    <AlertCircle size={16} />
                    {demoResult.error}
                  </div>
                ) : (
                  <div className="space-y-3">
                    <div className="flex justify-between items-center text-xs border-b border-[#374151]/30 pb-1.5">
                      <span className="text-[#9CA3AF]">Target URL</span>
                      <span className="font-semibold text-[#F9FAFB] max-w-[200px] truncate">{demoResult.originalUrl}</span>
                    </div>
                    
                    <div className="flex justify-between items-center text-xs border-b border-[#374151]/30 pb-1.5">
                      <span className="text-[#9CA3AF]">Resolution Time</span>
                      <span className="font-mono text-[#F9FAFB] font-semibold">{formatDuration(demoResult.responseTime)}</span>
                    </div>

                    <div className="flex justify-between items-center text-xs">
                      <span className="text-[#9CA3AF]">Memory Cache Check</span>
                      <Badge variant={demoResult.cacheHit === 'HIT' ? 'SAFE' : 'WARNING'}>
                        {demoResult.cacheHit === 'HIT' ? 'CACHE HIT' : 'CACHE MISS'}
                      </Badge>
                    </div>
                  </div>
                )
              ) : (
                <div className="text-sm text-[#6B7280] font-medium uppercase tracking-wide">
                  Redirection results will show here
                </div>
              )}
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
};

export default UrlShortener;
