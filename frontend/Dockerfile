# syntax=docker/dockerfile:1

# --- Build stage: produce the static Vite bundle ------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

# Vite inlines env vars at build time, so the backend URL must be known here. Pass it with
# --build-arg VITE_API_URL=https://your-api.example.com (defaults to same-origin /api if unset).
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL

COPY . .
RUN npm run build

# --- Runtime stage: serve the bundle with nginx -------------------------------------------
FROM nginx:alpine AS run
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
