import { formatDistanceToNow } from 'date-fns';

export const formatNumber = (n) => {
  if (n === null || n === undefined || isNaN(n)) return '0';
  return new Intl.NumberFormat().format(n);
};

export const formatBytes = (b) => {
  if (b === null || b === undefined || isNaN(b)) return '0 B';
  if (b === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(b) / Math.log(k));
  return parseFloat((b / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

export const formatDuration = (ms) => {
  if (ms === null || ms === undefined || isNaN(ms)) return '0ms';
  if (ms >= 1000) {
    return (ms / 1000).toFixed(2) + 's';
  }
  return ms.toFixed(2) + 'ms';
};

export const formatPercent = (n) => {
  if (n === null || n === undefined || isNaN(n)) return '0.0%';
  // If the percentage is passed as a decimal between 0 and 1, convert it, otherwise keep it
  const val = n <= 1.0 && n > 0.0 ? n * 100 : n;
  return val.toFixed(1) + '%';
};

export const formatUrl = (url, maxLength = 40) => {
  if (!url) return '';
  if (url.length <= maxLength) return url;
  return url.substring(0, maxLength) + '...';
};

export const timeAgo = (date) => {
  if (!date) return '';
  const parsedDate = typeof date === 'string' || typeof date === 'number' ? new Date(date) : date;
  try {
    return formatDistanceToNow(parsedDate, { addSuffix: true });
  } catch (e) {
    return '';
  }
};

export const formatQPS = (n) => {
  if (n === null || n === undefined || isNaN(n)) return '0 req/s';
  return formatNumber(Math.round(n)) + ' req/s';
};
