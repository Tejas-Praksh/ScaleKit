import React, { useState, useEffect, useRef } from 'react';
import {
  ResponsiveContainer,
  LineChart as RechartsLineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip
} from 'recharts';
import usePolling from '../../hooks/usePolling';

const RealTimeChart = ({
  dataKey = 'value',
  color = '#3B82F6',
  label = 'Value',
  pollInterval = 2000,
  fetchData,
  height = 300,
  maxPoints = 30
}) => {
  const [history, setHistory] = useState([]);
  
  // Guard for double poll triggers on fast intervals
  const lastPolledVal = useRef(null);

  const { data } = usePolling(fetchData, pollInterval, !!fetchData);

  useEffect(() => {
    if (data !== null && data !== undefined) {
      // Extract target value
      let val = 0;
      if (typeof data === 'object') {
        val = data[dataKey] !== undefined ? data[dataKey] : (data.value !== undefined ? data.value : 0);
      } else {
        val = Number(data);
      }

      // Format time string
      const time = new Date().toLocaleTimeString([], { 
        hour: '2-digit', 
        minute: '2-digit', 
        second: '2-digit',
        hour12: false
      });

      setHistory((prev) => {
        const next = [...prev, { time, [dataKey]: val }];
        if (next.length > maxPoints) {
          return next.slice(next.length - maxPoints);
        }
        return next;
      });
    }
  }, [data, dataKey, maxPoints]);

  return (
    <div style={{ width: '100%', height }}>
      <ResponsiveContainer>
        <RechartsLineChart
          data={history}
          margin={{ top: 10, right: 10, left: -20, bottom: 5 }}
        >
          <CartesianGrid strokeDasharray="3 3" stroke="#374151" opacity={0.3} />
          <XAxis
            dataKey="time"
            stroke="#9CA3AF"
            fontSize={10}
            tickLine={false}
            axisLine={{ stroke: '#374151' }}
          />
          <YAxis
            stroke="#9CA3AF"
            fontSize={10}
            tickLine={false}
            axisLine={{ stroke: '#374151' }}
            domain={[0, 'auto']}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: '#1F2937',
              borderColor: '#374151',
              borderRadius: '8px',
              color: '#F9FAFB',
              fontSize: '11px',
              boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.5)'
            }}
            itemStyle={{ color: '#F9FAFB' }}
            labelStyle={{ color: '#9CA3AF', fontWeight: 'bold' }}
          />
          <Line
            type="monotone"
            dataKey={dataKey}
            name={label}
            stroke={color}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false} // Turn off animation to make real-time updates smooth
          />
        </RechartsLineChart>
      </ResponsiveContainer>
    </div>
  );
};

export default RealTimeChart;
