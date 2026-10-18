#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

./mvnw -DskipTests package
exec java -jar arl-server/target/arl-server-0.1.0-SNAPSHOT.jar
