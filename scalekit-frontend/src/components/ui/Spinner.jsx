import React from 'react';
import clsx from 'clsx';

const Spinner = ({ size = 'md', className }) => {
  const sizeMap = {
    sm: 'h-4 w-4 border-2',
    md: 'h-8 w-8 border-3',
    lg: 'h-12 w-12 border-4'
  };

  return (
    <div className="flex items-center justify-center">
      <div
        className={clsx(
          "animate-spin rounded-full border-t-transparent border-[#3B82F6] ease-linear",
          sizeMap[size] || sizeMap.md,
          className
        )}
      />
    </div>
  );
};

export default Spinner;
