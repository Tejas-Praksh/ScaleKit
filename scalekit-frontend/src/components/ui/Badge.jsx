import React from 'react';
import clsx from 'clsx';

const Badge = ({ variant = 'INFO', children, className }) => {
  const badgeStyles = {
    SAFE: 'bg-[#10B981]/15 text-[#10B981] border-[#10B981]/30',
    WARNING: 'bg-[#F59E0B]/15 text-[#F59E0B] border-[#F59E0B]/30',
    DANGER: 'bg-[#EF4444]/15 text-[#EF4444] border-[#EF4444]/30',
    INFO: 'bg-[#3B82F6]/15 text-[#3B82F6] border-[#3B82F6]/30',
    PURPLE: 'bg-[#8B5CF6]/15 text-[#8B5CF6] border-[#8B5CF6]/30',
    CYAN: 'bg-[#06B6D4]/15 text-[#06B6D4] border-[#06B6D4]/30',
    MUTED: 'bg-[#6B7280]/15 text-[#9CA3AF] border-[#6B7280]/30',
  };

  return (
    <span
      className={clsx(
        "inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold border tracking-wide uppercase",
        badgeStyles[variant] || badgeStyles.INFO,
        className
      )}
    >
      {children}
    </span>
  );
};

export default Badge;
