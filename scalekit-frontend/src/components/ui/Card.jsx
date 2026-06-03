import React from 'react';
import { motion } from 'framer-motion';
import clsx from 'clsx';

const Card = ({
  children,
  title,
  subtitle,
  className,
  hoverable = false,
  loading = false
}) => {
  return (
    <motion.div
      initial={{ opacity: 0, y: 15 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className={clsx(
        "bg-[#1F2937] rounded-xl border border-[#374151] p-6 overflow-hidden relative",
        hoverable && "hover:border-[#4B5563] hover:bg-[#252F3F] transition-all duration-300 shadow-lg",
        className
      )}
    >
      {loading ? (
        <div className="space-y-4">
          <div className="h-6 w-1/3 rounded animate-shimmer" />
          <div className="h-4 w-1/4 rounded animate-shimmer" />
          <div className="space-y-2 pt-4">
            <div className="h-4 w-full rounded animate-shimmer" />
            <div className="h-4 w-full rounded animate-shimmer" />
            <div className="h-4 w-5/6 rounded animate-shimmer" />
          </div>
        </div>
      ) : (
        <>
          {(title || subtitle) && (
            <div className="mb-4">
              {title && <h3 className="text-lg font-semibold text-[#F9FAFB] tracking-tight">{title}</h3>}
              {subtitle && <p className="text-sm text-[#9CA3AF] mt-0.5">{subtitle}</p>}
            </div>
          )}
          {children}
        </>
      )}
    </motion.div>
  );
};

export default Card;
