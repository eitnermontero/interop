# MWC Admin Service - API Test Report
**Date:** 2026-05-19  
**Status:** ⚠️ **ISSUES FOUND**

---

## 📋 API Endpoints Discovered

### 1. **Users API** (`/api/v1/admin/users`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/users` | GET | List users (paginated) | ✓ USUARIOS:READ |
| `/api/v1/admin/users/{userId}` | GET | Get user by ID | ✓ USUARIOS:READ |
| `/api/v1/admin/users` | POST | Create new user | ✓ USUARIOS:CREATE |
| `/api/v1/admin/users/{userId}` | PUT | Update user | ✓ USUARIOS:UPDATE |
| `/api/v1/admin/users/{userId}` | DELETE | Delete user | ✓ USUARIOS:DELETE |
| `/api/v1/admin/users/{userId}/password` | PUT | Reset password | ✓ USUARIOS:UPDATE |
| `/api/v1/admin/users/{userId}/status` | PUT | Enable/Disable user | ✓ USUARIOS:UPDATE |
| `/api/v1/admin/users/{userId}/roles` | GET | Get user roles | ✓ USUARIOS:READ |
| `/api/v1/admin/users/{userId}/roles` | PUT | Update user roles | ✓ USUARIOS:UPDATE |
| `/api/v1/admin/users/{userId}/send-reset` | POST | Send password reset email | ✓ USUARIOS:UPDATE |

### 2. **Roles API** (`/api/v1/admin/roles`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/roles` | GET | List all roles | ✓ ROLES:READ |
| `/api/v1/admin/roles/{name}` | GET | Get role by name | ✓ ROLES:READ |
| `/api/v1/admin/roles` | POST | Create new role | ✓ ROLES:CREATE |
| `/api/v1/admin/roles/{name}` | PUT | Update role | ✓ ROLES:UPDATE |
| `/api/v1/admin/roles/{name}` | DELETE | Delete role | ✓ ROLES:DELETE |

### 3. **Permissions API** (`/api/v1/admin/roles/{name}/permissions`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/roles/{name}/permissions` | GET | Get role permissions | ✓ PERMISOS:READ |
| `/api/v1/admin/roles/{name}/permissions` | PUT | Set role permissions | ✓ PERMISOS:UPDATE |

### 4. **Actions API** (`/api/v1/admin/actions`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/actions` | GET | List all actions (CRUD operations) | ✓ ACCIONES:READ |
| `/api/v1/admin/actions/{id}` | GET | Get action by ID | ✓ ACCIONES:READ |
| `/api/v1/admin/actions` | POST | Create new action | ✓ ACCIONES:CREATE |
| `/api/v1/admin/actions/{id}` | PUT | Update action | ✓ ACCIONES:UPDATE |
| `/api/v1/admin/actions/{id}` | DELETE | Delete action | ✓ ACCIONES:DELETE |

### 5. **Menu API** (`/api/v1/admin/menus`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/menus` | GET | Get menu tree | ✓ MENUS:READ |
| `/api/v1/admin/menus/{id}` | GET | Get menu item by ID | ✓ MENUS:READ |
| `/api/v1/admin/menus` | POST | Create menu item | ✓ MENUS:CREATE |
| `/api/v1/admin/menus/{id}` | PUT | Update menu item | ✓ MENUS:UPDATE |
| `/api/v1/admin/menus/{id}` | DELETE | Delete menu item | ✓ MENUS:DELETE |
| `/api/v1/admin/menus/reorder` | PUT | Reorder menu items | ✓ MENUS:UPDATE |

### 6. **Audit Log API** (`/api/v1/admin/audit`)
| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/v1/admin/audit` | GET | Search audit logs (paginated) | ✓ AUDITORIA:READ |
| `/api/v1/admin/audit/{id}` | GET | Get audit log by ID | ✓ AUDITORIA:READ |
| `/api/v1/admin/audit/event-types` | GET | Get distinct event types | ✓ AUDITORIA:READ |
| `/api/v1/admin/audit/modules` | GET | Get distinct modules | ✓ AUDITORIA:READ |
| `/api/v1/admin/audit/export` | GET | Export audit logs to CSV | ✓ AUDITORIA:EXPORT |

---

## 🗄️ Database Schema

**Schema:** `admin`

### Tables:
- ✓ `menu` - Menu items with hierarchy support
- ✓ `action` - CRUD and operational actions
- ✓ `role_menu_action` - Role permissions matrix
- ✓ `audit_log` - Audit trail logs

---

## ❌ Issues Found

### 1. **Seed Data Loading**
- **Status:** ✅ **RESOLVED**
- **Data Loaded:**
  - `admin.action` - **8 rows** ✓ (READ, CREATE, UPDATE, DELETE, APPROVE, EXPORT, PRINT, VOID)
  - `admin.menu` - **17 rows** ✓ (Dashboard, Pagos, Partners, Reportes, Administración + submenus)
  - `admin.role_menu_action` - **136 rows** ✓ (SUPER_ADMIN has all permissions)
  - `admin.audit_log` - **50 rows** ✓ (has data)

- **Note:** Liquibase changesets did not execute automatically. Seed data was loaded manually.
  - Seed Data Files: ✓ Verified to exist
  - `db/changelog/changes/data/099-actions.csv` - 8 actions defined
  - `db/changelog/changes/data/099-menus.csv` - 18 menus defined

### 2. **Authentication/Authorization Issues**
- **Status:** ⚠️ BLOCKING
- **Problem:** All endpoints require authentication with specific permissions
- **Current Error:** `401 Unauthorized - No authenticated client`
- **When authenticated:** `502 Keycloak Upstream Error - Access Denied`

### 3. **Service Status**
- **Admin Service:** ✓ Running (port 8083)
- **PostgreSQL:** ✓ Running (port 5432)
- **Keycloak:** ✓ Running (port 8180)
- **Vault:** ✓ Running (port 8200)

---

## 🔧 Action Items

### Priority 1: Load Seed Data
```bash
# Option A: Manually insert seed data
INSERT INTO admin.action (id, code, name, description, created_by, created_date) 
VALUES (1, 'READ', 'Consultar', 'Permite consultar/visualizar el recurso', 'system', NOW());
-- ... (insert all 8 actions)

INSERT INTO admin.menu (id, code, name, icon, route, parent_id, order_index, is_active, created_by, created_date)
VALUES (1, 'DASHBOARD', 'Dashboard', 'dashboard', '/dashboard', NULL, 10, true, 'system', NOW());
-- ... (insert all 18 menus)
```

### Priority 2: Verify Liquibase Configuration
- Check if Liquibase is running on startup
- Verify changelog paths are correct
- Check for Liquibase lock issues in `databasechangeloglock` table

### Priority 3: Setup Test User with Permissions
- Create test user in Keycloak with SUPER_ADMIN role
- Verify SUPER_ADMIN has all permissions

---

## ✅ Working Features
- Database connectivity: ✓
- Table structure created: ✓
- Audit logging: ✓ (has 50 logs)
- API endpoints defined: ✓
- Authorization framework: ✓ (requires proper setup)

---

## 📝 Testing Checklist

Once seed data is loaded:

- [ ] **GET /api/v1/admin/actions** - Should return 8 actions
- [ ] **GET /api/v1/admin/menus** - Should return menu tree with 18 items
- [ ] **GET /api/v1/admin/roles** - Should list roles
- [ ] **GET /api/v1/admin/users** - Should list users
- [ ] **GET /api/v1/admin/audit** - Should return audit logs
- [ ] Create new menu item
- [ ] Create new role
- [ ] Assign permissions to role
- [ ] Create user with role
- [ ] Verify audit logs capture all operations

---

## 🚀 Next Steps

1. **Immediately:** Load seed data into database
2. **Setup test user:** Create admin user in Keycloak with proper permissions
3. **Test all endpoints:** Run comprehensive API tests
4. **Verify logs:** Ensure audit trail captures all operations
5. **Document:** Update API documentation with authentication requirements
