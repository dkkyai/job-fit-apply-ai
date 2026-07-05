# ── Build stage: produce the static SPA bundle ────────────────────────────────
FROM node:20 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# VITE_API_BASE_URL is baked into the bundle at build time — it's the bridge URL the
# browser calls over the tailnet. .env.production.local guarantees Vite picks it up.
ARG VITE_API_BASE_URL
RUN echo "VITE_API_BASE_URL=${VITE_API_BASE_URL}" > .env.production.local \
    && npm run build

# ── Runtime stage: nginx serving the static files ─────────────────────────────
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 3030
