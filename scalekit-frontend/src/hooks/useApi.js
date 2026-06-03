import { useState, useCallback, useEffect, useRef } from 'react';

const useApi = (apiFunc, options = {}) => {
  const { autoExecute = false, retries = 0, retryDelay = 1000 } = options;
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(autoExecute);
  const [error, setError] = useState(null);
  
  const isMounted = useRef(true);
  const activeRequest = useRef(null);

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
      if (activeRequest.current && typeof activeRequest.current.cancel === 'function') {
        activeRequest.current.cancel('Component unmounted');
      }
    };
  }, []);

  const execute = useCallback(async (...args) => {
    setLoading(true);
    setError(null);

    let attempt = 0;
    const run = async () => {
      try {
        const response = await apiFunc(...args);
        if (isMounted.current) {
          setData(response.data);
          setLoading(false);
          return response.data;
        }
      } catch (err) {
        if (attempt < retries && isMounted.current) {
          attempt++;
          await new Promise((resolve) => setTimeout(resolve, retryDelay));
          return run();
        }
        if (isMounted.current) {
          setError(err.response?.data || err.message || err);
          setLoading(false);
          throw err;
        }
      }
    };

    return run();
  }, [apiFunc, retries, retryDelay]);

  useEffect(() => {
    if (autoExecute) {
      execute();
    }
  }, [autoExecute, execute]);

  return { data, loading, error, execute };
};

export default useApi;
