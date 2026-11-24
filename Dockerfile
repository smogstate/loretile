# Loretile served by itself: the app with the server's api/ (server/).
#   docker build -t loretile .
#   docker run -p 8080:8080 -e LORETILE_CONFIG=s3://bucket/loretile.edn loretile
# Every setting is in server/config.example.edn; each can also be set from
# the environment (llm.token-ttl-ms → LLM_TOKEN_TTL_MS).
FROM node:26-trixie-slim AS node

FROM clojure:temurin-25-tools-deps-trixie-slim AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && apt-get update && apt-get install -y --no-install-recommends make zip libatomic1 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src

COPY server/deps.edn server/
RUN cd server && clojure -P

COPY package.json package-lock.json ./
RUN npm ci

COPY shadow-cljs.edn Makefile ./
COPY src src
COPY public public
COPY kg/demo kg/demo
RUN make dist VERSION=app && mv dist/loretile-app-app /out-public

# the server loads the shared cljc from ../src (fold, graph-store, schema,
# profile) and hashes four of them at start (a changed fold rebuilds the
# projection) — so those sources ship beside the classes, older than them
COPY server server
RUN cd server && mkdir -p /out/lib /out/classes/loretile \
 && for j in $(clojure -Spath | tr ':' '\n' | grep '\.jar$'); do cp "$j" /out/lib/; done \
 && java -cp "/out/classes:$(clojure -Spath)" -Dclojure.compile.path=/out/classes \
      clojure.main -e "(compile 'loretile.server.main)" \
 && cp -p ../src/loretile/fold.cljc ../src/loretile/schema.cljc ../src/loretile/profile.cljc \
      ../src/loretile/graph_store.cljc /out/classes/loretile/

FROM eclipse-temurin:25-jre
RUN useradd --system --home /app loretile
WORKDIR /app
COPY --from=build /out/lib lib
COPY --from=build /out/classes classes
COPY --from=build /out-public public
USER loretile
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-cp", "classes:lib/*", "loretile.server.main"]
