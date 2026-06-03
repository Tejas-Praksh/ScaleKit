import React from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Link2,
  Gauge,
  Database,
  RefreshCw,
  Sparkles,
  Lock,
  BarChart3,
  X
} from 'lucide-react';
import clsx from 'clsx';

const Sidebar = ({ isOpen, onClose }) => {
  const menuItems = [
    { name: 'Dashboard', path: '/', icon: LayoutDashboard },
    { name: 'URL Shortener', path: '/urls', icon: Link2 },
    { name: 'Rate Limiter', path: '/rate-limiter', icon: Gauge },
    { name: 'Cache Visualizer', path: '/cache', icon: Database },
    { name: 'Hash Ring', path: '/hash-ring', icon: RefreshCw },
    { name: 'Bloom Filter', path: '/bloom-filter', icon: Sparkles },
    { name: 'Distributed Locks', path: '/locks', icon: Lock },
    { name: 'Benchmarks', path: '/benchmarks', icon: BarChart3 },
  ];

  return (
    <>
      {/* Mobile Sidebar Overlay */}
      {isOpen && (
        <div
          onClick={onClose}
          className="fixed inset-0 z-40 bg-black/60 md:hidden backdrop-blur-sm transition-opacity duration-300"
        />
      )}

      {/* Sidebar Panel */}
      <aside
        className={clsx(
          "fixed top-0 bottom-0 left-0 z-50 flex flex-col w-64 bg-[#111827] border-r border-[#374151] transition-transform duration-300 md:translate-x-0",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        {/* Sidebar Header */}
        <div className="flex items-center justify-between h-16 px-6 border-b border-[#374151] bg-[#0A0E1A]/20">
          <div className="flex flex-col">
            <span className="text-xl font-bold tracking-wider text-transparent bg-clip-text bg-gradient-to-r from-[#3B82F6] to-[#06B6D4]">
              ScaleKit
            </span>
            <span className="text-[10px] text-[#6B7280] font-medium uppercase tracking-widest -mt-0.5">
              Distributed Systems Toolkit
            </span>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-[#9CA3AF] hover:text-[#F9FAFB] md:hidden hover:bg-[#1F2937]"
          >
            <X size={20} />
          </button>
        </div>

        {/* Navigation Items */}
        <nav className="flex-1 px-4 py-6 space-y-1.5 overflow-y-auto">
          {menuItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={onClose}
                className={({ isActive }) =>
                  clsx(
                    "flex items-center gap-3 px-4 py-3 text-sm font-medium rounded-lg transition-all duration-200 group relative",
                    isActive
                      ? "text-[#3B82F6] bg-[#3B82F6]/10 border-l-4 border-[#3B82F6] rounded-l-none pl-3"
                      : "text-[#9CA3AF] hover:text-[#F9FAFB] hover:bg-[#1F2937]/50"
                  )
                }
              >
                <Icon size={18} className="flex-shrink-0 group-hover:scale-110 transition-transform duration-200" />
                <span>{item.name}</span>
              </NavLink>
            );
          })}
        </nav>

        {/* Sidebar Footer */}
        <div className="p-4 border-t border-[#374151] text-center bg-[#0A0E1A]/10">
          <p className="text-xs text-[#6B7280] font-mono">v1.0.0 | JDK 21</p>
        </div>
      </aside>
    </>
  );
};

export default Sidebar;
