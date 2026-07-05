import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import {
  AlertComponent,
  ButtonComponent,
  InputFieldComponent,
  LabelComponent,
  ModalComponent,
  PageBreadcrumbComponent,
  SpinnerComponent,
  DataTableComponent, DataTableColumn, DataTableAction,
} from '@hub/ui';
import { ActionsAdminApi, ActionDto, CreateActionRequest, UpdateActionRequest } from '@hub/sdk';
import { iconToSvg } from '../../../lucide-icon.util';

@Component({
  selector: 'app-acciones-admin',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    ModalComponent,
    InputFieldComponent,
    LabelComponent,
    SpinnerComponent,
    DataTableComponent,],
  templateUrl: './acciones.component.html',
})
export class AccionesAdminComponent implements OnInit {
  private readonly actionsApi = inject(ActionsAdminApi);

  readonly iconPlus = iconToSvg('list');
  readonly iconEdit = iconToSvg('edit');
  readonly iconTrash = iconToSvg('delete');
  readonly iconRefresh = iconToSvg('history');

  columns: DataTableColumn[] = [
    { key: 'code', label: 'Código', sortable: true },
    { key: 'name', label: 'Nombre', sortable: true },
    { key: 'description', label: 'Descripción', sortable: true },
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
    },
  ];

  acciones = signal<ActionDto[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  // Create/Edit modal
  editOpen = signal(false);
  editItem = signal<ActionDto | null>(null);
  editCode = signal('');
  editName = signal('');
  editDescription = signal('');
  editSubmitting = signal(false);
  editError = signal<string | null>(null);

  // Delete confirmation
  deleteOpen = signal(false);
  deleteItem = signal<ActionDto | null>(null);
  deleteError = signal<string | null>(null);

  isMobile = signal(false);

  readonly hayResultados = computed(() => this.acciones().length > 0);

  readonly dataTableConfig = computed(() => ({
    rowKey: this.isMobile() ? 'id' : undefined
  }));

  readonly visibleColumns = computed(() => {
    if (this.isMobile()) {
      return this.columns.filter(col => col.key !== 'actions');
    }
    return this.columns;
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
    this.actionsApi.list().subscribe({
      next: res => {
        this.acciones.set(res);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Error cargando acciones');
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.editItem.set(null);
    this.editCode.set('');
    this.editName.set('');
    this.editDescription.set('');
    this.editError.set(null);
    this.editOpen.set(true);
  }

  openEdit(item: ActionDto): void {
    this.editItem.set(item);
    this.editCode.set(item.code);
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
    const body: CreateActionRequest | UpdateActionRequest = {
      name: this.editName(),
      description: this.editDescription() || undefined,
      ...(isCreate && { code: this.editCode() }),
    };

    this.editSubmitting.set(true);
    this.editError.set(null);

    const call = isCreate
      ? this.actionsApi.create(body as CreateActionRequest)
      : this.actionsApi.update(this.editItem()!.id, body as UpdateActionRequest);

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

  openDelete(item: ActionDto): void {
    this.deleteItem.set(item);
    this.deleteError.set(null);
    this.deleteOpen.set(true);
  }

  closeDelete(): void {
    this.deleteOpen.set(false);
  }

  confirmDelete(): void {
    if (!this.deleteItem()) return;
    this.actionsApi.delete(this.deleteItem()!.id).subscribe({
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
