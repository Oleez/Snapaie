# Builds the Cloud Read service in backend/.
#
# This lives at the repository root rather than beside the code it builds, because
# Railway looks for a Dockerfile in the service's root directory and this repository's
# root is an Android app. Without this, Railway scans the root, finds build.gradle.kts
# and gradlew.bat, and fails with "could not determine how to build the app" — which is
# what it did on every push until this file existed. Setting Root Directory to "backend"
# in the dashboard also works; this makes it unnecessary, so the build cannot break again
# because a setting was lost or a new environment was created without it.
FROM node:20-slim AS build

WORKDIR /app
COPY backend/package.json backend/tsconfig.json ./
RUN npm install --no-audit --no-fund

COPY backend/src ./src
RUN npm run build

FROM node:20-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app

COPY backend/package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY --from=build /app/dist ./dist

# Railway injects PORT; nothing here hardcodes one.
EXPOSE 8080
CMD ["node", "dist/index.js"]
