module.exports = {
  content: [
    "./src/**/*.{clj,cljs,cljc}",
    "./resources/**/*.{clj,html,css}"
  ],
  theme: {
    extend: {
      colors: {
        'bg': 'var(--color-bg)',
        'bg-secondary': 'var(--color-bg-secondary)',
        'text': 'var(--color-text)',
        'text-subdued': 'var(--color-text-subdued)',
        'primary': 'var(--color-primary)',
        'secondary': 'var(--color-secondary)',
        'tertiary': 'var(--color-tertiary)',
        'callout': 'var(--color-callout)',
        'input-border': 'var(--color-input-border)',
      }
    },
  },
  safelist: [
    'manager',
    'exile',
    'firefighter'
  ],
  plugins: [],
}
