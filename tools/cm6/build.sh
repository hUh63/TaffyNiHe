set -e
npx esbuild src/main.js --bundle --minify --format=iife --target=es2019 \
  --outfile=dist/cm.js --legal-comments=none
