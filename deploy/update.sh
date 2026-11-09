#!/usr/bin/env bash
# Installs the latest version from GitHub. Database migrations run automatically on start.
#   cd /opt/prenotazione-scarico && sudo ./deploy/update.sh
set -euo pipefail
cd "$(dirname "$0")/.."
git pull --ff-only
docker compose up -d --build
docker image prune -f >/dev/null
for i in $(seq 1 60); do
  docker compose exec -T app wget -qO- http://127.0.0.1:3000/api/health 2>/dev/null | grep -q '"ok":true' && { echo "Updated and running."; exit 0; }
  sleep 3
done
echo "Not healthy yet. Logs: docker compose logs app"; exit 1
