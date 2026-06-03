import React from 'react';
import { motion } from 'framer-motion';
import clsx from 'clsx';
import { ArrowUpRight, ArrowDownRight } from 'lucide-react';

const StatCard = ({
  value,
  label,
  trend, // 'up' | 'down' | 'neutral'
  trendValue,
  color = 'blue', // 'blue' | 'green' | 'amber' | 'red' | 'purple' | 'cyan'
  icon: Icon,
  loading = false
}) => {
  const colorMap = {
    blue: 'text-[#3B82F6] bg-[#3B82F6]/10 border-[#3B82F6]/30',
    green: 'text-[#10B981] bg-[#10B981]/10 border-[#10B981]/30',
    amber: 'text-[#F59E0B] bg-[#F59E0B]/10 border-[#F59E0B]/30',
    red: 'text-[#EF4444] bg-[#EF4444]/10 border-[#EF4444]/30',
    purple: 'text-[#8B5CF6] bg-[#8B5CF6]/10 border-[#8B5CF6]/30',
    cyan: 'text-[#06B6D4] bg-[#06B6D4]/10 border-[#06B6D4]/30'
  };

  const borderGlowMap = {
    blue: 'hover:border-[#3B82F6]/50',
    green: 'hover:border-[#10B981]/50',
    amber: 'hover:border-[#F59E0B]/50',
    red: 'hover:border-[#EF4444]/50',
    purple: 'hover:border-[#8B5CF6]/50',
    cyan: 'hover:border-[#06B6D4]/50'
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 15 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className={clsx(
        "bg-[#1F2937] rounded-xl border border-[#374151] p-6 shadow-md transition-all duration-300 relative overflow-hidden",
        borderGlowMap[color] || 'hover:border-[#4B5563]'
      )}
    >
      {loading ? (
        <div className="space-y-3">
          <div className="flex justify-between items-center">
            <div className="h-4 w-20 rounded animate-shimmer" />
            <div className="h-8 w-8 rounded-full animate-shimmer" />
          </div>
          <div className="h-8 w-32 rounded animate-shimmer" />
          <div className="h-4 w-24 rounded animate-shimmer" />
        </div>
      ) : (
        <>
          <div className="flex justify-between items-start">
            <span className="text-sm font-medium text-[#9CA3AF] tracking-wide uppercase">{label}</span>
            {Icon && (
              <div className={clsx("p-2 rounded-lg border", colorMap[color] || 'text-[#F9FAFB] bg-[#374151]/50')}>
                <Icon size={18} />
              </div>
            )}
          </div>
          
          <div className="mt-3 flex items-baseline gap-2">
            <motion.span
              initial={{ scale: 0.8 }}
              animate={{ scale: 1 }}
              transition={{ type: "spring", stiffness: 300, damping: 20 }}
              className="text-3xl font-bold text-[#F9FAFB] font-number tracking-tight"
            >
              {value}
            </motion.span>
          </div>

          {(trend || trendValue) && (
            <div className="mt-3 flex items-center gap-1.5 text-xs">
              {trend === 'up' && (
                <span className="flex items-center text-[#10B981] font-medium bg-[#10B981]/10 px-2 py-0.5 rounded">
                  <ArrowUpRight size={14} className="mr-0.5" />
                  {trendValue}
                </span>
              )}
              {trend === 'down' && (
                <span className="flex items-center text-[#EF4444] font-medium bg-[#EF4444]/10 px-2 py-0.5 rounded">
                  <ArrowDownRight size={14} className="mr-0.5" />
                  {trendValue}
                </span>
              )}
              {trend === 'neutral' && (
                <span className="text-[#9CA3AF] bg-[#374151] px-2 py-0.5 rounded">
                  {trendValue}
                </span>
              )}
              <span className="text-[#6B7280]">vs last period</span>
            </div>
          )}
        </>
      )}
    </motion.div>
  );
};

export default StatCard;
