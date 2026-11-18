#!/bin/sh
set -eu
base=${1:-http://127.0.0.1:18184}
curl --fail --silent --show-error "$base/actuator/health"
printf '\n'
curl --fail --silent --show-error "$base/api/catalog/v1/datasets?page=0&size=10"
printf '\n'
curl --fail --silent --show-error -H 'Accept: application/ld+json' "$base/api/catalog/v1/dcat"
printf '\n'
