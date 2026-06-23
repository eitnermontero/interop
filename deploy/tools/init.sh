#!/usr/bin/env bash
# Script de inicialización para el stack de tools (Consul, Redis, Vault, Keycloak)
# Ejecutar desde deploy/tools/

set -e

echo "🚀 Inicializando MDQR Tools Stack..."
echo ""

# 1. Crear .env si no existe
if [ ! -f .env ]; then
  echo "📝 Creando .env desde .env.example..."
  cp .env.example .env
  echo "   ✅ .env creado"
else
  echo "✅ .env ya existe"
fi

# 2. Crear red compartida si no existe
echo ""
echo "🌐 Verificando red compartida mdqr_shared..."
if ! docker network inspect mdqr_shared >/dev/null 2>&1; then
  echo "   Creando red mdqr_shared..."
  docker network create --driver bridge mdqr_shared
  echo "   ✅ Red mdqr_shared creada"
else
  echo "   ✅ Red mdqr_shared ya existe"
fi

# 3. Levantar contenedores
echo ""
echo "🐳 Levantando contenedores..."
docker compose up -d

# 4. Esperar a que los servicios estén listos
echo ""
echo "⏳ Esperando a que los servicios estén listos..."
sleep 5

# 5. Verificar estado
echo ""
echo "📊 Estado de los servicios:"
docker compose ps

echo ""
echo "✅ ¡Stack inicializado correctamente!"
echo ""
echo "📍 Servicios disponibles:"
echo "   - Keycloak:  http://localhost:8180 (admin/admin)"
echo "   - Consul:    http://localhost:8500"
echo "   - Vault:     http://localhost:8200 (token: root)"
echo "   - Redis:     localhost:6379"
echo ""
echo "💡 Para ver logs: docker compose logs -f"
echo "💡 Para detener:  docker compose down"
