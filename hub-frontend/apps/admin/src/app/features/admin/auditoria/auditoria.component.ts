import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
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
import { AuditAdminApi, AuditLogDto } from '@hub/sdk';
import { iconToSvg } from '../../../lucide-icon.util';

@Component({
  selector: 'app-auditoria-admin',
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
  templateUrl: './auditoria.component.html',
})
export class AuditoriaAdminComponent implements OnInit {
  private readonly auditApi = inject(AuditAdminApi);

  readonly iconDownload = iconToSvg('payment');
  readonly iconRefresh = iconToSvg('history');
  readonly iconEye = iconToSvg('view');

  columns: DataTableColumn[] = [
    { key: 'eventTime', label: 'Hora', sortable: true },
    { key: 'username', label: 'Usuario', sortable: true },
    { key: 'eventType', label: 'Tipo Evento', sortable: true },
    { key: 'module', label: 'Módulo', sortable: true },
    { key: 'responseStatus', label: 'Estado HTTP' },
    {
      key: 'actions',
      label: 'Acciones',
      sortable: false,
      actions: [
        {
          id: 'view',
          label: 'Ver',
          icon: this.iconEye,
          variant: 'default',
          onClick: (row: any, event: MouseEvent) => this.openDetail(row)
        }
      ]
    }
  ];

  logs = signal<AuditLogDto[]>([]);
  page = signal(0);
  totalPages = signal(0);
  loading = signal(false);
  error = signal<string | null>(null);

  fromDate = signal('');
  toDate = signal('');
  username = signal('');
  eventType = signal('');
  module = signal('');
  searchText = signal('');

  eventTypes = signal<string[]>([]);
  modules = signal<string[]>([]);

  detailOpen = signal(false);
  detailItem = signal<AuditLogDto | null>(null);

  isMobile = signal(false);

  readonly hayResultados = computed(() => this.logs().length > 0);

  readonly dataTableConfig = computed(() => ({
    rowKey: this.isMobile() ? 'id' : undefined
  }));

  readonly visibleColumns = computed(() => {
    if (this.isMobile()) {
      return this.columns.filter(col => col.key !== 'actions');
    }
    return this.columns;
  });
  readonly pageDisplay = computed(() => this.page() + 1);

  readonly eventTypeOptions = computed<Option[]>(() =>
    this.eventTypes().map(et => ({ value: et, label: et }))
  );
  readonly moduleOptions = computed<Option[]>(() =>
    this.modules().map(m => ({ value: m, label: m }))
  );

  ngOnInit(): void {
    this.detectMobileScreen();
    this.setupMobileListener();
    this.loadFilters();
    this.doSearch();
  }

  private detectMobileScreen(): void {
    const isMobileScreen = window.innerWidth < 768; // md breakpoint
    this.isMobile.set(isMobileScreen);
  }

  private setupMobileListener(): void {
    const mediaQuery = window.matchMedia('(max-width: 767px)');
    const handleChange = (e: MediaQueryListEvent) => this.isMobile.set(e.matches);
    mediaQuery.addEventListener('change', handleChange);
  }



  loadFilters(): void {
    Promise.all([
      this.auditApi.getEventTypes().toPromise(),
      this.auditApi.getModules().toPromise(),
    ]).then(([ets, mods]) => {
      this.eventTypes.set(ets!);
      this.modules.set(mods!);
    });
  }

  doSearch(): void {
    this.page.set(0);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auditApi.search({
      from: this.fromDate() || undefined,
      to: this.toDate() || undefined,
      username: this.username() || undefined,
      eventTypes: this.eventType() ? [this.eventType()] : undefined,
      modules: this.module() ? [this.module()] : undefined,
      q: this.searchText() || undefined,
      page: this.page(),
      size: 50,
    }).subscribe({
      next: res => {
        this.logs.set(res.content);
        this.totalPages.set(res.totalPages);
        this.loading.set(false);
      },
      error: err => {
        this.error.set(err?.error?.detail ?? 'Error cargando auditoría');
        this.loading.set(false);
      },
    });
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

  export(): void {
    const url = this.auditApi.exportUrl({
      from: this.fromDate() || undefined,
      to: this.toDate() || undefined,
      username: this.username() || undefined,
      eventTypes: this.eventType() ? [this.eventType()] : undefined,
      modules: this.module() ? [this.module()] : undefined,
      q: this.searchText() || undefined,
    });
    window.open(url, '_blank');
  }

  openDetail(item: AuditLogDto): void {
    this.detailItem.set(item);
    this.detailOpen.set(true);
  }

  closeDetail(): void {
    this.detailOpen.set(false);
  }

  statusColor(status: number | null): 'success' | 'error' | 'warning' | 'info' {
    if (!status) return 'info';
    if (status < 300) return 'success';
    if (status < 400) return 'warning';
    return 'error';
  }
}
