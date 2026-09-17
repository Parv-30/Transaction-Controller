/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        surface: {
          light: '#ffffff',
          'light-alt': '#f7f7f9',
          dark: '#0a0a0f',
          'dark-alt': '#17171f',
        },
        ink: {
          light: '#0b0e14',
          dark: '#e7e8ec',
        },
        accent: {
          DEFAULT: '#4f46e5',
          hover: '#4338ca',
        },
        danger: '#dc2626',
        warning: '#d97706',
        success: '#16a34a',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        card: '1rem',
      },
    },
  },
  plugins: [],
};
