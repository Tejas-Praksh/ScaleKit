import { useState, useCallback } from 'react';

const useRealTime = (maxPoints = 30) => {
  const [points, setPoints] = useState([]);

  const addPoint = useCallback((newPoint) => {
    setPoints((prev) => {
      const next = [...prev, newPoint];
      if (next.length > maxPoints) {
        return next.slice(next.length - maxPoints);
      }
      return next;
    });
  }, [maxPoints]);

  const clear = useCallback(() => setPoints([]), []);

  return { points, addPoint, clear, setPoints };
};

export default useRealTime;
