export default {
  plugins: {
    "@tailwindcss/postcss": {},
    "autoprefixer": {},
    "postcss-nesting": {},
    ...(process.env.NODE_ENV === 'production'
      ? { "cssnano": { preset: ['default', { discardComments: { removeAll: true } }] } }
      : {})
  }
}
