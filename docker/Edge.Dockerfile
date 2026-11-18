FROM node@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /build
COPY admin/package.json admin/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY admin/ ./
ENV VITE_BASE_PATH=/admin/ VITE_PUBLIC_CATALOG_URL=/
RUN npm run build

FROM nginx@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de
LABEL org.opencontainers.image.title="D.CATALOG Admin"
COPY --from=build /build/dist/ /usr/share/nginx/html/admin/
COPY --chmod=0555 infra/site/catalog-nginx-entrypoint.sh /usr/local/bin/catalog-nginx-entrypoint.sh
COPY --chmod=0444 infra/site/catalog-nginx-error-filter.awk /usr/local/lib/catalog-nginx-error-filter.awk
RUN sed -i 's/\r$//' /usr/local/bin/catalog-nginx-entrypoint.sh /usr/local/lib/catalog-nginx-error-filter.awk
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/catalog-nginx-entrypoint.sh"]
CMD ["nginx","-g","daemon off;"]
