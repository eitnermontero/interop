import { CommonModule } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import {
  AlertComponent,
  ButtonComponent,
  CheckboxComponent,
  LabelComponent,
  PageBreadcrumbComponent,
  SelectComponent,
  SpinnerComponent,
  Option,
} from '@hub/ui';
import { RolesAdminApi, PermissionsAdminApi, MenusAdminApi, ActionsAdminApi, RoleDto, MenuDto } from '@hub/sdk';

interface MenuRow {
  menu: MenuDto;
  level: number;
}

@Component({
  selector: 'app-permisos-admin',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    CheckboxComponent,
    LabelComponent,
    SelectComponent,
    SpinnerComponent,
    TranslateModule,
  ],
  templateUrl: './permisos.component.html',
})
export class PermisosAdminComponent implements OnInit {
  private readonly rolesApi = inject(RolesAdminApi);
  private readonly permissionsApi = inject(PermissionsAdminApi);
  private readonly menusApi = inject(MenusAdminApi);
  private readonly actionsApi = inject(ActionsAdminApi);

  // Orden preferido de columnas; las acciones desconocidas se anexan al final.
  private readonly actionOrder = ['READ', 'CREATE', 'UPDATE', 'DELETE', 'APPROVE', 'VOID', 'EXPORT', 'PRINT'];

  roles = signal<RoleDto[]>([]);
  menuRows = signal<MenuRow[]>([]);
  selectedRole = signal<string>('');
  permissionsByMenu = signal<Record<string, string[]>>({});
  allActions = signal<string[]>(['READ', 'CREATE', 'UPDATE', 'DELETE', 'EXPORT', 'PRINT']);

  loading = signal(false);
  permsLoading = signal(false);
  error = signal<string | null>(null);
  saving = signal(false);
  saveError = signal<string | null>(null);
  saveSuccess = signal(false);

  readonly iconSave = `<svg width="1em" height="1em" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z" fill="currentColor"/></svg>`;

  readonly roleOptions = computed<Option[]>(() =>
    this.roles().map(r => ({ value: r.name, label: r.name }))
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    Promise.all([
      this.rolesApi.list().toPromise(),
      this.menusApi.list().toPromise(),
      this.actionsApi.list().toPromise(),
    ]).then(([rolesRes, menusRes, actionsRes]) => {
      this.roles.set(rolesRes!);
      this.menuRows.set(this.flattenMenus(menusRes!));
      if (actionsRes && actionsRes.length) {
        this.allActions.set(this.sortActions(actionsRes.map(a => a.code)));
      }
      this.loading.set(false);
      if (rolesRes!.length > 0) {
        this.selectRole(rolesRes![0].name);
      }
    }).catch(() => {
      this.error.set('Error cargando datos');
      this.loading.set(false);
    });
  }

  private sortActions(codes: string[]): string[] {
    return [...codes].sort((a, b) => {
      const ia = this.actionOrder.indexOf(a);
      const ib = this.actionOrder.indexOf(b);
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib) || a.localeCompare(b);
    });
  }

  private flattenMenus(menus: MenuDto[], level = 0, out: MenuRow[] = []): MenuRow[] {
    for (const menu of menus) {
      out.push({ menu, level });
      if (menu.children?.length) this.flattenMenus(menu.children, level + 1, out);
    }
    return out;
  }

  selectRole(roleName: string): void {
    this.selectedRole.set(roleName);
    this.permsLoading.set(true);
    this.error.set(null);
    this.saveSuccess.set(false);
    this.permissionsApi.getByRole(roleName).subscribe({
      next: res => {
        const byMenu: Record<string, string[]> = {};
        for (const perm of res.permissions) {
          byMenu[perm.menuCode] = [...perm.actions];
        }
        this.permissionsByMenu.set(byMenu);
        this.permsLoading.set(false);
      },
      error: () => {
        this.permissionsByMenu.set({});
        this.permsLoading.set(false);
      },
    });
  }

  hasAction(menuCode: string, action: string): boolean {
    return (this.permissionsByMenu()[menuCode] ?? []).includes(action);
  }

  toggleAction(menuCode: string, action: string): void {
    this.saveSuccess.set(false);
    const current = this.permissionsByMenu()[menuCode] ?? [];
    const updated = current.includes(action) ? current.filter(a => a !== action) : [...current, action];
    this.permissionsByMenu.update(perms => ({ ...perms, [menuCode]: updated }));
  }

  // Selecciona/deselecciona todas las acciones de un menu (fila).
  rowAllChecked(menuCode: string): boolean {
    const current = this.permissionsByMenu()[menuCode] ?? [];
    return this.allActions().length > 0 && this.allActions().every(a => current.includes(a));
  }

  toggleRow(menuCode: string): void {
    this.saveSuccess.set(false);
    const allOn = this.rowAllChecked(menuCode);
    this.permissionsByMenu.update(perms => ({
      ...perms,
      [menuCode]: allOn ? [] : [...this.allActions()],
    }));
  }

  // Selecciona/deselecciona una accion para todos los menus (columna).
  columnAllChecked(action: string): boolean {
    const rows = this.menuRows();
    return rows.length > 0 && rows.every(r => (this.permissionsByMenu()[r.menu.code] ?? []).includes(action));
  }

  toggleColumn(action: string): void {
    this.saveSuccess.set(false);
    const allOn = this.columnAllChecked(action);
    this.permissionsByMenu.update(perms => {
      const next = { ...perms };
      for (const { menu } of this.menuRows()) {
        const current = next[menu.code] ?? [];
        if (allOn) {
          next[menu.code] = current.filter(a => a !== action);
        } else if (!current.includes(action)) {
          next[menu.code] = [...current, action];
        }
      }
      return next;
    });
  }

  save(): void {
    if (!this.selectedRole()) return;
    const permissions = Object.entries(this.permissionsByMenu())
      .filter(([, actions]) => actions.length > 0)
      .map(([menuCode, actions]) => ({ menuCode, actions }));

    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);
    this.permissionsApi.setByRole(this.selectedRole(), { permissions }).subscribe({
      next: () => {
        this.saving.set(false);
        this.saveSuccess.set(true);
      },
      error: err => {
        this.saveError.set(err?.error?.detail ?? 'Error al guardar');
        this.saving.set(false);
      },
    });
  }
}
