# Builds this service with the build context set to backend/.
#
# There are deliberately two Dockerfiles for one service: this one, and an identical
# build at the repository root whose paths are prefixed with backend/. That looks like
# duplication and is insurance. Railway looks for a Dockerfile in the service's Root
# Directory, and this repository has two defensible answers for what that should be —
# empty, because the repo root is what GitHub connected, or backend/, because that is
# where the server lives. Having only one of the two meant whichever setting the
# dashboard happened to hold decided whether the build worked at all, and a setting
# that has already been changed twice is not something to hang a deploy on.
#
# If you edit one, edit the other. They differ only in the COPY paths.
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

# Railway injects PORT; nothing here hardcodes one.
EXPOSE 8080
CMD ["node", "dist/index.js"]
