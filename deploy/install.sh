#!/usr/bin/env bash
# First installation on the Aruba server (Ubuntu/Debian). Safe to run again.
#   sudo apt-get install -y git
#   sudo git clone https://github.com/TEA-CoderR/Prenotazione_Scarico.git /opt/prenotazione-scarico
#   cd /opt/prenotazione-scarico && sudo ./deploy/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
[ "$(id -u)" -eq 0 ] || { echo "Run with sudo."; exit 1; }

say "1/6 Docker"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null

say "2/6 Firewall"
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null && ufw allow 443/tcp >/dev/null && echo "Ports 80 and 443 opened."
else
  echo "ufw not active: make sure ports 80 and 443 are open in the Aruba panel too."
fi

say "3/6 Settings (.env)"
if [ ! -f .env ]; then
  read -rp "Domain [prenota.casa-te.com]: " DOMAIN; DOMAIN=${DOMAIN:-prenota.casa-te.com}
  read -rsp "Password of noreply@casa-te.com (Enter = not yet, mails go to the log): " SMTP_PASS; echo
  read -rp "Turnstile site key (Enter = later): " TS_SITE
  read -rsp "Turnstile secret key (Enter = later): " TS_SECRET; echo
  sed -e "s|^DOMAIN=.*|DOMAIN=$DOMAIN|" \
      -e "s|^APP_URL=.*|APP_URL=https://$DOMAIN|" \
      -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" \
      -e "s|^SMTP_PASS=.*|SMTP_PASS=$SMTP_PASS|" \
      -e "s|^TURNSTILE_SITE_KEY=.*|TURNSTILE_SITE_KEY=$TS_SITE|" \
      -e "s|^TURNSTILE_SECRET_KEY=.*|TURNSTILE_SECRET_KEY=$TS_SECRET|" \
      -e "/^DATABASE_URL=/d" \
      .env.example > .env
  [ -n "$SMTP_PASS" ] || sed -i "s|^SMTP_USER=.*|SMTP_USER=|" .env
  chmod 600 .env
  echo ".env created (database password generated). Edit it later with: sudo nano .env"
else
  echo ".env already exists: kept as is."
fi
DOMAIN=$(grep '^DOMAIN=' .env | cut -d= -f2)

say "4/6 DNS check for $DOMAIN"
SERVER_IP=$(curl -fsS -4 https://ifconfig.me || true)
DNS_IP=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
if [ -n "$SERVER_IP" ] && [ "$SERVER_IP" = "$DNS_IP" ]; then
  echo "OK: $DOMAIN → $SERVER_IP"
else
  echo "WARNING: $DOMAIN points to '${DNS_IP:-nothing}', this server is '${SERVER_IP:-unknown}'."
  echo "Add the DNS A record; the HTTPS certificate is obtained automatically once it resolves."
fi

say "5/6 Build and start (first time takes a few minutes)"
docker compose up -d --build
for i in $(seq 1 60); do
  if docker compose exec -T app wget -qO- http://localhost:3000/api/health 2>/dev/null | grep -q '"ok":true'; then
    echo "Application is running."; break
  fi
  sleep 3
  [ "$i" -eq 60 ] && { echo "Not healthy yet. Logs: docker compose logs app"; exit 1; }
done

say "6/6 First administrator"
if docker compose exec -T db psql -U scarico -d scarico -tAc "select count(*) from app_user where role='admin'" | grep -q '^0$'; then
  read -rp "Administrator e-mail: " ADMIN_EMAIL
  read -rp "Administrator name: " ADMIN_NAME
  docker compose exec -T app node dist-scripts/create-user.js --email "$ADMIN_EMAIL" --name "$ADMIN_NAME" --role admin
  echo "Write down the temporary password above, then sign in at https://$DOMAIN/magazzino"
else
  echo "An administrator already exists."
fi

say "Done: https://$DOMAIN (carriers) · https://$DOMAIN/magazzino (staff)"
