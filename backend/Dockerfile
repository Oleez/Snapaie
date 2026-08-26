# Small, boring, and pinned. Railway builds this rather than guessing at the repo,
# which matters because the repository root is an Android app and nothing here can
# infer a server from a build.gradle.kts.
FROM node:20-slim AS build

WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --no-audit --no-fund

COPY src ./src
RUN npm run build

FROM node:20-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY --from=build /app/dist ./dist

# Railway injects PORT. Nothing here should hardcode one.
EXPOSE 8080
CMD ["node", "dist/index.js"]
