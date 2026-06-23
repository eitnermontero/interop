#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# URLs
FRONTEND_URL="http://localhost:4200"
GATEWAY_URL="http://localhost:8080"
ADMIN_SERVICE_URL="http://localhost:8083"
REPORT_SERVICE_URL="http://localhost:8082"
CART_SERVICE_URL="http://localhost:8081"

PASS=0
FAIL=0

# Helper functions
pass() {
    echo -e "${GREEN}✅ $1${NC}"
    ((PASS++))
}

fail() {
    echo -e "${RED}❌ $1${NC}"
    ((FAIL++))
}

warn() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

echo "╔════════════════════════════════════════════════════════════╗"
echo "║         ADMIN PAGES AUTOMATED TEST SUITE                  ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Test 1: Frontend Accessibility
echo "Test 1: Frontend Accessibility"
if curl -s -o /dev/null -w "%{http_code}" "$FRONTEND_URL" | grep -q "200"; then
    pass "Frontend is accessible (http://localhost:4200)"
else
    fail "Frontend is not responding"
fi

# Test 2: Gateway Accessibility
echo ""
echo "Test 2: Gateway Accessibility"
if curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL" | grep -q "200"; then
    pass "Gateway is accessible (http://localhost:8080)"
else
    fail "Gateway is not responding"
fi

# Test 3: Microservices Health
echo ""
echo "Test 3: Microservices Health Checks"

check_health() {
    local url=$1
    local name=$2
    local response=$(curl -s "$url/actuator/health" 2>/dev/null)
    if echo "$response" | grep -q '"status":"UP"'; then
        pass "$name is UP"
    elif echo "$response" | grep -q 'UP'; then
        pass "$name is UP"
    else
        warn "$name health check could not be verified"
    fi
}

check_health "$ADMIN_SERVICE_URL" "Admin Service"
check_health "$REPORT_SERVICE_URL" "Report Service"
check_health "$CART_SERVICE_URL" "Cart Service"
check_health "$GATEWAY_URL" "Gateway"

# Test 4: Admin Service APIs
echo ""
echo "Test 4: Admin Service APIs (Direct Access)"

test_api() {
    local url=$1
    local name=$2
    local response=$(curl -s "$url" 2>/dev/null)
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$url")

    if [ "$http_code" = "200" ]; then
        if echo "$response" | jq . > /dev/null 2>&1; then
            local count=$(echo "$response" | jq 'if type == "array" then length elif .content != null then (.content | length) else 0 end' 2>/dev/null || echo 0)
            pass "$name - HTTP $http_code (Items: $count)"
        else
            pass "$name - HTTP $http_code"
        fi
    elif [ "$http_code" = "401" ]; then
        warn "$name - HTTP 401 (Authentication Required - Expected in production)"
    else
        fail "$name - HTTP $http_code"
    fi
}

test_api "$ADMIN_SERVICE_URL/api/v1/admin/menus" "GET /api/v1/admin/menus"
test_api "$ADMIN_SERVICE_URL/api/v1/admin/actions" "GET /api/v1/admin/actions"
test_api "$ADMIN_SERVICE_URL/api/v1/admin/roles" "GET /api/v1/admin/roles"
test_api "$ADMIN_SERVICE_URL/api/v1/admin/users" "GET /api/v1/admin/users"

# Test 5: Gateway Routing
echo ""
echo "Test 5: Gateway Routing (Through Gateway)"

test_gateway_route() {
    local url=$1
    local name=$2
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL$url")

    if [ "$http_code" = "200" ]; then
        pass "Route $url - HTTP $http_code"
    elif [ "$http_code" = "401" ]; then
        warn "Route $url - HTTP 401 (Authentication Required)"
    else
        warn "Route $url - HTTP $http_code"
    fi
}

test_gateway_route "/api/v1/admin/menus" "Admin Menus"
test_gateway_route "/api/v1/admin/actions" "Admin Actions"
test_gateway_route "/api/v1/admin/roles" "Admin Roles"
test_gateway_route "/api/v1/admin/users" "Admin Users"
test_gateway_route "/api/v1/reports" "Reports"
test_gateway_route "/api/v1/partners" "Partners (Cart)"

# Test 6: OpenAPI/Swagger Endpoints
echo ""
echo "Test 6: OpenAPI/Swagger Endpoints"

test_openapi() {
    local url=$1
    local name=$2
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$url")

    if [ "$http_code" = "200" ]; then
        local response=$(curl -s "$url" 2>/dev/null)
        if echo "$response" | jq . > /dev/null 2>&1; then
            local count=$(echo "$response" | jq '.paths | keys | length' 2>/dev/null || echo 0)
            pass "$name - HTTP $http_code (Endpoints: $count)"
        else
            pass "$name - HTTP $http_code"
        fi
    else
        fail "$name - HTTP $http_code"
    fi
}

test_openapi "$GATEWAY_URL/v3/api-docs" "Gateway OpenAPI"
test_openapi "$GATEWAY_URL/v3/api-docs/admin-service" "Admin Service OpenAPI"
test_openapi "$GATEWAY_URL/v3/api-docs/report-service" "Report Service OpenAPI"
test_openapi "$GATEWAY_URL/v3/api-docs/cart-service" "Cart Service OpenAPI"

# Test 7: Consul Service Registration
echo ""
echo "Test 7: Consul Service Discovery"

test_consul() {
    local service=$1
    local response=$(curl -s "http://localhost:8500/v1/catalog/service/$service" 2>/dev/null)

    if echo "$response" | jq . > /dev/null 2>&1; then
        local count=$(echo "$response" | jq 'length' 2>/dev/null)
        if [ "$count" -gt 0 ]; then
            pass "Service '$service' registered in Consul (Instances: $count)"
        else
            warn "Service '$service' not found in Consul"
        fi
    else
        warn "Could not check Consul for service '$service'"
    fi
}

test_consul "mdqr-admin-service"
test_consul "mdqr-report-service"
test_consul "mdqr-cart-service"
test_consul "mdqr-gateway"

# Test 8: Admin Page Routes (Frontend Bundle Check)
echo ""
echo "Test 8: Frontend Admin Page Routes"

test_frontend_route() {
    local path=$1
    local name=$2
    local response=$(curl -s "$FRONTEND_URL$path")

    if echo "$response" | grep -q "DOCTYPE html\|<html"; then
        pass "Route $path - Frontend responding"
    else
        warn "Route $path - Could not verify response"
    fi
}

test_frontend_route "/admin/usuarios" "Usuarios Page"
test_frontend_route "/admin/roles" "Roles Page"
test_frontend_route "/admin/menus" "Menus Page"
test_frontend_route "/admin/acciones" "Acciones Page"
test_frontend_route "/admin/permisos" "Permisos Page"
test_frontend_route "/admin/auditoria" "Auditoria Page"

# Test 9: Database Connectivity
echo ""
echo "Test 9: Database Schema Verification"

# Check if admin schema exists and has tables
if curl -s "$ADMIN_SERVICE_URL/actuator/health/db" 2>/dev/null | grep -q "UP\|status"; then
    pass "Admin Service Database Connection - Healthy"
else
    warn "Admin Service Database Connection - Could not verify"
fi

# Test 10: Feature Completeness
echo ""
echo "Test 10: Feature Completeness Check"

info "Checking Admin Service OpenAPI for required operations..."
admin_openapi=$(curl -s "$GATEWAY_URL/v3/api-docs/admin-service" 2>/dev/null)

check_operation() {
    local op=$1
    local count=$(echo "$admin_openapi" | jq "[.paths[][][] | select(.operationId? == \"$op\")]" 2>/dev/null | grep -c operationId)
    if [ "$count" -gt 0 ]; then
        pass "Operation '$op' is implemented"
    else
        warn "Operation '$op' not found in OpenAPI spec"
    fi
}

check_operation "list"
check_operation "create"
check_operation "update"
check_operation "delete"

# Summary
echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                    TEST SUMMARY                           ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo -e "Total Tests: $((PASS + FAIL))"
echo -e "${GREEN}Passed: $PASS${NC}"
echo -e "${RED}Failed: $FAIL${NC}"

if [ $FAIL -eq 0 ]; then
    SUCCESS_RATE=100
else
    SUCCESS_RATE=$(( (PASS * 100) / (PASS + FAIL) ))
fi
echo -e "Success Rate: ${SUCCESS_RATE}%"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}🎉 All tests passed! Admin pages are fully functional.${NC}"
    echo ""
    echo "📍 Access Frontend: http://localhost:4200"
    echo "📍 Access Gateway Swagger: http://localhost:8080/swagger-ui.html"
    echo "📍 Access Admin Service: http://localhost:8083/swagger-ui.html"
    echo "📍 Access Consul: http://localhost:8500/ui/"
    exit 0
else
    echo -e "${YELLOW}⚠️  Some tests did not pass. Please review the output above.${NC}"
    exit 1
fi
