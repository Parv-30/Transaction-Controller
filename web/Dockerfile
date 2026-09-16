FROM node:20-alpine AS build
WORKDIR /workspace
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
