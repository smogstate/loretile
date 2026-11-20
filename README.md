# Loretile

A learning platform built on a knowledge graph: small learning objects, a
per-learner mastery model, and an AI tutor that works on top of the graph.
It also works as a graph tool for any ontology. Runs entirely in the browser
with your own provider key, or behind its own server with a shared LLM gateway.

Live: https://loretile.com

## Quick start

Loretile runs two ways: as static files in the browser, where each user
brings their own model key, or as a Docker container whose server holds the
LLM gateway and its token for everyone.

### Browser only

Download `loretile-app-<version>.tar.gz` from
[Releases](https://github.com/smogstate/loretile/releases) and serve it with
any static web server:

    tar -xzf loretile-app-<version>.tar.gz
    cd loretile-app-<version>
    python3 -m http.server 8000

Open http://localhost:8000/ and enter a provider key under Settings →
Assistant. The `-enterprise` package is the same app without the demo graphs.

### Docker, with a backend

    docker run -p 8080:8080 \
      -e LLM_URL=https://api.deepseek.com/chat/completions \
      -e LLM_MODEL=deepseek-chat \
      -e LLM_TOKEN=sk-... \
      ghcr.io/smogstate/loretile:latest

Open http://localhost:8080/. The browser calls the server's `api/`, which
adds the token; users never see a key. There is no login: run it behind
your auth proxy.

For more than a few settings, put them in one EDN file and point
`LORETILE_CONFIG` at it, as `file:///…` (mount it into the container) or
`s3://bucket/key`. It is re-read every 10 seconds. Every key in it can be
overridden from the environment: `[:llm :token-ttl-ms]` is
`LLM_TOKEN_TTL_MS`. All settings, including a token provider function, S3
merge files and S3-compatible endpoints, are in
[server/config.example.edn](server/config.example.edn).

    docker run -p 8080:8080 \
      -v /etc/loretile:/etc/loretile:ro \
      -e LORETILE_CONFIG=file:///etc/loretile/config.edn \
      ghcr.io/smogstate/loretile:latest

## Build

Needs Node 26 and Java 25 (shadow-cljs and the server).

    make dev                      # local dev server
    make test                     # unit tests + codebase-graph check
    make server CONFIG=file:///…  # the app behind the server, locally
    make docker                   # the Docker image

Comments in the code cite `wiki/*.md` pages. Those design notes are not
published.

## Licence

Copyright © 2026 smogstate. Licensed under the GNU Affero General Public
License v3.0 or later — see [LICENSE](LICENSE). Third-party files are listed
in [THIRD_PARTY.md](THIRD_PARTY.md).

Additional permission under GNU AGPL version 3 section 7: if you modify this
Program, or any covered work, by linking or combining it with ClojureScript,
malli, or any other library licensed under the Eclipse Public License 1.0 or
2.0 (or a modified version of such a library), containing parts covered by
the terms of the Eclipse Public License, the licensors of this Program grant
you additional permission to convey the resulting work. Corresponding Source
for a non-source form of such a combination shall include the source code for
the parts of those libraries used as well as that of the covered work.
