# -- build stage --
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
# Public Turnstile site key is baked into the bundle at build time (not a secret).
ARG VITE_TURNSTILE_SITE_KEY=
ARG VITE_TURNSTILE_REQUIRED=false
ENV VITE_TURNSTILE_SITE_KEY=$VITE_TURNSTILE_SITE_KEY
ENV VITE_TURNSTILE_REQUIRED=$VITE_TURNSTILE_REQUIRED
RUN npm run build

# -- serve stage --
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -qO- http://127.0.0.1:80/ || exit 1
