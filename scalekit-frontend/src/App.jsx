import React, { useState, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import PageLayout from './components/layout/PageLayout';
import Dashboard from './pages/Dashboard';
import UrlShortener from './pages/UrlShortener';
import RateLimiter from './pages/RateLimiter';
import CacheVisualizer from './pages/CacheVisualizer';
import HashRing from './pages/HashRing';
import BloomFilter from './pages/BloomFilter';
import Locks from './pages/Locks';
import Benchmarks from './pages/Benchmarks';
import { apiService } from './services/api';

function App() {
  const [isConnected, setIsConnected] = useState(true);
  const [alertsCount, setAlertsCount] = useState(0);

  // Background status check for global TopBar health summary
  useEffect(() => {
    let failCount = 0;
    const MAX_FAILS_BEFORE_OFFLINE = 3; // tolerate 3 failures (cold start grace)

    const checkHealth = async () => {
      try {
        const res = await apiService.getDashboard();
        failCount = 0;
        setIsConnected(true);
        setAlertsCount(res.data?.data?.activeAlerts?.length || 0);
      } catch (err) {
        failCount++;
        // Only mark offline after multiple consecutive failures
        // This prevents false "OFFLINE" during Render cold starts
        if (failCount >= MAX_FAILS_BEFORE_OFFLINE) {
          setIsConnected(false);
        }
        setAlertsCount(0);
      }
    };
    checkHealth();
    const id = setInterval(checkHealth, 30000); // every 30s instead of 5s
    return () => clearInterval(id);
  }, []);

  return (
    <Router>
      <Toaster 
        position="top-right" 
        toastOptions={{
          style: {
            background: '#1F2937',
            color: '#F9FAFB',
            borderColor: '#374151',
          }
        }} 
      />
      <PageLayout alertsCount={alertsCount} isConnected={isConnected}>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/urls" element={<UrlShortener />} />
          <Route path="/rate-limiter" element={<RateLimiter />} />
          <Route path="/cache" element={<CacheVisualizer />} />
          <Route path="/hash-ring" element={<HashRing />} />
          <Route path="/bloom-filter" element={<BloomFilter />} />
          <Route path="/locks" element={<Locks />} />
          <Route path="/benchmarks" element={<Benchmarks />} />
        </Routes>
      </PageLayout>
    </Router>
  );
}

export default App;
