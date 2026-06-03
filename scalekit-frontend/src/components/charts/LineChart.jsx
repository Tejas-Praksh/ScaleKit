import React from 'react';
import {
  ResponsiveContainer,
  LineChart as RechartsLineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend
} from 'recharts';

const LineChart = ({
  data,
  xAxisKey = 'name',
  series = [], // array of objects: { key, color, name }
  height = 300,
  grid = true,
}) => {
  return (
    <div style={{ width: '100%', height }}>
      <ResponsiveContainer>
        <RechartsLineChart
          data={data}
          margin={{ top: 10, right: 10, left: -20, bottom: 5 }}
        >
          {grid && <CartesianGrid strokeDasharray="3 3" stroke="#374151" opacity={0.3} />}
          <XAxis
            dataKey={xAxisKey}
            stroke="#9CA3AF"
            fontSize={11}
            tickLine={false}
            axisLine={{ stroke: '#374151' }}
          />
          <YAxis
            stroke="#9CA3AF"
            fontSize={11}
            tickLine={false}
            axisLine={{ stroke: '#374151' }}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: '#1F2937',
              borderColor: '#374151',
              borderRadius: '8px',
              color: '#F9FAFB',
              fontSize: '12px',
              boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.5)'
            }}
            itemStyle={{ color: '#F9FAFB' }}
            labelStyle={{ color: '#9CA3AF', fontWeight: 'bold' }}
          />
          {series.length > 1 && <Legend verticalAlign="top" height={36} wrapperStyle={{ fontSize: '11px', color: '#9CA3AF' }} />}
          {series.map((s, idx) => (
            <Line
              key={s.key}
              type="monotone"
              dataKey={s.key}
              name={s.name || s.key}
              stroke={s.color || '#3B82F6'}
              strokeWidth={2}
              dot={{ r: 2, strokeWidth: 1 }}
              activeDot={{ r: 6 }}
              animationDuration={500}
            />
          ))}
        </RechartsLineChart>
      </ResponsiveContainer>
    </div>
  );
};

export default LineChart;
