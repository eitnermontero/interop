import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: 'reportes',
    loadComponent: () =>
      import('./reportes/reportes.component').then(
        (m) => m.ReportesAdminComponent,
      ),
    title: 'Administración de Reportes',
  },
  {
    path: 'usuarios',
    loadComponent: () =>
      import('./usuarios/usuarios.component').then(
        (m) => m.UsuariosAdminComponent,
      ),
    title: 'Administración de Usuarios',
  },
  {
    path: 'roles',
    loadComponent: () =>
      import('./roles/roles.component').then(
        (m) => m.RolesAdminComponent,
      ),
    title: 'Administración de Roles',
  },
  {
    path: 'menus',
    loadComponent: () =>
      import('./menus/menus.component').then(
        (m) => m.MenusAdminComponent,
      ),
    title: 'Administración de Menús',
  },
  {
    path: 'acciones',
    loadComponent: () =>
      import('./acciones/acciones.component').then(
        (m) => m.AccionesAdminComponent,
      ),
    title: 'Administración de Acciones',
  },
  {
    path: 'permisos',
    loadComponent: () =>
      import('./permisos/permisos.component').then(
        (m) => m.PermisosAdminComponent,
      ),
    title: 'Matriz de Permisos',
  },
  {
    path: 'auditoria',
    loadComponent: () =>
      import('./auditoria/auditoria.component').then(
        (m) => m.AuditoriaAdminComponent,
      ),
    title: 'Auditoría',
  },
];
