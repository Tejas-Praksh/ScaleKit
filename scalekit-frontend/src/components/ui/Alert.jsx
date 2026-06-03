import React from 'react';
import clsx from 'clsx';
import { AlertCircle, CheckCircle, Info, X } from 'lucide-react';

const Alert = ({
  type = 'info', // 'info' | 'warning' | 'error' | 'success'
  message,
  children,
  className,
  onClose
}) => {
  const styles = {
    info: 'bg-[#3B82F6]/10 border-[#3B82F6]/30 text-[#3B82F6]',
    warning: 'bg-[#F59E0B]/10 border-[#F59E0B]/30 text-[#F59E0B]',
    error: 'bg-[#EF4444]/10 border-[#EF4444]/30 text-[#EF4444]',
    success: 'bg-[#10B981]/10 border-[#10B981]/30 text-[#10B981]'
  };

  const icons = {
    info: <Info size={18} className="flex-shrink-0" />,
    warning: <AlertCircle size={18} className="flex-shrink-0" />,
    error: <AlertCircle size={18} className="flex-shrink-0" />,
    success: <CheckCircle size={18} className="flex-shrink-0" />
  };

  return (
    <div
      className={clsx(
        "flex items-start gap-3 p-4 rounded-lg border text-sm relative",
        styles[type] || styles.info,
        className
      )}
    >
      {icons[type]}
      <div className="flex-1">
        {message && <div className="font-semibold">{message}</div>}
        {children && <div className={clsx(message && "mt-1")}>{children}</div>}
      </div>
      {onClose && (
        <button
          onClick={onClose}
          className="text-[#9CA3AF] hover:text-[#F9FAFB] transition-all"
        >
          <X size={16} />
        </button>
      )}
    </div>
  );
};

export default Alert;
