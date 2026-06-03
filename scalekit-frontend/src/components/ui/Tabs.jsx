import React from 'react';
import clsx from 'clsx';

const Tabs = ({ tabs, activeTab, onChange, className }) => {
  return (
    <div className={clsx("border-b border-[#374151] flex gap-6", className)}>
      {tabs.map((tab) => {
        const isActive = activeTab === tab.id;
        return (
          <button
            key={tab.id}
            onClick={() => onChange(tab.id)}
            className={clsx(
              "pb-3 text-sm font-medium border-b-2 transition-all duration-200 focus:outline-none relative -mb-[2px]",
              isActive
                ? "border-[#3B82F6] text-[#3B82F6]"
                : "border-transparent text-[#9CA3AF] hover:text-[#F9FAFB]"
            )}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
};

export default Tabs;
