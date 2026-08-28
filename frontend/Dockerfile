# OpenForge 前端：构建 + Nginx 托管（/api 反代网关）
FROM node:20-alpine AS build
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci || npm install
COPY frontend/ .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
