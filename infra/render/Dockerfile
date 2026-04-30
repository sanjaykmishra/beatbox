# Build context: repository root.
#   docker build -f infra/render/Dockerfile -t beat-render .
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY render/package.json render/package-lock.json* ./
RUN npm ci --ignore-scripts
COPY render/tsconfig.json ./
COPY render/src ./src
RUN npm run build

FROM node:22-bookworm-slim
ENV PUPPETEER_SKIP_DOWNLOAD=false
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium fonts-liberation libnss3 libatk-bridge2.0-0 libxkbcommon0 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libpangocairo-1.0-0 libasound2 \
  && rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY render/package.json ./
RUN npm ci --omit=dev --ignore-scripts
EXPOSE 3000
CMD ["node","dist/server.js"]
