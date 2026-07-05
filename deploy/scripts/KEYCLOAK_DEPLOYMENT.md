# Keycloak Client Deployment Strategy

## Development Environment

### Automatic Setup
When you run `dev-up.sh`, it automatically:

1. **Starts infrastructure** (Postgres, Redis, Consul, Vault, Keycloak)
2. **Waits for Keycloak** to be ready (port 8180)
3. **Runs `keycloak-sync.sh --yes-to-all`** to create:
   - Realm: `hub`
   - Service Client: `hub-api`
   - Partner Clients: `cartcore_stage_demo01`, `mwc-admin-service`
   - SPA Clients: `mwc-public-fe`, `mwc-admin-fe`
   - Users: admin (admin/admin), soboce-test (soboce-test/soboce123)
   - Roles: soboce:*, cart:*, etc.

### Configuration Files
The sync process reads from `scripts/keycloak-seed/`:

| File | Purpose | Includes |
|------|---------|----------|
| `spa-clients.csv` | Frontend SPA clients | `mwc-public-fe`, `mwc-admin-fe` |
| `clients.csv` | Backend service clients | `cartcore_stage_demo01`, `mwc-admin-service` |
| `roles.csv` | Realm roles | soboce:*, cart:*, etc. |
| `scopes.csv` | OAuth2 scopes | cart:read, cart:write, etc. |
| `resources.csv` | Protected resources | Cart endpoints, etc. |
| `policies.csv` | Authorization policies | Role-based policies |
| `permissions.csv` | Resource permissions | Resource + policy + scope mappings |
| `keyload.csv` | Client ID references | public, admin clients |
| `keyload.json` | Full config structure | Dev/prod configurations per client |

## Production Environment

### Option 1: Pre-deployed Keycloak (Recommended)
If Keycloak already exists in production:

```bash
export KC_URL="https://sso.sintesis.com.bo"
export KC_REALM="hub"
export KC_USERNAME="keycloak-admin"
export KC_PASSWORD="<secure-password-from-vault>"

bash deploy/scripts/keycloak-sync.sh --yes-to-all
```

**Deployment locations (from environment configs):**
- Public frontend: `https://public.reports.sintesis.com.bo` → `mwc-public-fe`
- Admin frontend: `https://internal.reports.sintesis.com.bo` → `mwc-admin-fe`
- API: `https://reports-api.sintesis.com.bo` → service clients

### Option 2: Docker Keycloak in Production (Optional)

If you need to manage Keycloak in Docker:

```bash
# Create keycloak service in docker-compose.prod.yml
docker compose -f docker-compose.yml -f docker-compose.prod.yml up keycloak -d

# Wait for it to be ready
sleep 30

# Sync realm configuration
KC_URL="http://keycloak-internal:8080" bash deploy/scripts/keycloak-sync.sh --yes-to-all
```

### Option 3: CI/CD Pipeline (Automated)

In your CI/CD pipeline (e.g., GitHub Actions, GitLab CI):

```yaml
# Example: GitHub Actions
deploy-keycloak:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v3
    - name: Sync Keycloak Realm
      env:
        KC_URL: https://sso.sintesis.com.bo
        KC_REALM: hub
        KC_USERNAME: keycloak-admin
        KC_PASSWORD: ${{ secrets.KEYCLOAK_ADMIN_PASSWORD }}
      run: bash deploy/scripts/keycloak-sync.sh --yes-to-all
```

## Environment Variables Reference

For both dev and production:

```bash
# Keycloak Server
KC_URL="http://localhost:8180"              # Dev
KC_URL="https://sso.sintesis.com.bo"        # Prod

# Realm & Admin
KC_REALM="hub"
KC_USERNAME="admin"
KC_PASSWORD="admin"                          # Use from vault in prod

# Service Client
KC_SERVICE_CLIENT="hub-api"      # Manages authorization

# Test User
KC_SOBOCE_TEST_USER="soboce-test"
KC_SOBOCE_TEST_PASSWORD="soboce123"          # Use from vault in prod
```

## Client ID Mappings

### Frontend (SPA) Clients
```json
{
  "public": {
    "clientId": "mwc-public-fe",
    "type": "public",
    "redirectUri": "http://localhost:4200",        // dev
    "redirectUri": "https://public.reports.sintesis.com.bo"  // prod
  },
  "admin": {
    "clientId": "mwc-admin-fe",
    "type": "public",
    "redirectUri": "http://localhost:4300",        // dev
    "redirectUri": "https://internal.reports.sintesis.com.bo"  // prod
  }
}
```

### Backend (Service) Clients
```json
{
  "cartcore": {
    "clientId": "cartcore_stage_demo01",
    "type": "confidential",
    "secret": "cartcore_stage_demo01"  // Use from vault in prod
  },
  "admin-service": {
    "clientId": "mwc-admin-service",
    "type": "confidential",
    "secret": "mwc-admin-service-secret"  // Use from vault in prod
  }
}
```

## Idempotency

Both `keycloak-sync.sh` and `--yes-to-all` are **fully idempotent**:

- ✅ Safe to run multiple times
- ✅ Skips existing clients (won't recreate)
- ✅ Updates roles if needed
- ✅ No data loss
- ✅ Suitable for CI/CD pipelines

## Manual Testing

After deployment, verify clients exist:

```bash
export KC_URL="http://localhost:8180"
export KC_REALM="hub"
export KC_USERNAME="admin"
export KC_PASSWORD="admin"

# Get all clients
curl -s -H "Authorization: Bearer $(curl -sf \
  ${KC_URL}/realms/master/protocol/openid-connect/token \
  -d "client_id=admin-cli&username=${KC_USERNAME}&password=${KC_PASSWORD}&grant_type=password" \
  | jq -r '.access_token')" \
  ${KC_URL}/admin/realms/${KC_REALM}/clients?max=100 | jq '.[].clientId'
```

Expected output:
```
hub-api
cartcore_stage_demo01
mwc-admin-service
mwc-public-fe
mwc-admin-fe
```
