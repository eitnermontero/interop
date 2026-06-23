# Role Management UI Guide

**Visual Guide to the New Role Management Feature**

---

## 📊 Screen 1: Usuarios List View

```
┌─────────────────────────────────────────────────────────────────┐
│  Usuarios                                    [+ Nuevo Usuario]  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Search: [Buscar por usuario o email...]                      │
│                                                                 │
│  ┌─────────────┬─────────────────┬──────────────┬─────────┐    │
│  │ Usuario     │ Email           │ Nombre       │ Estado  │    │
│  ├─────────────┼─────────────────┼──────────────┼─────────┤    │
│  │ admin       │ admin@host      │ Admin User   │ ✓ Activo│    │
│  │ Actions:    │                 │              │         │    │
│  │ [✎ Editar]  [🔒 Roles]  [🔑 Contraseña]  [🗑 Eliminar]  │
│  │             │                 │              │         │    │
│  ├─────────────┼─────────────────┼──────────────┼─────────┤    │
│  │ jose        │ jose@host       │ José Pérez   │ ✓ Activo│    │
│  │ Actions:    │                 │              │         │    │
│  │ [✎ Editar]  [🔒 Roles] ← NEW! [🔑 Contraseña]  [🗑 Eliminar]  │
│  │             │                 │              │         │    │
│  └─────────────┴─────────────────┴──────────────┴─────────┘    │
│                                                                 │
│  Página 1 de 3                     [Anterior] [Siguiente]      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔒 Screen 2: Role Management Modal (Before Selection)

```
┌──────────────────────────────────────────────────────┐
│  X                                                   │
│  Gestionar Roles                                     │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Usuario: jose                                       │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │ □ SUPER_ADMIN                                  │ │
│  │   Acceso completo a todas las funciones       │ │
│  │                                                 │ │
│  │ □ AUDITOR                                       │ │
│  │   Solo lectura a auditoría y reportes         │ │
│  │                                                 │ │
│  │ ☑ default-roles-middleware-core (CURRENT)     │ │
│  │   Rol predeterminado para todos los usuarios  │ │
│  │                                                 │ │
│  │ □ cart:read                                     │ │
│  │   Lectura de carrito de compras                │ │
│  │                                                 │ │
│  │ □ cart:write                                    │ │
│  │   Crear y editar órdenes                       │ │
│  │                                                 │ │
│  │ □ cart:pay                                      │ │
│  │   Efectuar pagos                               │ │
│  │                                                 │ │
│  │ □ soboce:operador                              │ │
│  │   Operaciones básicas SOBOCE                   │ │
│  │                                                 │ │
│  │ □ soboce:gestor                                │ │
│  │   Gestión completa de SOBOCE                   │ │
│  └────────────────────────────────────────────────┘ │
│       (Max-height scrollable, ~8 roles visible)     │
│                                                      │
│  [Cancelar]                         [Guardar Roles] │
└──────────────────────────────────────────────────────┘
```

---

## ✏️ Screen 3: Role Management Modal (After Selection)

```
┌──────────────────────────────────────────────────────┐
│  X                                                   │
│  Gestionar Roles                                     │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Usuario: jose                                       │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │ ☑ SUPER_ADMIN  ← NEWLY SELECTED              │ │
│  │   Acceso completo a todas las funciones       │ │
│  │                                                 │ │
│  │ ☑ AUDITOR  ← NEWLY SELECTED                  │ │
│  │   Solo lectura a auditoría y reportes         │ │
│  │                                                 │ │
│  │ ☑ default-roles-middleware-core (CURRENT)     │ │
│  │   Rol predeterminado para todos los usuarios  │ │
│  │                                                 │ │
│  │ □ cart:read                                     │ │
│  │   Lectura de carrito de compras                │ │
│  │                                                 │ │
│  │ □ cart:write                                    │ │
│  │   Crear y editar órdenes                       │ │
│  │                                                 │ │
│  │ ☑ cart:pay  ← NEWLY SELECTED                 │ │
│  │   Efectuar pagos                               │ │
│  │                                                 │ │
│  │ □ soboce:operador                              │ │
│  │   Operaciones básicas SOBOCE                   │ │
│  │                                                 │ │
│  │ □ soboce:gestor                                │ │
│  │   Gestión completa de SOBOCE                   │ │
│  └────────────────────────────────────────────────┘ │
│                                                      │
│  [Cancelar]                    [Guardar Roles] ← ENABLED
└──────────────────────────────────────────────────────┘
```

---

## ⏳ Screen 4: Saving State

```
┌──────────────────────────────────────────────────────┐
│  X                                                   │
│  Gestionar Roles                                     │
├──────────────────────────────────────────────────────┤
│                                                      │
│  Usuario: jose                                       │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │ ☑ SUPER_ADMIN (disabled during save)           │ │
│  │   Acceso completo a todas las funciones       │ │
│  │                                                 │ │
│  │ ☑ AUDITOR (disabled during save)              │ │
│  │   Solo lectura a auditoría y reportes         │ │
│  │   ...                                           │ │
│  └────────────────────────────────────────────────┘ │
│                                                      │
│  [Cancelar (disabled)]  [Guardando... (disabled)]   │
│                         ⏳ Sending to API...
└──────────────────────────────────────────────────────┘
```

---

## ✅ Screen 5: Success State

```
Modal closes automatically after successful save
↓
Returns to Usuarios list
↓
List refreshes with updated user data
```

---

## ❌ Screen 6: Error State

```
┌──────────────────────────────────────────────────────┐
│  X                                                   │
│  Gestionar Roles                                     │
├──────────────────────────────────────────────────────┤
│                                                      │
│  ⚠️ Error: El usuario no tiene permisos              │
│     para asignar el rol SUPER_ADMIN                  │
│                                                      │
│  Usuario: jose                                       │
│                                                      │
│  ┌────────────────────────────────────────────────┐ │
│  │ ☑ SUPER_ADMIN (retained selection for retry)  │ │
│  │   Acceso completo a todas las funciones       │ │
│  │                                                 │ │
│  │ ☑ AUDITOR                                      │ │
│  │   Solo lectura a auditoría y reportes         │ │
│  │   ...                                           │ │
│  └────────────────────────────────────────────────┘ │
│                                                      │
│  [Cancelar]                      [Guardar Roles]    │
│  (user can retry or cancel)                         │
└──────────────────────────────────────────────────────┘
```

---

## 🎨 Component Hierarchy

```
UsuariosAdminComponent
├── Page Header
│   ├── Title: "Usuarios"
│   └── Action: "+ Nuevo Usuario"
│
├── Search Bar
│   └── Input: "Buscar por usuario o email..."
│
├── Usuarios Table
│   ├── Columns:
│   │   ├─ Usuario (username)
│   │   ├─ Email
│   │   ├─ Nombre (firstName + lastName)
│   │   ├─ Estado (enabled badge)
│   │   └─ Acciones (buttons)
│   │
│   ├── Action Buttons (per row):
│   │   ├─ [✎ Editar]        → editOpen modal
│   │   ├─ [🔒 Roles]        → rolesOpen modal (NEW)
│   │   ├─ [🔑 Contraseña]   → passwordOpen modal
│   │   └─ [🗑 Eliminar]     → deleteOpen modal
│   │
│   └── Pagination Controls
│       ├─ Current page display
│       ├─ [Anterior] button
│       └─ [Siguiente] button
│
├── Edit Modal (existing)
│   ├── Title: "Editar Usuario" / "Nuevo Usuario"
│   ├── Fields: username, email, firstName, lastName, enabled
│   └── Actions: [Cancelar] [Guardar]
│
├── Password Modal (existing)
│   ├── Title: "Cambiar Contraseña"
│   ├── Fields: password, temporary checkbox
│   └── Actions: [Cancelar] [Cambiar]
│
├── Delete Modal (existing)
│   ├── Title: "Confirmar eliminación"
│   ├── Message: Confirm delete
│   └── Actions: [Cancelar] [Eliminar]
│
└── Roles Modal (NEW)
    ├── Title: "Gestionar Roles"
    ├── Display: Current user username
    ├── Loading State: "Cargando roles..."
    ├── Role Checkboxes:
    │   ├─ Role name
    │   ├─ Role description
    │   ├─ Checkbox (checked/unchecked)
    │   └─ Hover effect
    ├── Empty State: "No hay roles disponibles"
    └── Actions: [Cancelar] [Guardar Roles]
```

---

## 📱 Responsive Behavior

### Desktop (≥ 1024px)
```
┌─────────────────────────────────────────────────────────────┐
│ Full width table with all columns visible                   │
│ Modal: max-w-lg (512px) centered                           │
│ Button row: Horizontal inline layout                       │
└─────────────────────────────────────────────────────────────┘
```

### Tablet (640px - 1024px)
```
┌──────────────────────────────────┐
│ Table with scrollable columns    │
│ Modal: Full width with margins   │
│ Button row: Still horizontal     │
│ Font sizes: Slightly reduced     │
└──────────────────────────────────┘
```

### Mobile (< 640px)
```
┌──────────────────┐
│ Stacked table    │
│ each column      │
│ on new row       │
│                  │
│ Modal:           │
│ Full viewport    │
│ minus margins    │
│                  │
│ Buttons:         │
│ Vertical stack   │
└──────────────────┘
```

---

## ⌨️ Keyboard Navigation

```
Tab Order:
1. "Nuevo Usuario" button
2. Search input
3. First user's "Editar" button
4. First user's "Roles" button (NEW)
5. First user's "Contraseña" button
6. First user's "Eliminar" button
... (repeat for each user)
N. Pagination buttons

In Modal:
1. Modal container (focus trap)
2. First role checkbox
3. Second role checkbox
... (all role checkboxes)
N. "Cancelar" button
N+1. "Guardar Roles" button

Escape Key: Close modal (closeRoles())
Enter Key: In checkbox = toggle, In button = click action
```

---

## 🎯 User Interactions

### Happy Path Flow
```
1. Admin navigates to Usuarios
2. Table loads, displays users
3. Admin clicks "Roles" on a user
   → Modal opens
   → Loading spinner appears
   → API call: GET /api/v1/admin/users/{id}/roles
   → Spinner disappears
   → Role checkboxes render
   → Current roles are checked
4. Admin checks/unchecks roles
   → UI updates immediately (no API call)
5. Admin clicks "Guardar Roles"
   → Button becomes disabled
   → Button text changes to "Guardando..."
   → Checkboxes become disabled
   → API call: PUT /api/v1/admin/users/{id}/roles
6. Server responds with 204
   → Modal closes
   → User list refreshes
   → Updated roles persisted

Total time: ~2 seconds (for API round-trips)
```

### Error Flow
```
1. Admin clicks "Roles"
2. Loading spinner shows
3. API call fails with error
   → Error message displays
   → Spinner disappears
4. Admin can:
   a) Retry by clicking "Guardar Roles" again
   b) Click "Cancelar" to close and try later
   c) Check network/server status
```

### Edge Case: No Roles Available
```
Modal opens → Spinner disappears → Message: "No hay roles disponibles"
This should never happen in production (admin service should have
at least SUPER_ADMIN role), but it's handled gracefully.
```

---

## 🎨 CSS Classes Used

```
Modal Container:
- max-w-lg       (max 512px)
- mx-4           (16px horizontal margin)
- p-6 lg:p-8     (24px padding, 32px on lg screens)

Role List Container:
- space-y-3      (12px gap between items)
- max-h-64       (16rem = 256px max height)
- overflow-y-auto (scrollable if roles exceed height)

Role Item:
- flex           (flex container)
- items-center   (center items vertically)
- gap-3          (12px gap)
- p-3            (12px padding)
- rounded        (4px border radius)
- border         (1px border)
- border-gray-200(light gray border)
- hover:bg-gray-50 (light gray on hover)
- cursor-pointer (show pointer)

Checkbox:
- w-4 h-4       (16x16px)
- rounded       (4px border radius)
- [disabled]:... (style when disabled)

Button Container:
- flex          (flex container)
- justify-end   (align right)
- gap-2         (8px gap)
- pt-4          (12px top padding)
- border-t      (top border separator)
```

---

## 🌐 Internationalization (i18n)

Current implementation uses Spanish labels. For multi-language support:

```typescript
// Current (Spanish)
'Gestionar Roles'
'Cargando roles...'
'No hay roles disponibles'
'Guardar Roles'
'Error al cargar roles'
'Error al guardar roles'

// For English (example)
'Manage Roles'
'Loading roles...'
'No roles available'
'Save Roles'
'Error loading roles'
'Error saving roles'

// Implementation: Use i18nService
private readonly i18n = inject(I18nService);
const title = this.i18n.t('admin.roles.title');
```

---

## 📊 State Management Diagram

```
Component State:
┌─────────────────────────────────────────┐
│ rolesOpen = false                       │ ← Hidden
│ rolesItem = null                        │
│ rolesLoading = false                    │
│ rolesError = null                       │
│ rolesSaving = false                     │
│ selectedRoles = []                      │
└─────────────────────────────────────────┘
          ↓ User clicks "Roles"
          ↓ openRoles(item)
┌─────────────────────────────────────────┐
│ rolesOpen = true                        │ ← Modal visible
│ rolesItem = { id: '123', ... }          │
│ rolesLoading = true                     │ ← Loading
│ rolesError = null                       │
│ rolesSaving = false                     │
│ selectedRoles = []                      │
└─────────────────────────────────────────┘
          ↓ API returns roles
          ↓ next() callback
┌─────────────────────────────────────────┐
│ rolesOpen = true                        │ ← Modal visible
│ rolesItem = { id: '123', ... }          │
│ rolesLoading = false                    │ ← Loaded
│ rolesError = null                       │
│ rolesSaving = false                     │
│ selectedRoles = ['SUPER_ADMIN']         │ ← Populated from API
└─────────────────────────────────────────┘
          ↓ User toggles checkboxes
          ↓ toggleRole()
┌─────────────────────────────────────────┐
│ rolesOpen = true                        │
│ rolesItem = { id: '123', ... }          │
│ rolesLoading = false                    │
│ rolesError = null                       │
│ rolesSaving = false                     │
│ selectedRoles = ['SUPER_ADMIN',         │ ← User selections
│                  'AUDITOR', ...]        │
└─────────────────────────────────────────┘
          ↓ User clicks "Guardar"
          ↓ submitRoles()
┌─────────────────────────────────────────┐
│ rolesOpen = true                        │
│ rolesItem = { id: '123', ... }          │
│ rolesLoading = false                    │
│ rolesError = null                       │
│ rolesSaving = true                      │ ← Saving
│ selectedRoles = ['SUPER_ADMIN', ...]    │
└─────────────────────────────────────────┘
          ↓ API returns 204
          ↓ next() callback
┌─────────────────────────────────────────┐
│ rolesOpen = false                       │ ← Modal hidden (closed)
│ rolesItem = null                        │ ← Reset
│ rolesLoading = false                    │
│ rolesError = null                       │
│ rolesSaving = false                     │ ← Reset
│ selectedRoles = []                      │ ← Reset
└─────────────────────────────────────────┘
          ↓ Usuario list refreshed
```

---

**Visual Design:** Material Design + Tailwind CSS  
**Accessibility:** WCAG 2.1 AA compliant  
**Browser Support:** Modern browsers (Chrome, Firefox, Safari, Edge)

