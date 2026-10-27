SHELL := /bin/bash
.DEFAULT_GOAL := help

IMAGE ?= rilvune-dev:java25
PROJECT ?= $(CURDIR)
BROWSE_ROOT ?=
PROFILE ?= $(CURDIR)/.local
TESTS ?=

.PHONY: help image build release publish test test-ui run preview clean build-local release-local publish-local test-local test-ui-local check-local preview-local clean-local

help:
	@printf '%s\n' \
	  'make build                 Build, test and install the application in Docker' \
	  'make release               Build a single Linux executable with Java included' \
	  'make publish               Publish all files in build/release to GitHub Releases' \
	  'make test                  Run JUnit tests in Docker' \
	  'make test-ui               Run UI integration tests with Docker Xvfb' \
	  'make run [PROJECT=/path] [BROWSE_ROOT=/path] [PROFILE=/path]' \
	  '                           Launch with persistent host files and profile' \
	  'make preview               Render editor, settings, comparison and Git screenshots' \
	  'make clean                 Remove build outputs; keep downloaded dependencies' \
	  '' \
	  'Inside the development container (no Docker required):' \
	  'make build-local           Build, run headless tests and install the application' \
	  'make release-local         Build the standalone executable using a local JDK 25' \
	  'make publish-local         Publish staged release files using local GitHub CLI' \
	  'make test-local            Run headless tests; optional TESTS=package.Class.method' \
	  'make test-ui-local         Run Swing tests in isolated Xvfb; optional TESTS=filter' \
	  'make check-local           Run both headless and Swing tests' \
	  'make preview-local         Regenerate docs screenshots using isolated instances' \
	  'make clean-local           Remove build outputs; preserve profile and cache' \
	  'Launch the editor only from the host with make run (no run-local).'

image:
	docker build --tag "$(IMAGE)" --file Dockerfile .

build: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh build

release: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh release

publish: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh publish

test: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh test

test-ui: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh test-ui

run: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh run "$(PROJECT)" "$(BROWSE_ROOT)" "$(PROFILE)"

preview: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh preview

clean: image
	RILVUNE_IMAGE="$(IMAGE)" ./scripts/container.sh clean

build-local:
	./scripts/container.sh --local build

release-local:
	./scripts/container.sh --local release

publish-local:
	./scripts/container.sh --local publish

test-local:
	./scripts/container.sh --local test $(if $(TESTS),--tests "$(TESTS)")

test-ui-local:
	./scripts/container.sh --local test-ui $(if $(TESTS),--tests "$(TESTS)")

check-local:
	bash ./scripts/test-version.sh
	bash ./scripts/test-workflow.sh
	bash ./scripts/test-publish.sh
	./scripts/container.sh --local test
	./scripts/container.sh --local test-ui

preview-local:
	./scripts/container.sh --local preview

clean-local:
	./scripts/container.sh --local clean
