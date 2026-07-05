import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal, viewChild, TemplateRef } from '@angular/core';
import {
  AlertComponent,
  ButtonComponent,
  CheckboxComponent,
  InputFieldComponent,
  LabelComponent,
  ModalComponent,
  PageBreadcrumbComponent,
  SpinnerComponent,
  DataTableComponent, DataTableColumn, DataTableAction } from '@hub/ui';
import { TranslateModule } from '@ngx-translate/core';
import { RolesAdminApi, RoleDto, CreateRoleRequest, UpdateRoleRequest, MenusAdminApi, MenuDto } from '@hub/sdk';
import { iconToSvg } from '../../../lucide-icon.util';

@Component({
  selector: 'app-roles-admin',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    CheckboxComponent,
    InputFieldComponent,
    LabelComponent,
    ModalComponent,
    SpinnerComponent,
    DataTableComponent,
    TranslateModule, ],
  templateUrl: './roles.component.html',
})
export class RolesAdminComponent implements OnInit {
  private readonly rolesApi = inject(RolesAdminApi);
  private readonly menusApi = inject(MenusAdminApi);

  readonly iconPlus = iconToSvg('list');
  readonly iconEdit = iconToSvg('edit');
  readonly iconTrash = iconToSvg('delete');
  readonly iconMenu = iconToSvg('menu');

  readonly compositeCell = viewChild<TemplateRef<any>>('compositeCell');

  readonly columns = computed<DataTableColumn[]>(() => [
    { key: 'name', label: 'Nombre', sortable: true },
    { key: 'description', label: 'Descripción', sortable: true },
    { key: 'composite', label: 'Compuesto', cellTemplate: this.compositeCell(), format: (v: boolean) => (v ? 'Sí' : 'No') },
    {
      key: 'actions',
      label: 'Acciones',
      sortable: false,
      actions: [
        {
          id: 'edit',
          label: 'Editar',
          icon: this.iconEdit,
          variant: 'default',
          onClick: (row: any, event: MouseEvent) => this.openEdit(row)
        },
        {
          id: 'menus',
          label: 'Gestionar Menús',
          icon: this.iconMenu,
          variant: 'default',
          onClick: (row: any, event: MouseEvent) => this.openMenus(row)
        },
        {
          id: 'delete',
          label: 'Eliminar',
          icon: this.iconTrash,
          variant: 'destructive',
          onClick: (row: any, event: MouseEvent) => this.openDelete(row)
        }
      ]
    }
  ]);

  roles = signal<RoleDto[]>([]);
  menus = signal<MenuDto[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  editOpen = signal(false);
  editItem = signal<RoleDto | null>(null);
  editName = signal('');
  editDescription = signal('');
  editSubmitting = signal(false);
  editError = signal<string | null>(null);

  deleteOpen = signal(false);
  deleteItem = signal<RoleDto | null>(null);
  deleteError = signal<string | null>(null);

  menusOpen = signal(false);
  menusItem = signal<RoleDto | null>(null);
  menusLoading = signal(false);
  menusError = signal<string | null>(null);
  menusSaving = signal(false);
  selectedMenus = signal<string[]>([]);

  isMobile = signal(false);

  // Arbol de menus aplanado con su nivel para indentar padres y submenus en el modal.
  readonly flatMenuItems = computed<{ menu: MenuDto; level: number }[]>(() => {
    const out: { menu: MenuDto; level: number }[] = [];
    const walk = (items: MenuDto[], level: number): void => {
      for (const m of items) {
        out.push({ menu: m, level });
        if (m.children?.length) walk(m.children, level + 1);
      }
    };
    walk(this.menus(), 0);
    return out;
  });

  readonly hayResultados = computed(() => this.roles().length > 0);

  readonly dataTableConfig = computed(() => ({
    rowKey: this.isMobile() ? 'id' : undefined
  }));

  readonly visibleColumns = computed(() => {
    if (this.isMobile()) {
      return this.columns().filter(col => col.key !== 'actions');
    }
    return this.columns();
  });

  ngOnInit(): void {
    this.detectMobileScreen();
    this.setupMobileListener();
    this.load();
  }

  private detectMobileScreen(): void {
    const isMobileScreen = window.innerWidth < 768;
    this.isMobile.set(isMobileScreen);
  }

  private setupMobileListener(): void {
    const mediaQuery = window.matchMedia('(max-width: 767px)');
    const handleChange = (e: MediaQueryListEvent) => this.isMobile.set(e.matches);
    mediaQuery.addEventListener('change', handleChange);
  }



  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.rolesApi.list().subscribe({
      next: res => {
        this.roles.set(res);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Error cargando roles');
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.editItem.set(null);
    this.editName.set('');
    this.editDescription.set('');
    this.editError.set(null);
    this.editOpen.set(true);
  }

  openEdit(item: RoleDto): void {
    this.editItem.set(item);
    this.editName.set(item.name);
    this.editDescription.set(item.description ?? '');
    this.editError.set(null);
    this.editOpen.set(true);
  }

  closeEdit(): void {
    this.editOpen.set(false);
  }

  submitEdit(): void {
    const isCreate = !this.editItem();
    const body: CreateRoleRequest | UpdateRoleRequest = {
      description: this.editDescription() || undefined,
      ...(isCreate && { name: this.editName() }),
    };

    this.editSubmitting.set(true);
    this.editError.set(null);

    const call = isCreate
      ? this.rolesApi.create(body as CreateRoleRequest)
      : this.rolesApi.update(this.editItem()!.name, body as UpdateRoleRequest);

    call.subscribe({
      next: () => {
        this.editSubmitting.set(false);
        this.editOpen.set(false);
        this.load();
      },
      error: err => {
        this.editError.set(err?.error?.detail ?? 'Error al guardar');
        this.editSubmitting.set(false);
      },
    });
  }

  openDelete(item: RoleDto): void {
    this.deleteItem.set(item);
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  closeDelete(): void {
    this.deleteOpen.set(false);
  }

  confirmDelete(): void {
    if (!this.deleteItem()) return;
    this.rolesApi.delete(this.deleteItem()!.name).subscribe({
      next: () => {
        this.deleteOpen.set(false);
        this.load();
      },
      error: err => {
        this.deleteError.set(err?.error?.detail ?? 'Error al eliminar');
      },
    });
  }

  openMenus(item: RoleDto): void {
    this.menusItem.set(item);
    this.menusLoading.set(true);
    this.menusError.set(null);
    this.menusOpen.set(true);

    if (this.menus().length === 0) {
      this.menusApi.list().subscribe({
        next: res => {
          this.menus.set(res);
        },
        error: err => {
          this.menusError.set(err?.error?.detail ?? 'Error al cargar menús');
        },
      });
    }

    this.rolesApi.getMenus(item.name).subscribe({
      next: res => {
        this.selectedMenus.set(res);
        this.menusLoading.set(false);
      },
      error: err => {
        this.menusError.set(err?.error?.detail ?? 'Error al cargar menús asignados');
        this.menusLoading.set(false);
      },
    });
  }

  closeMenus(): void {
    this.menusOpen.set(false);
  }

  // Marcar/desmarcar un menu propaga el mismo estado a todos sus descendientes.
  toggleMenu(menuCode: string): void {
    const menu = this.findMenu(menuCode, this.menus());
    const codes = menu ? this.collectCodes(menu) : [menuCode];
    const selected = new Set(this.selectedMenus());
    const willSelect = !selected.has(menuCode);
    for (const code of codes) {
      if (willSelect) selected.add(code);
      else selected.delete(code);
    }
    this.selectedMenus.set([...selected]);
  }

  private findMenu(code: string, items: MenuDto[]): MenuDto | null {
    for (const m of items) {
      if (m.code === code) return m;
      const found = this.findMenu(code, m.children ?? []);
      if (found) return found;
    }
    return null;
  }

  private collectCodes(menu: MenuDto): string[] {
    const codes = [menu.code];
    for (const child of menu.children ?? []) {
      codes.push(...this.collectCodes(child));
    }
    return codes;
  }

  hasMenu(menuCode: string): boolean {
    return this.selectedMenus().includes(menuCode);
  }

  submitMenus(): void {
    if (!this.menusItem()) return;
    this.menusSaving.set(true);
    this.menusError.set(null);

    this.rolesApi.updateMenus(this.menusItem()!.name, { menuCodes: this.selectedMenus() }).subscribe({
      next: () => {
        this.menusSaving.set(false);
        this.menusOpen.set(false);
        this.load();
      },
      error: err => {
        this.menusError.set(err?.error?.detail ?? 'Error al guardar menús');
        this.menusSaving.set(false);
      },
    });
  }
}
