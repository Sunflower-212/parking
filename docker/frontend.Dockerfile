FROM node:22.15.0-alpine AS build
ARG VITE_APP_BASE=/
ENV VITE_APP_BASE=${VITE_APP_BASE}
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM nginx:1.28.0-alpine
COPY docker/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
