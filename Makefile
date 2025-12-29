# ._______ .______  .______  _____._.________
# : ____  |:      \ : __   \ \__ _:||    ___/
# |    :  ||   .   ||  \____|  |  :||___    \
# |   |___||   :   ||   :  \   |   ||       /
# |___|    |___|   ||   |___\  |   ||__:___/
#              |___||___|      |___|   :
#
# References:
# https://makefiletutorial.com/ - what a wonderful labour of love
# https://nedbatchelder.com/blog/201804/makefile_help_target.html - help target

.PHONY: help repl css-watch test test-watch test-config test-profile dist \
		build-css build-frontend build-config build-uberjar run-dist deploy \
		clean deps npm-deps pg-start pg-stop pg-status pg-console pg-console-test

.DEFAULT_GOAL := help

HELP_SPACING := 20
CLOJURE_TEST_RUNNER = clojure -X:test/env:test/run

VERSION := $(shell git rev-parse --short HEAD)
JAR_BASENAME = parts-$(VERSION)-standalone.jar 
JAR = target/$(JAR_BASENAME)
REMOTE = /opt/parts
HOST = parts

help: ## This blessed text
	@grep '^[a-zA-Z]' $(MAKEFILE_LIST) | sort | awk -F ':.*?## ' 'NF==2 {printf "  \033[36m%-$(HELP_SPACING)s\033[0m %s\n", $$1, $$2}'

pg-start: ## Start PostgreSQL and create databases
	@pg_ctl -l $$PGDATA/logfile start && sleep 2 && createdb -E UTF8 parts_dev 2>/dev/null || true && createdb -E UTF8 parts_test 2>/dev/null || true && echo "✓ PostgreSQL started (parts_dev, parts_test)"

pg-stop: ## Stop PostgreSQL
	@pg_ctl stop && echo "✓ PostgreSQL stopped"

pg-status: ## Check PostgreSQL status
	@pg_ctl status

pg-console: ## Open psql console (dev DB)
	@psql -d parts_dev

pg-console-test: ## Open psql console (test DB)
	@psql -d parts_test

repl: deps ## Start a Clojure REPL
	clojure -M:dev -m shadow.cljs.devtools.cli clj-repl

css-watch: ## Watch and build CSS
	pnpm exec postcss resources/styles/*.css -o resources/public/css/style.css --watch

test: ## Run clj/cljs unit tests
	$(CLOJURE_TEST_RUNNER)

test-watch: ## Run clj/cljs unit tests in watch mode
	$(CLOJURE_TEST_RUNNER) :watch? true

format-check: ## Check formatting of clj/cljs files
	clojure -M:cljfmt check

format-fix: ## Fix formatting of clj/cljs files
	clojure -M:cljfmt fix

npm-deps: package.json
	pnpm install

deps: deps.edn npm-deps ## Prepare dependencies for test and dist targets
	clojure -P

deps-update:
	clojure -M:antq --upgrade

npm-deps-update: package.json
	pnpm update

dist: build-css build-uberjar  ## Build project

build-css: ## Buld CSS for production
	NODE_ENV=production pnpm exec postcss resources/styles/*.css -o resources/public/css/style.css

build-frontend: ## Build frontend for production
	clojure -M:dev -m shadow.cljs.devtools.cli release frontend

build-config: ## Pretty print build configuration
	clojure -T:build/task config

build-uberjar: build-frontend ## Build uberjar for deployment
	rm -f target/*.jar
	rm -rf resources/public/js/cljs-runtime resources/public/com
	clojure -T:build/task uberjar
	mv target/*-standalone.jar $(JAR)

run-dist: ## Test dist locally before deploying
	java -jar target/parts-*-standalone.jar

deploy: dist ## Deploy to production
	scp $(JAR) $(HOST):$(REMOTE)/releases/
	ssh $(HOST) 'set -e; \
		cd $(REMOTE); \
		prev=$$(readlink current || true); \
		if [ -n "$$prev" ]; then ln -nfs $$prev previous; fi; \
		ln -nfs releases/$(JAR_BASENAME) current; \
		systemctl restart parts'

rollback:
	ssh $(HOST) 'set -e; \
		cd $(REMOTE); \
		prev=$$(readlink previous || true); \
		if [ -z "$$prev" ]; then echo "No previous release to roll back to!" >&2; exit 1; fi; \
		ln -nfs "$$prev" current; \
		systemctl restart parts'

clean: ## Clean build files
	rm -rf 	./.cpcache \
			./.clj-kondo/.cache \
			./.cljs_node_repl \
			./.lsp \
			./.shadow-cljs \
			./.nrepl-port \
			./out \
			./target \
			./node_modules \
			./resources/public/css/style.css
	find ./resources/public/js/ -mindepth 1 -not -name ".keep" -delete
	find . -name ".DS_Store" -type f -delete
	clojure -T:build/task clean
