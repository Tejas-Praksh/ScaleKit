import React from 'react';
import { Menu, Wifi, WifiOff, Bell, BellRing } from 'lucide-react';
import { useLocation } from 'react-router-dom';
import clsx from 'clsx';

const TopBar = ({ onMenuClick, alertsCount = 0, isConnected = true }) => {
  const location = useLocation();
  
  const titleMap = {
    '/': 'Dashboard Overview',
    '/urls': 'URL Shortener & Safety Analytics',
    '/rate-limiter': 'Rate Limiter Visualization',
    '/cache': 'Cache Strategy Visualizer',
    '/hash-ring': 'Consistent Hashing Ring',
    '/bloom-filter': 'Probabilistic Bloom Filter',
    '/locks': 'Distributed Redlock Manager',
    '/benchmarks': 'Load & Benchmark Testing',
  };

  const currentTitle = titleMap[location.pathname] || 'ScaleKit Toolkit';

  return (
    <header className="h-16 bg-[#111827] border-b border-[#374151] flex items-center justify-between px-6 sticky top-0 z-30">
      <div className="flex items-center gap-4">
        {/* Burger Button */}
        <button
          onClick={onMenuClick}
          className="p-2 rounded-lg text-[#9CA3AF] hover:text-[#F9FAFB] hover:bg-[#1F2937] md:hidden focus:outline-none"
        >
          <Menu size={20} />
        </button>
        <h1 className="text-lg font-semibold text-[#F9FAFB] tracking-tight transition-all duration-300">
          {currentTitle}
        </h1>
      </div>

      <div className="flex items-center gap-4">
        {/* Backend Connectivity Status */}
        <div
          className={clsx(
            "flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium border transition-all duration-300",
            isConnected 
              ? "bg-[#10B981]/10 text-[#10B981] border-[#10B981]/20" 
              : "bg-[#F59E0B]/10 text-[#F59E0B] border-[#F59E0B]/20"
          )}
        >
          {isConnected ? (
            <>
              <Wifi size={14} className="animate-pulse" />
              <span>CONNECTED</span>
            </>
          ) : (
            <>
              <WifiOff size={14} className="animate-pulse" />
              <span>CONNECTING...</span>
            </>
          )}
        </div>

        {/* Alerts Notification Bell */}
        <div className="relative">
          <div
            className={clsx(
              "p-2 rounded-lg border text-[#9CA3AF] transition-all duration-200",
              alertsCount > 0 
                ? "bg-[#EF4444]/10 border-[#EF4444]/20 text-[#EF4444]" 
                : "border-[#374151] hover:bg-[#1F2937] hover:text-[#F9FAFB]"
            )}
          >
            {alertsCount > 0 ? (
              <BellRing size={16} className="animate-bounce" />
            ) : (
              <Bell size={16} />
            )}
          </div>
          {alertsCount > 0 && (
            <span className="absolute top-0 right-0 block h-2 w-2 rounded-full bg-[#EF4444] ring-2 ring-[#111827]" />
          )}
        </div>
      </div>
    </header>
  );
};

export default TopBar;
