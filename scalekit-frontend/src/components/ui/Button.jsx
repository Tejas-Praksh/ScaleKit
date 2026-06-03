import React from 'react';
import clsx from 'clsx';
import Spinner from './Spinner';

const Button = ({
  children,
  onClick,
  type = 'button',
  variant = 'primary', // 'primary' | 'secondary' | 'danger' | 'outline' | 'ghost'
  disabled = false,
  loading = false,
  className,
  ...props
}) => {
  const baseStyle = "inline-flex items-center justify-center font-medium rounded-lg text-sm transition-all duration-200 select-none px-4 py-2 relative overflow-hidden focus:outline-none focus:ring-2 focus:ring-[#3B82F6]/50";
  
  const variants = {
    primary: "bg-[#3B82F6] hover:bg-[#2563EB] text-[#F9FAFB] active:bg-[#1D4ED8] disabled:bg-[#1F2937] disabled:text-[#6B7280] disabled:border-[#374151]",
    secondary: "bg-[#374151] hover:bg-[#4B5563] text-[#F9FAFB] active:bg-[#1F2937] disabled:bg-[#1F2937] disabled:text-[#6B7280]",
    danger: "bg-[#EF4444] hover:bg-[#DC2626] text-[#F9FAFB] active:bg-[#B91C1C] disabled:bg-[#1F2937] disabled:text-[#6B7280]",
    outline: "border border-[#374151] hover:bg-[#374151] hover:text-[#F9FAFB] text-[#9CA3AF] active:bg-[#1F2937] disabled:bg-transparent disabled:text-[#6B7280]",
    ghost: "hover:bg-[#1F2937] text-[#9CA3AF] hover:text-[#F9FAFB] active:bg-[#111827] disabled:bg-transparent disabled:text-[#6B7280]"
  };

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled || loading}
      className={clsx(
        baseStyle,
        variants[variant] || variants.primary,
        loading && "text-transparent hover:text-transparent cursor-not-allowed",
        disabled && "cursor-not-allowed opacity-50",
        className
      )}
      {...props}
    >
      {loading && (
        <div className="absolute inset-0 flex items-center justify-center">
          <Spinner size="sm" />
        </div>
      )}
      {children}
    </button>
  );
};

export default Button;
