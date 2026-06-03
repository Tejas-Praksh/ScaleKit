/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        'bg-primary': '#0A0E1A',
        'bg-secondary': '#111827',
        'bg-card': '#1F2937',
        'bg-card-hover': '#374151',
        'accent-blue': '#3B82F6',
        'accent-green': '#10B981',
        'accent-amber': '#F59E0B',
        'accent-red': '#EF4444',
        'accent-purple': '#8B5CF6',
        'accent-cyan': '#06B6D4',
        'text-primary': '#F9FAFB',
        'text-secondary': '#9CA3AF',
        'text-muted': '#6B7280',
        'border-custom': '#374151',
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', 'monospace'],
        sans: ['Inter', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
