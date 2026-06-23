#!/bin/sh
set -eu

HTML_DIR="/usr/share/nginx/html"
INDEX_FILE="${HTML_DIR}/index.html"
CONFIG_FILE="${HTML_DIR}/assets/config.json"

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

normalize_base_path() {
  path="${1:-/}"

  if [ -z "$path" ]; then
    path="/"
  fi

  case "$path" in
    /*) ;;
    *) path="/$path" ;;
  esac

  while printf '%s' "$path" | grep -q '//'; do
    path=$(printf '%s' "$path" | sed 's|//|/|g')
  done

  case "$path" in
    */) ;;
    *) path="$path/" ;;
  esac

  printf '%s' "$path"
}

APP_BASE_PATH="$(normalize_base_path "${APP_BASE_PATH:-/}")"
# apiUrl = context-path relativo (sin trailing slash): el FE consume /services bajo su
# propio contexto (${apiUrl}/services/...), nginx lo reescribe al gateway. No depende de
# un host externo ni del proxy. '' cuando el contexto es root.
API_BASE="${APP_BASE_PATH%/}"
APP_NAME="${APP_NAME:-MDQR Portal}"
APP_KEYCLOAK_URL="${APP_KEYCLOAK_URL:-}"
APP_KEYCLOAK_REALM="${APP_KEYCLOAK_REALM:-}"
APP_KEYCLOAK_CLIENT_ID="${APP_KEYCLOAK_CLIENT_ID:-mdqr-public-fe}"

if [ -f "$INDEX_FILE" ]; then
  sed -i "s|<base href=\"[^\"]*\">|<base href=\"${APP_BASE_PATH}\">|g" "$INDEX_FILE"
fi

mkdir -p "$(dirname "$CONFIG_FILE")"
cat > "$CONFIG_FILE" <<EOF
{
  "apiUrl": "$(json_escape "$API_BASE")",
  "appName": "$(json_escape "$APP_NAME")",
  "basePath": "$(json_escape "$APP_BASE_PATH")",
  "keycloak": {
    "url": "$(json_escape "$APP_KEYCLOAK_URL")",
    "realm": "$(json_escape "$APP_KEYCLOAK_REALM")",
    "clientId": "$(json_escape "$APP_KEYCLOAK_CLIENT_ID")"
  }
}
EOF

# --- nginx server block (runtime) ---
# El context-path es 100% runtime: los assets compilados viven en la raiz de html/
# y 'alias' los mapea bajo ${APP_BASE_PATH}. Por eso cambiar APP_BASE_PATH basta
# (no requiere rebuild). 'location = /' redirige al contexto solo si hay contexto,
# para no provocar un loop 302 cuando APP_BASE_PATH es '/'.
NGINX_CONF="/etc/nginx/conf.d/default.conf"

ROOT_REDIRECT=""
if [ "$APP_BASE_PATH" != "/" ]; then
  ROOT_REDIRECT="location = / { return 302 ${APP_BASE_PATH}; }"
fi

cat > "$NGINX_CONF" <<EOF
server {
    listen 80;
    server_name _;

    location = /health {
        access_log off;
        return 200 "OK";
        add_header Content-Type text/plain;
    }

    ${ROOT_REDIRECT}

    # /services bajo el propio contexto del FE: el proxy externo solo rutea ${APP_BASE_PATH};
    # aca se reescribe ${APP_BASE_PATH}services/... -> /services/... y se proxea al gateway.
    location ${APP_BASE_PATH}services/ {
        resolver 127.0.0.11 ipv6=off valid=10s;
        set \$gateway_host gateway;
        rewrite ^${APP_BASE_PATH}services/(.*)\$ /services/\$1 break;
        proxy_pass http://\$gateway_host:8080\$uri\$is_args\$args;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location ${APP_BASE_PATH} {
        alias /usr/share/nginx/html/;
        try_files \$uri \$uri/ ${APP_BASE_PATH}index.html;
        add_header Cache-Control "no-cache, no-store, must-revalidate";

        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)\$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
    }

    location ~ /\. {
        deny all;
        access_log off;
        log_not_found off;
    }
}
EOF

# Fail-fast: si el conf generado es invalido, abortar antes de que arranque nginx.
nginx -t

echo "Runtime config generated:"
echo "  APP_BASE_PATH=${APP_BASE_PATH}"
echo "  apiUrl(relativo)=${API_BASE:-<root>}"
echo "  APP_NAME=${APP_NAME}"
echo "  APP_KEYCLOAK_URL=${APP_KEYCLOAK_URL}"
echo "  APP_KEYCLOAK_REALM=${APP_KEYCLOAK_REALM}"
echo "  APP_KEYCLOAK_CLIENT_ID=${APP_KEYCLOAK_CLIENT_ID}"
