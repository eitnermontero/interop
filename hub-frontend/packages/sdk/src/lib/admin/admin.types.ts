// Users
export interface UserDto {
  id: string;
  username: string;
  email: string;
  firstName: string | null;
  lastName: string | null;
  enabled: boolean;
  emailVerified: boolean;
  createdTimestamp: number;
  attributes: Record<string, string[]>;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  password?: string;
  temporaryPassword?: boolean;
  roles?: string[];
  attributes?: Record<string, string[]>;
}

export interface UpdateUserRequest {
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  attributes?: Record<string, string[]>;
}

export interface ResetPasswordRequest {
  password: string;
  temporary?: boolean;
}

export interface UpdateStatusRequest {
  enabled: boolean;
}

export interface UpdateRolesRequest {
  roles: string[];
}

// Roles
export interface RoleDto {
  id: string;
  name: string;
  description: string | null;
  composite: boolean | null;
}

export interface CreateRoleRequest {
  name: string;
  description?: string;
}

export interface UpdateRoleRequest {
  description?: string;
}

// Menus
export interface MenuDto {
  id: number;
  code: string;
  name: string;
  icon: string | null;
  route: string | null;
  parentId: number | null;
  orderIndex: number;
  isActive: boolean;
  children: MenuDto[];
}

export interface CreateMenuRequest {
  code: string;
  name: string;
  icon?: string;
  route?: string;
  parentId?: number;
  orderIndex?: number;
  isActive?: boolean;
}

export interface UpdateMenuRequest {
  name?: string;
  icon?: string;
  route?: string;
  parentId?: number;
  orderIndex?: number;
  isActive?: boolean;
}

// Actions
export interface ActionDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
}

export interface CreateActionRequest {
  code: string;
  name: string;
  description?: string;
}

export interface UpdateActionRequest {
  name?: string;
  description?: string;
}

// Permissions
export interface RolePermissionsResponse {
  roleName: string;
  permissions: Array<{
    menuCode: string;
    menuName: string;
    actions: string[];
  }>;
}

export interface SetPermissionsRequest {
  permissions: Array<{
    menuCode: string;
    actions: string[];
  }>;
}

// Audit
export interface AuditLogDto {
  id: number;
  eventTime: string;
  eventType: string;
  module: string;
  optionCode: string | null;
  userId: string | null;
  username: string | null;
  fullName: string | null;
  roles: string[] | null;
  ipAddress: string | null;
  userAgent: string | null;
  serviceName: string;
  httpMethod: string | null;
  endpoint: string | null;
  responseStatus: number | null;
  durationMs: number | null;
  details: Record<string, unknown> | null;
}

export interface AuditLogFilter {
  from?: string;
  to?: string;
  username?: string;
  userId?: string;
  eventTypes?: string[];
  modules?: string[];
  serviceName?: string;
  ipAddress?: string;
  responseStatuses?: number[];
  q?: string;
}

// Auth / Me / Permissions
export interface MeResponse {
  id: string;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  fullName: string;
  roles: string[];
}

export interface UserInfo {
  id: string;
  username: string;
  email: string | null;
  fullName: string;
  roles: string[];
}

export interface MenuNode {
  code: string;
  name: string;
  icon: string | null;
  route: string | null;
  actions: string[];
  children: MenuNode[];
}

export interface PermissionsTreeResponse {
  user: UserInfo;
  menus: MenuNode[];
}
