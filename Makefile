.PHONY: install engines dev release test checks mcp server docker smoke dist dist-enterprise smoke-enterprise check-landing clean-tree deploy deploy-landing cert-status publish

# loretile.com — Object Storage website bucket behind Yandex Cloud DNS
# (zone loretile-com: ANAME apex → bucket, _acme-challenge CNAMEs → managed
# cert loretile-com covering apex + www). www.loretile.com is a second
# bucket whose only job is a 301 to the apex — deploy ONLY here.
# Landing at the root, the app under /app/ (relative asset paths, so the
# same public/ also serves at / in dev). Same shape as kintale.family.
BUCKET := loretile.com
ENDPOINT := https://storage.yandexcloud.net/$(BUCKET)

# Version stamp = git short hash. Also the cache-bust token for main.js,
# which is cached for a year — so a deploy must BE that commit: clean-tree
# refuses a dirty tree (the browser checks' screenshots are gitignored), and
# deploying without a new commit re-serves the old bundle, so commit first.
GIT_COMMIT := $(shell git log -1 --format='%h' 2>/dev/null || echo "dev")

clean-tree:
	@git diff --quiet HEAD -- . || \
		(echo "Deploy refused — uncommitted changes; commit first:" && git status --short -- . && exit 1)

install:
	npm install

# Exercise runtimes (wiki/Exercises.md), served from js/ like pdf.js but
# copied from node_modules rather than committed (~30 MB of wasm):
# PGlite (real Postgres) finds its .wasm/.data next to index.js; Pyodide
# (Python) is loaded by the committed worker js/pyrunner.js from js/pyodide/.
PGLITE := node_modules/@electric-sql/pglite/dist
PYODIDE := node_modules/pyodide
engines: install
	@mkdir -p public/js/pglite public/js/pyodide
	@cp $(PGLITE)/*.js $(PGLITE)/*.wasm $(PGLITE)/pglite.data public/js/pglite/
	@cp $(PYODIDE)/pyodide.mjs $(PYODIDE)/pyodide.asm.mjs $(PYODIDE)/pyodide.asm.wasm \
	    $(PYODIDE)/python_stdlib.zip $(PYODIDE)/pyodide-lock.json public/js/pyodide/

dev: engines
	npx shadow-cljs watch app

release: engines
	npx shadow-cljs release app

test:
	npx shadow-cljs compile test && node out/test.js
	@node test/i18n-check.js
	@python3 kg/loretile/build.py --check

# every browser check, in parallel (J at a time; ~20 min serial -> a few) —
# public/ served on :8291 first (`npm run dev` or `python3 -m http.server 8291 -d public`);
# one log per check in out/checks/, the summary in out/checks/summary.txt
J ?= 8
checks:
	@mkdir -p out/checks
	@curl -sf -o /dev/null http://localhost:8291/ || { echo "serve public/ on :8291 first"; exit 1; }
	@ls test/*-check.js | grep -v -e i18n-check -e mcp-check -e landing-check \
	  | xargs -P $(J) -I{} sh -c 'n=$$(basename {} -check.js); if CLJ_CONFIG=$$(mktemp -d) timeout 600 node {} > out/checks/$$n.log 2>&1; then echo "OK   $$n"; else echo "FAIL $$n"; fi' \
	  | tee out/checks/summary.txt
	@! grep -q FAIL out/checks/summary.txt

# the MCP server over a log.edn — `claude mcp add loretile -- node $(PWD)/out/mcp.js /path/to/log.edn`
mcp:
	npx shadow-cljs release mcp
	@node test/mcp-check.js

# The Loretile server (server/, wiki: CLAUDE.md § Release and deploy) over
# public/ — CONFIG=file:///path/config.edn or s3://…; settings also from env
server: release
	cd server && SERVER_PUBLIC_DIR=$(CURDIR)/public LORETILE_CONFIG=$(CONFIG) clojure -M -m loretile.server.main

# The same as one image: the app with its server, run with a config
docker:
	docker build -t loretile:$(VERSION) .

# The app as one zip — exactly the files `make deploy` uploads (bundle, css,
# pdf.js, both runners, the PGlite + Pyodide engines, the nine demo logs,
# index.html), unhashed and uncompressed, so any static server can host it
# from any path (asset paths are document-relative). VERSION names the zip;
# the release workflow passes the tag. Run as `make test smoke dist` so the
# release bundle is built once.
VERSION ?= $(GIT_COMMIT)
DIST := dist/loretile-app-$(VERSION)
DEMOS := sql infosec vineyard
LOCALES := ru de en
# the app's files, shared by both zips: $(call app-files,DIR)
define app-files
	@mkdir -p $(1)/css $(1)/js
	@cp public/icon.svg $(1)/
	@cp public/css/app.css public/css/driver.css $(1)/css/
	@cp public/js/main.js public/js/pdf.min.mjs public/js/pdf.worker.min.mjs \
	    public/js/pyrunner.js public/js/pgrunner.js $(1)/js/
	@cp -R public/js/pglite public/js/pyodide $(1)/js/
endef

dist: release
	@rm -rf $(DIST) $(DIST).zip && mkdir -p $(DIST)/demo
	$(call app-files,$(DIST))
	@cp public/index.html $(DIST)/
	@for d in $(DEMOS); do for l in $(LOCALES); do cp kg/demo/$$d.$$l.edn $(DIST)/demo/ || exit 1; done; done
	@printf 'Loretile %s\n\nServe this directory with any static web server, e.g.\n  python3 -m http.server 8000\nthen open http://localhost:8000/ — it also works under a sub-path.\nThe PGlite and Pyodide engines (js/pglite, js/pyodide) run exercises in the browser.\n' "$(VERSION)" > $(DIST)/README.txt
	@cd dist && zip -qr loretile-app-$(VERSION).zip loretile-app-$(VERSION)
	@echo "$(DIST).zip ($$(du -h $(DIST).zip | cut -f1))"

# The same app for an organisation (Denis 2026-10-20): no demo graphs — no
# demo/ directory, and index.html carries <meta name="loretile-demos"
# content="off">, so the app renders no demo control at all. The bundle is
# the same build as `dist`; an organisation can flip the meta in any copy.
# Run as `make test smoke dist dist-enterprise smoke-enterprise`.
DIST_E := $(DIST)-enterprise
dist-enterprise: release
	@rm -rf $(DIST_E) $(DIST_E).zip
	$(call app-files,$(DIST_E))
	@sed 's|<meta charset="utf-8">|<meta charset="utf-8">\n<meta name="loretile-demos" content="off">|' public/index.html > $(DIST_E)/index.html
	@grep -q '<meta name="loretile-demos" content="off">' $(DIST_E)/index.html || { echo "index.html: the demo switch was not written"; exit 1; }
	@printf 'Loretile %s — without demo graphs\n\nServe this directory with any static web server, e.g.\n  python3 -m http.server 8000\nthen open http://localhost:8000/ — it also works under a sub-path.\nThe PGlite and Pyodide engines (js/pglite, js/pyodide) run exercises in the browser.\nindex.html carries <meta name="loretile-demos" content="off">: the app offers no demo graphs.\nEverything stays in the browser that uses it; the only outbound calls go to the model\nprovider configured in Settings.\n' "$(VERSION)" > $(DIST_E)/README.txt
	@cd dist && zip -qr loretile-app-$(VERSION)-enterprise.zip loretile-app-$(VERSION)-enterprise
	@echo "$(DIST_E).zip ($$(du -h $(DIST_E).zip | cut -f1))"

SMOKE_PORT := 8292
smoke: release
	@python3 -m http.server $(SMOKE_PORT) -d public >/dev/null 2>&1 & \
	SRV=$$!; sleep 1; \
	node test/smoke.js http://localhost:$(SMOKE_PORT); STATUS=$$?; \
	kill $$SRV 2>/dev/null; \
	if [ $$STATUS -ne 0 ]; then echo "Deploy aborted — bundle failed to boot."; fi; \
	exit $$STATUS

# boot the enterprise zip's directory as shipped: it renders, offers no demo
# and never asks for one
smoke-enterprise: dist-enterprise
	@python3 -m http.server $(SMOKE_PORT) -d $(DIST_E) >/dev/null 2>&1 & \
	SRV=$$!; sleep 1; \
	node test/smoke.js http://localhost:$(SMOKE_PORT) --no-demos; STATUS=$$?; \
	kill $$SRV 2>/dev/null; \
	exit $$STATUS

# put FILE KEY CONTENT-TYPE CACHE
define put
curl -sf -X PUT -H "Authorization: Bearer $$IAM_TOKEN" \
	-H "Content-Type: $(3)" -H "Cache-Control: $(4)" \
	--data-binary @$(1) "$(ENDPOINT)/$(2)" >/dev/null
endef

# The landing is written entirely by its inline script — a runtime error
# is a blank page, so the gate boots it in a browser (kintale lesson).
check-landing:
	@node test/landing-check.js

deploy-landing: clean-tree check-landing
	@command -v yc >/dev/null || (echo "Error: yc CLI not installed" && exit 1)
	@IAM_TOKEN=$$(yc iam create-token) && \
	echo "Deploying landing to $(ENDPOINT) (v=$(GIT_COMMIT))..." && \
	sed 's|@VERSION@|$(GIT_COMMIT)|' landing/index.html > .landing.html && \
	$(call put,.landing.html,index.html,text/html; charset=utf-8,no-cache) && \
	rm -f .landing.html && \
	echo "Deployed: https://loretile.com (v $(GIT_COMMIT))"

deploy: clean-tree test smoke deploy-landing
	@IAM_TOKEN=$$(yc iam create-token) && \
	echo "Deploying app to $(ENDPOINT)/app/ (v=$(GIT_COMMIT))..." && \
	$(call put,public/js/main.js,app/js/main-$(GIT_COMMIT).js,application/javascript; charset=utf-8,max-age=31536000) && \
	$(call put,public/js/pdf.min.mjs,app/js/pdf.min.mjs,text/javascript; charset=utf-8,max-age=86400) && \
	$(call put,public/js/pdf.worker.min.mjs,app/js/pdf.worker.min.mjs,text/javascript; charset=utf-8,max-age=86400) && \
	$(call put,public/css/app.css,app/css/app.css,text/css; charset=utf-8,no-cache) && \
	$(call put,public/css/driver.css,app/css/driver.css,text/css; charset=utf-8,max-age=86400) && \
	$(call put,public/icon.svg,app/icon.svg,image/svg+xml,max-age=86400) && \
	for f in sql.ru sql.de sql.en infosec.ru infosec.de infosec.en vineyard.ru vineyard.de vineyard.en; do \
		curl -sf -X PUT -H "Authorization: Bearer $$IAM_TOKEN" \
			-H "Content-Type: application/edn; charset=utf-8" -H "Cache-Control: no-cache" \
			--data-binary @kg/demo/$$f.edn "$(ENDPOINT)/app/demo/$$f.edn" >/dev/null || exit 1; \
	done && \
	$(call put,public/js/pyrunner.js,app/js/pyrunner.js,text/javascript; charset=utf-8,no-cache) && \
	$(call put,public/js/pgrunner.js,app/js/pgrunner.js,text/javascript; charset=utf-8,no-cache) && \
	for f in public/js/pglite/* public/js/pyodide/*; do \
		case $$f in *.wasm) ct=application/wasm;; *.js|*.mjs) ct="text/javascript; charset=utf-8";; \
			*.json) ct=application/json;; *.zip) ct=application/zip;; *) ct=application/octet-stream;; esac; \
		gzip -9c $$f | curl -sf -X PUT -H "Authorization: Bearer $$IAM_TOKEN" \
			-H "Content-Type: $$ct" -H "Content-Encoding: gzip" -H "Cache-Control: no-cache" \
			--data-binary @- "$(ENDPOINT)/app/$${f#public/}" >/dev/null || exit 1; \
	done && \
	sed 's|js/main.js|js/main-$(GIT_COMMIT).js|' public/index.html > .deploy.html && \
	$(call put,.deploy.html,app/index.html,text/html; charset=utf-8,no-cache) && \
	rm -f .deploy.html && \
	echo "Deployed: https://loretile.com/app/ (v $(GIT_COMMIT))"

cert-status:
	@yc certificate-manager certificate get --name loretile-com --format json | \
		python3 -c "import sys,json; c=json.load(sys.stdin); print('loretile.com:', c['status'])"

# Public AGPL copy: one squashed "Snapshot <hash>" commit per publish, wiki/
# left out (design notes stay private). This repo stays the working one.
PUBLIC_REPO ?= https://github.com/smogstate/loretile.git
publish: clean-tree
	@rm -rf .publish && git clone -q $(PUBLIC_REPO) .publish && \
	find .publish -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} + && \
	git archive HEAD -- . ':(exclude)wiki' | tar -x -C .publish && \
	cd .publish && git add -A && \
	if git diff --cached --quiet; then echo "Nothing new to publish"; else \
		git commit -qm "Snapshot $(GIT_COMMIT)" && git push -q origin HEAD:master && \
		echo "Published $(GIT_COMMIT) to $(PUBLIC_REPO)"; fi && \
	cd .. && rm -rf .publish
