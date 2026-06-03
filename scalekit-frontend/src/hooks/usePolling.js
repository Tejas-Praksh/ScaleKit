import { useState, useEffect, useCallback, useRef } from 'react';

const usePolling = (fetchFn, interval = 2000, enabled = true) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  
  const fetchFnRef = useRef(fetchFn);
  
  useEffect(() => {
    fetchFnRef.current = fetchFn;
  }, [fetchFn]);

  const poll = useCallback(async () => {
    try {
      const result = await fetchFnRef.current();
      setData(result);
      setError(null);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled) return;

    poll(); // immediate first call
    const id = setInterval(poll, interval);
    return () => clearInterval(id);
  }, [interval, enabled, poll]);

  return { data, loading, error, refresh: poll };
};

export default usePolling;
