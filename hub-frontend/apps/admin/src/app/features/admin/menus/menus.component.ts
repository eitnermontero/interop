import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal, viewChild, TemplateRef } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import {
  AlertComponent,
  ButtonComponent,
  InputFieldComponent,
  LabelComponent,
  ModalComponent,
  PageBreadcrumbComponent,
  SelectComponent,
  SpinnerComponent,
  DataTableComponent, DataTableColumn, DataTableAction, Option,
} from '@hub/ui';
import { MenusAdminApi, MenuDto, CreateMenuRequest, UpdateMenuRequest } from '@hub/sdk';
import { iconToSvg } from '../../../lucide-icon.util';

@Component({
  selector: 'app-menus-admin',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    ModalComponent,
    InputFieldComponent,
    LabelComponent,
    SelectComponent,
    SpinnerComponent,
    DataTableComponent, ],
  templateUrl: './menus.component.html',
})
export class MenusAdminComponent implements OnInit {
  private readonly menusApi = inject(MenusAdminApi);
  private readonly translate = inject(TranslateService);

  readonly iconPlus = iconToSvg('list');
  readonly iconEdit = iconToSvg('edit');
  readonly iconTrash = iconToSvg('delete');

  // Celda badge para el flag isActive (template definido en el HTML).
  readonly statusCell = viewChild<TemplateRef<any>>('statusCell');

  readonly columns = computed<DataTableColumn[]>(() => [
    { key: 'code', label: 'Código', sortable: true },
    // El name puede ser una clave i18n (p.ej. SIDEBAR.PENDING); se traduce para mostrar.
    { key: 'name', label: 'Nombre', sortable: true, format: (v: string) => this.translate.instant(v) },
    { key: 'route', label: 'Ruta', sortable: true },
    { key: 'isActive', label: 'Activo', cellTemplate: this.statusCell(), format: (v: boolean) => (v ? 'Activo' : 'Inactivo') },
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
          id: 'delete',
          label: 'Eliminar',
          icon: this.iconTrash,
          variant: 'destructive',
          onClick: (row: any, event: MouseEvent) => this.openDelete(row)
        }
      ]
    }
  ]);

  menus = signal<MenuDto[]>([]);
  flatMenus = signal<MenuDto[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  editOpen = signal(false);
  editItem = signal<MenuDto | null>(null);
  editCode = signal('');
  editName = signal('');
  editIcon = signal('');
  editRoute = signal('');
  editParentId = signal<number | null>(null);
  editOrderIndex = signal(0);
  editIsActive = signal(true);
  editSubmitting = signal(false);
  editError = signal<string | null>(null);

  deleteOpen = signal(false);
  deleteItem = signal<MenuDto | null>(null);
  deleteError = signal<string | null>(null);

  isMobile = signal(false);

  readonly hayResultados = computed(() => this.flatMenus().length > 0);

  readonly dataTableConfig = computed(() => ({
    rowKey: this.isMobile() ? 'id' : undefined
  }));

  readonly visibleColumns = computed(() => {
    if (this.isMobile()) {
      return this.columns().filter(col => col.key !== 'actions');
    }
    return this.columns();
  });

  readonly parentOptions = computed<Option[]>(() => [
    { value: '', label: 'Raíz (sin padre)' },
    ...this.flatMenus()
      .filter(m => m.id !== this.editItem()?.id)
      .map(m => ({ value: this.numToString(m.id), label: this.indent(m) + m.name })),
  ]);

  indent(menu: MenuDto): string {
    let level = 0;
    let current: MenuDto | undefined = menu;
    while (current?.parentId) {
      level++;
      current = this.flatMenus().find(m => m.id === current?.parentId);
    }
    return '  '.repeat(level);
  }

  numToString(val: number | null | undefined): string {
    return val ? val.toString() : '';
  }

  strToNum(val: string | null | undefined): number | null {
    return val ? Number(val) : null;
  }

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
    this.menusApi.list().subscribe({
      next: res => {
        this.menus.set(res);
        this.flatMenus.set(this.flattenMenus(res));
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Error cargando menús');
        this.loading.set(false);
      },
    });
  }

  private flattenMenus(menus: MenuDto[]): MenuDto[] {
    const result: MenuDto[] = [];
    const traverse = (items: MenuDto[]) => {
      for (const item of items) {
        result.push(item);
        if (item.children?.length) traverse(item.children);
      }
    };
    traverse(menus);
    return result;
  }

  openCreate(): void {
    this.editItem.set(null);
    this.editCode.set('');
    this.editName.set('');
    this.editIcon.set('');
    this.editRoute.set('');
    this.editParentId.set(null);
    this.editOrderIndex.set(0);
    this.editIsActive.set(true);
    this.editError.set(null);
    this.editOpen.set(true);
  }

  openEdit(item: MenuDto): void {
    this.editItem.set(item);
    this.editCode.set(item.code);
    this.editName.set(item.name);
    this.editIcon.set(item.icon ?? '');
    this.editRoute.set(item.route ?? '');
    this.editParentId.set(item.parentId);
    this.editOrderIndex.set(item.orderIndex);
    this.editIsActive.set(item.isActive);
    this.editError.set(null);
    this.editOpen.set(true);
  }

  closeEdit(): void {
    this.editOpen.set(false);
  }

  submitEdit(): void {
    const isCreate = !this.editItem();
    const body: CreateMenuRequest | UpdateMenuRequest = {
      name: this.editName(),
      icon: this.editIcon() || undefined,
      route: this.editRoute() || undefined,
      parentId: this.editParentId() || undefined,
      orderIndex: this.editOrderIndex(),
      isActive: this.editIsActive(),
      ...(isCreate && { code: this.editCode() }),
    };

    this.editSubmitting.set(true);
    this.editError.set(null);

    const call = isCreate
      ? this.menusApi.create(body as CreateMenuRequest)
      : this.menusApi.update(this.editItem()!.id, body as UpdateMenuRequest);

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

  openDelete(item: MenuDto): void {
    this.deleteItem.set(item);
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  closeDelete(): void {
    this.deleteOpen.set(false);
  }

  confirmDelete(): void {
    if (!this.deleteItem()) return;
    this.menusApi.delete(this.deleteItem()!.id).subscribe({
      next: () => {
        this.deleteOpen.set(false);
        this.load();
      },
      error: err => {
        this.deleteError.set(err?.error?.detail ?? 'Error al eliminar');
      },
    });
  }
}
