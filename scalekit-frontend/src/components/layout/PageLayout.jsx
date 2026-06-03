import React, { useState } from 'react';
import Sidebar from './Sidebar';
import TopBar from './TopBar';

const PageLayout = ({ children, alertsCount = 0, isConnected = true }) => {
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);

  return (
    <div className="min-h-screen bg-[#0A0E1A] text-[#F9FAFB] flex">
      {/* Sidebar Navigation */}
      <Sidebar isOpen={isSidebarOpen} onClose={() => setIsSidebarOpen(false)} />

      {/* Main Area */}
      <div className="flex-1 flex flex-col md:pl-64 min-w-0 transition-all duration-300">
        {/* Top Header Bar */}
        <TopBar
          onMenuClick={() => setIsSidebarOpen(true)}
          alertsCount={alertsCount}
          isConnected={isConnected}
        />

        {/* Dynamic Content Page */}
        <main className="flex-1 p-6 md:p-8 overflow-y-auto max-w-7xl w-full mx-auto">
          {children}
        </main>
      </div>
    </div>
  );
};

export default PageLayout;
