import React from 'react';
import clsx from 'clsx';

const Input = React.forwardRef(({
  type = 'text',
  value,
  onChange,
  placeholder,
  disabled = false,
  error,
  label,
  className,
  icon: Icon,
  ...props
}, ref) => {
  return (
    <div className={clsx("w-full space-y-1.5", className)}>
      {label && (
        <label className="block text-sm font-medium text-[#9CA3AF]">
          {label}
        </label>
      )}
      <div className="relative rounded-lg shadow-sm">
        {Icon && (
          <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-[#6B7280]">
            <Icon size={16} />
          </div>
        )}
        <input
          ref={ref}
          type={type}
          value={value}
          onChange={onChange}
          disabled={disabled}
          placeholder={placeholder}
          className={clsx(
            "block w-full bg-[#111827] text-[#F9FAFB] border rounded-lg py-2 text-sm focus:outline-none transition-all duration-200",
            Icon ? "pl-10 pr-3" : "px-3",
            error
              ? "border-[#EF4444] focus:border-[#EF4444] focus:ring-1 focus:ring-[#EF4444]"
              : "border-[#374151] focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6]",
            disabled && "opacity-50 cursor-not-allowed bg-[#1F2937]/50",
          )}
          {...props}
        />
      </div>
      {error && (
        <p className="text-xs text-[#EF4444] font-medium mt-1">{error}</p>
      )}
    </div>
  );
});

Input.displayName = 'Input';

export default Input;
