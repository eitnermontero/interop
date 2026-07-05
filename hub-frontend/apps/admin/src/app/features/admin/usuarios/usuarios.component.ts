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
import { UsersAdminApi, RolesAdminApi, UserDto, CreateUserRequest, UpdateUserRequest, RoleDto, ResetPasswordRequest } from '@hub/sdk';
import { PageResponse } from '@hub/sdk';
import { iconToSvg } from '../../../lucide-icon.util';

@Component({
  selector: 'app-usuarios-admin',
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
    DataTableComponent, ],
  templateUrl: './usuarios.component.html',
})
export class UsuariosAdminComponent implements OnInit {
  private readonly usersApi = inject(UsersAdminApi);
  private readonly rolesApi = inject(RolesAdminApi);

  readonly iconPlus = iconToSvg('list');
  readonly iconEdit = iconToSvg('edit');
  readonly iconKey = iconToSvg('vpn_key');
  readonly iconTrash = iconToSvg('delete');
  readonly iconShield = iconToSvg('admin_panel_settings');

  readonly statusCell = viewChild<TemplateRef<any>>('statusCell');

  readonly columns = computed<DataTableColumn[]>(() => [
    { key: 'username', label: 'Usuario', sortable: true },
    { key: 'email', label: 'Email', sortable: true },
    { key: 'firstName', label: 'Nombre' },
    { key: 'lastName', label: 'Apellido' },
    { key: 'enabled', label: 'Estado', cellTemplate: this.statusCell(), format: (v: boolean) => (v ? 'Activo' : 'Inactivo') },
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
          id: 'password',
          label: 'Cambiar Contraseña',
          icon: this.iconKey,
          variant: 'default',
          onClick: (row: any, event: MouseEvent) => this.openPassword(row)
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

  usuarios = signal<UserDto[]>([]);
  roles = signal<RoleDto[]>([]);
  page = signal(0);
  totalPages = signal(0);
  loading = signal(false);
  error = signal<string | null>(null);

  search = signal('');
  enabledFilter = signal<boolean | null>(null);

  editOpen = signal(false);
  editItem = signal<UserDto | null>(null);
  editUsername = signal('');
  editEmail = signal('');
  editFirstName = signal('');
  editLastName = signal('');
  editPassword = signal('');
  editPasswordConfirm = signal('');
  editEnabled = signal(true);
  editSelectedRoles = signal<string[]>([]);
  editSubmitting = signal(false);
  editError = signal<string | null>(null);

  passwordOpen = signal(false);
  passwordItem = signal<UserDto | null>(null);
  passwordNew = signal('');
  passwordTemporary = signal(false);
  passwordSubmitting = signal(false);
  passwordError = signal<string | null>(null);

  deleteOpen = signal(false);
  deleteItem = signal<UserDto | null>(null);
  deleteError = signal<string | null>(null);

  rolesOpen = signal(false);
  rolesItem = signal<UserDto | null>(null);
  rolesLoading = signal(false);
  rolesError = signal<string | null>(null);
  rolesSaving = signal(false);

  isMobile = signal(false);

  readonly dataTableConfig = computed(() => ({
    rowKey: this.isMobile() ? 'id' : undefined
  }));

  readonly visibleColumns = computed(() => {
    if (this.isMobile()) {
      return this.columns().filter(col => col.key !== 'actions');
    }
    return this.columns();
  });
  selectedRoles = signal<string[]>([]);

  readonly hayResultados = computed(() => this.usuarios().length > 0);
  readonly pageDisplay = computed(() => this.page() + 1);

  ngOnInit(): void {
    this.detectMobileScreen();
    this.setupMobileListener();
    this.loadRoles();
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



  loadRoles(): void {
    this.rolesApi.list().subscribe({
      next: res => this.roles.set(res),
      error: () => {},
    });
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.usersApi.list({ search: this.search() || undefined, enabled: this.enabledFilter() ?? undefined, page: this.page(), size: 20 }).subscribe({
      next: res => {
        this.usuarios.set(res.content);
        this.totalPages.set(res.totalPages);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Error cargando usuarios');
        this.loading.set(false);
      },
    });
  }

  onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
    this.load();
  }

  onPrev(): void {
    if (this.page() > 0) {
      this.page.update(p => p - 1);
      this.load();
    }
  }

  onNext(): void {
    if (!this.isLast()) {
      this.page.update(p => p + 1);
      this.load();
    }
  }

  isLast(): boolean {
    return this.page() >= this.totalPages() - 1;
  }

  openCreate(): void {
    this.editItem.set(null);
    this.editUsername.set('');
    this.editEmail.set('');
    this.editFirstName.set('');
    this.editLastName.set('');
    this.editPassword.set('');
    this.editPasswordConfirm.set('');
    this.editEnabled.set(true);
    this.editSelectedRoles.set([]);
    this.editError.set(null);
    this.editOpen.set(true);
  }

  openEdit(item: UserDto): void {
    this.editItem.set(item);
    this.editUsername.set(item.username);
    this.editEmail.set(item.email);
    this.editFirstName.set(item.firstName ?? '');
    this.editLastName.set(item.lastName ?? '');
    this.editPassword.set('');
    this.editPasswordConfirm.set('');
    this.editEnabled.set(item.enabled);
    this.editError.set(null);

    this.usersApi.getRoles(item.id).subscribe({
      next: res => {
        this.editSelectedRoles.set(res);
      },
      error: () => {
        this.editSelectedRoles.set([]);
      },
    });

    this.editOpen.set(true);
  }

  closeEdit(): void {
    this.editOpen.set(false);
  }

  submitEdit(): void {
    const isCreate = !this.editItem();

    if (isCreate) {
      if (!this.editPassword() || !this.editPasswordConfirm()) {
        this.editError.set('Las contraseñas son requeridas para nuevos usuarios');
        return;
      }
      if (this.editPassword() !== this.editPasswordConfirm()) {
        this.editError.set('Las contraseñas no coinciden');
        return;
      }
    }

    const body: CreateUserRequest | UpdateUserRequest = {
      email: this.editEmail(),
      firstName: this.editFirstName() || undefined,
      lastName: this.editLastName() || undefined,
      enabled: this.editEnabled(),
      ...(isCreate && { username: this.editUsername(), password: this.editPassword() }),
    };

    this.editSubmitting.set(true);
    this.editError.set(null);

    const call = isCreate
      ? this.usersApi.create(body as CreateUserRequest)
      : this.usersApi.update(this.editItem()!.id, body as UpdateUserRequest);

    call.subscribe({
      next: (createdUser?: any) => {
        const userId = isCreate ? createdUser?.id : this.editItem()!.id;

        if (this.editSelectedRoles().length > 0 && userId) {
          this.usersApi.updateRoles(userId, { roles: this.editSelectedRoles() }).subscribe({
            next: () => {
              this.editSubmitting.set(false);
              this.editOpen.set(false);
              this.load();
            },
            error: err => {
              this.editError.set(err?.error?.detail ?? 'Error al guardar roles');
              this.editSubmitting.set(false);
            },
          });
        } else {
          this.editSubmitting.set(false);
          this.editOpen.set(false);
          this.load();
        }
      },
      error: err => {
        this.editError.set(err?.error?.detail ?? 'Error al guardar');
        this.editSubmitting.set(false);
      },
    });
  }

  openPassword(item: UserDto): void {
    this.passwordItem.set(item);
    this.passwordNew.set('');
    this.passwordTemporary.set(true);
    this.passwordError.set(null);
    this.passwordOpen.set(true);
  }

  closePassword(): void {
    this.passwordOpen.set(false);
  }

  submitPassword(): void {
    if (!this.passwordItem()) return;
    const body: ResetPasswordRequest = {
      password: this.passwordNew(),
      temporary: this.passwordTemporary(),
    };
    this.passwordSubmitting.set(true);
    this.usersApi.resetPassword(this.passwordItem()!.id, body).subscribe({
      next: () => {
        this.passwordSubmitting.set(false);
        this.passwordOpen.set(false);
      },
      error: err => {
        this.passwordError.set(err?.error?.detail ?? 'Error al cambiar contraseña');
        this.passwordSubmitting.set(false);
      },
    });
  }

  openDelete(item: UserDto): void {
    this.deleteItem.set(item);
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  closeDelete(): void {
    this.deleteOpen.set(false);
  }

  confirmDelete(): void {
    if (!this.deleteItem()) return;
    this.usersApi.delete(this.deleteItem()!.id).subscribe({
      next: () => {
        this.deleteOpen.set(false);
        this.load();
      },
      error: err => {
        this.deleteError.set(err?.error?.detail ?? 'Error al eliminar');
      },
    });
  }

  openRoles(item: UserDto): void {
    this.rolesItem.set(item);
    this.rolesError.set(null);
    this.rolesLoading.set(true);
    this.rolesOpen.set(true);

    this.usersApi.getRoles(item.id).subscribe({
      next: res => {
        this.selectedRoles.set(res);
        this.rolesLoading.set(false);
      },
      error: err => {
        this.rolesError.set(err?.error?.detail ?? 'Error al cargar roles');
        this.rolesLoading.set(false);
      },
    });
  }

  closeRoles(): void {
    this.rolesOpen.set(false);
  }

  toggleRole(roleName: string): void {
    this.selectedRoles.update(roles =>
      roles.includes(roleName)
        ? roles.filter(r => r !== roleName)
        : [...roles, roleName]
    );
  }

  hasRole(roleName: string): boolean {
    return this.selectedRoles().includes(roleName);
  }

  submitRoles(): void {
    const item = this.rolesItem();
    if (!item) return;

    this.rolesSaving.set(true);
    this.rolesError.set(null);

    this.usersApi.updateRoles(item.id, { roles: this.selectedRoles() }).subscribe({
      next: () => {
        this.rolesSaving.set(false);
        this.rolesOpen.set(false);
        this.load();
      },
      error: err => {
        this.rolesError.set(err?.error?.detail ?? 'Error al guardar roles');
        this.rolesSaving.set(false);
      },
    });
  }

  toggleEditRole(roleName: string): void {
    this.editSelectedRoles.update(roles =>
      roles.includes(roleName)
        ? roles.filter(r => r !== roleName)
        : [...roles, roleName]
    );
  }

  hasEditRole(roleName: string): boolean {
    return this.editSelectedRoles().includes(roleName);
  }
}
