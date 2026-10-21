#!/usr/bin/env bash
# Single source of truth for the SpotiFLAC backend revisions Hush builds against.
#
# Both build scripts read this file: scripts/build-spotiflac-aar.sh for the Go runtime that ships in
# app/libs/gobackend.aar, and scripts/build-spotiflac-rust.sh for the Rust runtime the migration
# stages. "Which revision of the backend is Hush built from?" therefore has one answer instead of
# one per script, and bumping a backend means editing this file and nothing else.
#
# Why that matters here rather than being a tidiness point: the two revisions are different ports of
# the same upstream, not two names for one commit, so nothing at build time can cross-check them.
# A revision written down in a build script as well as here is a revision that can be bumped in one
# of them and forgotten in the other, and the symptom of that is an APK carrying one backend while
# the script that rebuilt it claims the other - which no test in this repo would catch.
#
# Sourced, not executed. The file defines variables and runs no commands of its own, so a script
# under `set -euo pipefail` reads it without inheriting any behaviour. Run it directly to print what
# it defines, which is the quickest way to see what this checkout builds.
#
# Bumping the Go pin is a migration, not a checkout update: upstream deleted `go_backend/` in 5.0.x,
# so there is no newer Go revision to move to. Bumping the Rust pin is a port of Hush's calls onto a
# different UniFFI surface. Both are described in docs/spotiflac-rust-migration-plan.md.

# The upstream repository both backends come from.
#
# It was called SpotiFLAC-Mobile until 2026-10-20, when the app shipped as **Mornye** (v5.2.1:
# "Rename app and native backend to Mornye") and the repository moved to `mornyeapp/Mornye`. The old
# address still answers 301 to the new one, so a checkout cloned from either spelling is the same
# repository; the pinned Go commit below resolves in the renamed repository too (checked through the
# API on 2026-10-20), which is what lets the pin survive the rename without a rebuild.
#
# Neither pin moves because of it. The Go pin is the last commit that has `go_backend/` at all, and
# that tree predates both the rename and the rewrite; the Rust pin is the migration target the plan
# measured, and it is a v5.0.7 tree that still calls itself SpotiFLAC. Moving the Rust pin past 5.1
# is a port, not a bump: the crates were renamed `spotiflac_*` -> `mornye_*` (see
# docs/spotiflac-rust-migration-plan.md, section 7).
SPOTIFLAC_UPSTREAM="https://github.com/mornyeapp/Mornye.git"

# What the upstream project is called now, and where a person who follows a link should land. Used
# in diagnostics only; nothing reads it to make a request.
SPOTIFLAC_UPSTREAM_NAME="Mornye"

# Directory name of the shared clone under the repo root. Both scripts build inside a worktree of
# this one clone - the Go tree directly, the Rust tree in a worktree - so the pinned objects are
# fetched once and there is a single object store to keep.
#
# Kept as the old name on purpose: existing checkouts on this machine, the worktree the migration
# plan names, and the paths in its documents are all spelled `SpotiFLAC-Mobile/`, and a script that
# looked for a renamed directory would answer "checkout not found" on the one checkout that exists.
SPOTIFLAC_MOBILE_DIR="SpotiFLAC-Mobile"

# The Go runtime Hush ships today: gomobile-bound into app/libs/gobackend.aar.
#
# v4.9.6-3, the last commit whose `go_backend/` still exists, and the tree the committed AAR was
# built from. SPOTIFLAC_GO_BACKEND_PIN is what a person reads and what messages show;
# SPOTIFLAC_GO_BACKEND_SHA is what a checkout is compared against, because a seven-character prefix
# is not enough to identify a revision unambiguously.
SPOTIFLAC_GO_BACKEND_PIN="a34e2935"
SPOTIFLAC_GO_BACKEND_SHA="a34e2935442e7ec661c539ab3c2df8fc0b23b1ff"

# The Rust runtime the migration targets, staged under app/libs/spotiflac-rust by
# scripts/build-spotiflac-rust.sh. The tag is resolved and asserted against the SHA at build time,
# so a tag that has been moved cannot silently change which backend gets built.
SPOTIFLAC_RUST_TAG="v5.0.7"
SPOTIFLAC_RUST_SHA="19cb70337949cd13d029a4f754b0e20a7483eb42"

# Android API level both backends are built for: the Go AAR's `-androidapi` and the Rust
# cross-compile linker and sysroot suffix. They have to agree, or one backend is built for devices
# the other cannot be loaded on.
SPOTIFLAC_ANDROID_API=24

# Executed rather than sourced: report what this checkout builds against.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  printf '%s\n' \
    "upstream:     $SPOTIFLAC_UPSTREAM ($SPOTIFLAC_UPSTREAM_NAME)" \
    "clone:        $SPOTIFLAC_MOBILE_DIR" \
    "go backend:   $SPOTIFLAC_GO_BACKEND_PIN ($SPOTIFLAC_GO_BACKEND_SHA) -> app/libs/gobackend.aar" \
    "rust backend: $SPOTIFLAC_RUST_TAG ($SPOTIFLAC_RUST_SHA) -> app/libs/spotiflac-rust" \
    "android api:  $SPOTIFLAC_ANDROID_API"
fi
