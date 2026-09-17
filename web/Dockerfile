FROM node:20-alpine AS build
WORKDIR /workspace
COPY web/package.json web/package-lock.json* ./
RUN npm install
COPY web .
ARG VITE_KEYCLOAK_BASE_URL=http://localhost:8180
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_KEYCLOAK_BASE_URL=$VITE_KEYCLOAK_BASE_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
