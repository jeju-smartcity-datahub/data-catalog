FROM node@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /build/web
ENV NEXT_TELEMETRY_DISABLED=1 NODE_OPTIONS=--max-old-space-size=1536
COPY web/package.json web/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY web/ ./
COPY scripts/ /build/scripts/
RUN npm run build

FROM node@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32
LABEL org.opencontainers.image.title="D.CATALOG Web"
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --from=build --chown=node:node /build/web/.next/standalone ./
COPY --from=build --chown=node:node /build/web/.next/static ./.next/static
COPY --from=build --chown=node:node /build/web/public ./public
USER node
EXPOSE 3000
CMD ["node","server.js"]
