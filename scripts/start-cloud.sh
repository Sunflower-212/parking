#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/check-cloud-config.py
docker compose -p parking -f compose.cloud.yml up -d --no-build --pull never --wait --wait-timeout 600
if ! docker compose -p parking -f compose.cloud.yml exec -T rabbitmq sh -c 'rabbitmqctl authenticate_user "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS" >/dev/null 2>&1'; then
  echo 'BLOCK: RabbitMQ stored credentials do not match .env; restore the original credentials. No password or volume was reset.' >&2
  exit 1
fi
docker compose -p parking -f compose.cloud.yml exec -T rabbitmq rabbitmq-diagnostics -q check_local_alarms
echo 'Core services started; verify external provider authorization and public callbacks separately.'
