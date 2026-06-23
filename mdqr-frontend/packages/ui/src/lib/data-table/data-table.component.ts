import {
  Component, input, output, signal, computed, linkedSignal, TemplateRef, inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

// Exported types for public API
export type SortDirection = 'asc' | 'desc' | null;
export type DataTableActionVariant = 'default' | 'destructive' | 'ghost';
export type DataTableExportFormat = 'excel' | 'csv' | 'json';

export interface DataTableAction<T = Record<string, unknown>> {
  id: string;
  label: string;
  text?: string;
  icon?: string;
  variant?: DataTableActionVariant;
  onClick: (row: T, event: MouseEvent) => void;
  hidden?: (row: T) => boolean;
  disabled?: (row: T) => boolean;
}

export interface DataTablePageEvent {
  page: number;
  pageSize: number;
}

export interface DataTableSortEvent {
  key: string;
  direction: SortDirection;
}

export interface DataTableColumn<T = Record<string, unknown>> {
  key: string;
  label: string;
  sortable?: boolean;
  visible?: boolean;
  align?: 'left' | 'center' | 'right';
  type?: 'text' | 'number' | 'currency';
  format?: (value: any, row: T) => string;
  cellTemplate?: TemplateRef<{ $implicit: T; value: unknown }>;
  actions?: DataTableAction<T>[];
  cellClass?: string;
  headerClass?: string;
  defaultVisible?: boolean;
  configurable?: boolean;
}

export interface DataTableConfig {
  pageSize?: number;
  pageSizeOptions?: number[];
  rowKey?: string;
  searchable?: boolean;
  searchPlaceholder?: string;
  searchDebounceMs?: number;
  emptyMessage?: string;
  exportable?: boolean;
  exportFormats?: DataTableExportFormat[];
  exportFileName?: string;
  columnConfigurable?: boolean;
  columnConfigStorageKey?: string | null;
  serverTotalPages?: number | null;
  serverTotalElements?: number | null;
  serverCurrentPage?: number | null;
  rowTrackBy?: ((row: any) => unknown) | null;
}

type SortDir = SortDirection;

@Component({
  selector: 'app-data-table',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './data-table.component.html',
})
export class DataTableComponent {
  private sanitizer = inject(DomSanitizer);

  columns = input.required<DataTableColumn[]>();
  data    = input.required<Record<string, any>[]>();
  config  = input<DataTableConfig>({});

  rowClick = output<Record<string, any>>();
  pageChange = output<DataTablePageEvent>();
  sortChange = output<DataTableSortEvent>();
  searchChange = output<string>();
  columnVisibilityChange = output<Record<string, boolean>>();

  // Writable signals initialized from inputs — reset when inputs change
  columnOrder      = linkedSignal(() => this.columns().map(c => c.key));
  columnVisibility = linkedSignal<Record<string, boolean>>(() => {
    const vis = Object.fromEntries(this.columns().map(c => [c.key, c.visible !== false]));
    this.loadColumnsFromStorage();
    return vis;
  });
  pageSizeVal = linkedSignal(() => this.config().pageSize ?? 10);

  sortKey     = signal<string | null>(null);
  sortDir     = signal<SortDir>(null);
  currentPage = signal(1);
  expandedKey = signal<string | null>(null);
  showColMenu = signal(false);
  dragOverKey = signal<string | null>(null);
  searchTerm  = signal('');
  searchDebounceTimer = signal<ReturnType<typeof setTimeout> | null>(null);
  isMobile = signal(false);

  private _dragSrc: string | null = null;

  constructor() {
    this.detectMobileScreen();
    this.setupMobileListener();
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

  // ── derived ─────────────────────────────────────────────────────────────

  visibleColumns = computed(() => {
    const order = this.columnOrder();
    const vis   = this.columnVisibility();
    const map   = new Map(this.columns().map(c => [c.key, c]));
    return order.filter(k => vis[k] && map.has(k)).map(k => map.get(k)!);
  });

  filteredData = computed(() => {
    const rows = [...this.data()];
    const term = this.searchTerm().toLowerCase().trim();
    if (!term || !this.config().searchable) return rows;
    return rows.filter(row =>
      Object.values(row).some(v => String(v ?? '').toLowerCase().includes(term))
    );
  });

  private sortedData = computed(() => {
    const rows = [...this.filteredData()];
    const key  = this.sortKey();
    const dir  = this.sortDir();
    if (!key || !dir) return rows;
    return rows.sort((a, b) => {
      const va = a[key], vb = b[key];
      const cmp = va == null ? -1 : vb == null ? 1 : va < vb ? -1 : va > vb ? 1 : 0;
      return dir === 'asc' ? cmp : -cmp;
    });
  });

  pagedData = computed(() => {
    const size  = this.pageSizeVal();
    const start = (this.currentPage() - 1) * size;
    return this.sortedData().slice(start, start + size);
  });

  totalItems = computed(() => this.sortedData().length);

  totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalItems() / this.pageSizeVal()))
  );

  startRecord = computed(() =>
    this.totalItems() === 0 ? 0 : (this.currentPage() - 1) * this.pageSizeVal() + 1
  );

  endRecord = computed(() =>
    Math.min(this.currentPage() * this.pageSizeVal(), this.totalItems())
  );

  pageNumbers = computed((): (number | null)[] => {
    const total   = this.totalPages();
    const current = this.currentPage();
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    if (current <= 4)         return [1, 2, 3, 4, 5, null, total];
    if (current >= total - 3) return [1, null, total-4, total-3, total-2, total-1, total];
    return [1, null, current - 1, current, current + 1, null, total];
  });

  pageSizeOptions = computed(() => this.config().pageSizeOptions ?? [10, 25, 50, 100]);

  // ── sort ─────────────────────────────────────────────────────────────────

  onSort(key: string) {
    let newDir: SortDir;
    if (this.sortKey() === key) {
      if (this.sortDir() === 'asc') {
        this.sortDir.set('desc');
        newDir = 'desc';
      } else {
        this.sortDir.set(null);
        this.sortKey.set(null);
        newDir = null;
      }
    } else {
      this.sortKey.set(key);
      this.sortDir.set('asc');
      newDir = 'asc';
    }
    this.currentPage.set(1);
    this.sortChange.emit({ key, direction: newDir });
  }

  // ── pagination ───────────────────────────────────────────────────────────

  goToPage(page: number | null) {
    if (page == null) return;
    const p = Math.max(1, Math.min(page, this.totalPages()));
    this.currentPage.set(p);
    this.pageChange.emit({ page: p, pageSize: this.pageSizeVal() });
  }

  onPageSizeChange(size: number) {
    this.pageSizeVal.set(size);
    this.currentPage.set(1);
    this.pageChange.emit({ page: 1, pageSize: size });
  }

  // ── search ───────────────────────────────────────────────────────────

  onSearchChange(term: string) {
    this.searchTerm.set(term);
    this.currentPage.set(1);

    if (this.searchDebounceTimer()) {
      clearTimeout(this.searchDebounceTimer()!);
    }

    const timer = setTimeout(() => {
      this.searchChange.emit(term);
    }, this.config().searchDebounceMs ?? 300);

    this.searchDebounceTimer.set(timer);
  }

  // ── column visibility ────────────────────────────────────────────────────

  toggleColumn(key: string) {
    this.columnVisibility.update(vis => {
      const updated = { ...vis, [key]: !vis[key] };
      this.columnVisibilityChange.emit(updated);
      this.saveColumnsToStorage(updated);
      return updated;
    });
  }

  // ── localStorage ─────────────────────────────────────────────────────────

  private loadColumnsFromStorage() {
    const key = this.config().columnConfigStorageKey;
    if (!key) return;
    try {
      const stored = localStorage.getItem(key);
      if (stored) {
        const vis = JSON.parse(stored);
        this.columnVisibility.set(vis);
      }
    } catch (e) {
      console.warn('Failed to load column config from storage:', e);
    }
  }

  private saveColumnsToStorage(vis: Record<string, boolean>) {
    const key = this.config().columnConfigStorageKey;
    if (!key) return;
    try {
      localStorage.setItem(key, JSON.stringify(vis));
    } catch (e) {
      console.warn('Failed to save column config to storage:', e);
    }
  }

  // ── export ───────────────────────────────────────────────────────────

  exportData(format: DataTableExportFormat) {
    const rows = this.sortedData();
    const cols = this.visibleColumns().filter(c => !c.actions);

    if (format === 'excel') this.exportExcel(rows, cols);
    else if (format === 'csv') this.exportCsv(rows, cols);
    else if (format === 'json') this.exportJson(rows, cols);
  }

  private exportExcel(rows: Record<string, any>[], cols: DataTableColumn[]) {
    let html = '<table border="1"><thead><tr>';
    cols.forEach(c => html += `<th>${this.escapeHtml(c.label)}</th>`);
    html += '</tr></thead><tbody>';
    rows.forEach(row => {
      html += '<tr>';
      cols.forEach(c => html += `<td>${this.escapeHtml(this.cellValue(row, c))}</td>`);
      html += '</tr>';
    });
    html += '</tbody></table>';

    const blob = new Blob([html], { type: 'application/vnd.ms-excel;charset=utf-8;' });
    this.downloadBlob(blob, `${this.config().exportFileName ?? 'tabla'}.xls`);
  }

  private exportCsv(rows: Record<string, any>[], cols: DataTableColumn[]) {
    const csv = [
      cols.map(c => this.quoteCsvField(c.label)).join(','),
      ...rows.map(row => cols.map(c => this.quoteCsvField(this.cellValue(row, c))).join(','))
    ].join('\n');

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    this.downloadBlob(blob, `${this.config().exportFileName ?? 'tabla'}.csv`);
  }

  private exportJson(rows: Record<string, any>[], cols: DataTableColumn[]) {
    const data = rows.map(row => {
      const obj: Record<string, any> = {};
      cols.forEach(c => obj[c.key] = row[c.key]);
      return obj;
    });

    const json = JSON.stringify(data, null, 2);
    const blob = new Blob([json], { type: 'application/json;charset=utf-8;' });
    this.downloadBlob(blob, `${this.config().exportFileName ?? 'tabla'}.json`);
  }

  private downloadBlob(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  private escapeHtml(text: string): string {
    const map: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
    return String(text).replace(/[&<>"']/g, c => map[c]);
  }

  private quoteCsvField(field: string): string {
    const str = String(field);
    if (str.includes(',') || str.includes('"') || str.includes('\n')) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  }

  // ── drag-and-drop reorder ────────────────────────────────────────────────

  onDragStart(event: DragEvent, key: string) {
    this._dragSrc = key;
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  onDragOver(event: DragEvent, key: string) {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
    this.dragOverKey.set(key);
  }

  onDrop(event: DragEvent, targetKey: string) {
    event.preventDefault();
    const src = this._dragSrc;
    if (!src || src === targetKey) { this.dragOverKey.set(null); return; }
    this.columnOrder.update(order => {
      const arr = [...order];
      const si = arr.indexOf(src);
      const ti = arr.indexOf(targetKey);
      if (si < 0 || ti < 0) return arr;
      arr.splice(si, 1);
      arr.splice(ti, 0, src);
      return arr;
    });
    this._dragSrc = null;
    this.dragOverKey.set(null);
  }

  onDragEnd() {
    this._dragSrc = null;
    this.dragOverKey.set(null);
  }

  // ── row expand ───────────────────────────────────────────────────────────

  rowKey(row: Record<string, any>, index: number): string {
    const field = this.config().rowKey;
    return field && row[field] != null ? String(row[field]) : String(index);
  }

  toggleRow(key: string, row: Record<string, any>) {
    if (this.isMobile()) {
      this.expandedKey.update(k => k === key ? null : key);
    }
    this.rowClick.emit(row);
  }

  // ── cell rendering ───────────────────────────────────────────────────────

  cellValue(row: Record<string, any>, col: DataTableColumn): string {
    const val = row[col.key];
    if (col.format) return col.format(val, row);
    if (val == null || val === '') return '—';
    if (col.type === 'currency') {
      const n = Number(val);
      return isNaN(n) ? String(val)
        : new Intl.NumberFormat('es-BO', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
    }
    if (col.type === 'number') {
      const n = Number(val);
      return isNaN(n) ? String(val)
        : new Intl.NumberFormat('es-BO', { minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(n);
    }
    return String(val);
  }

  // ── action button styling ───────────────────────────────────────────────

  getActionButtonClass(variant: DataTableActionVariant): string {
    const base = 'rounded-lg px-2 py-1 text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed';
    switch (variant) {
      case 'destructive':
        return `${base} border border-red-200 bg-red-50 text-red-700 hover:bg-red-100 dark:border-red-800 dark:bg-red-900/30 dark:text-red-400`;
      case 'default':
        return `${base} border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300`;
      case 'ghost':
      default:
        return `${base} text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800`;
    }
  }

  // ── sanitization ─────────────────────────────────────────────────────────

  sanitizeIcon(iconHtml: string): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(iconHtml);
  }

  // ── css helpers ──────────────────────────────────────────────────────────

  thClass(col: DataTableColumn, isDragOver: boolean): string {
    const align = col.align === 'right' ? 'text-right'
                : col.align === 'center' ? 'text-center'
                : 'text-left';
    const drag  = isDragOver
      ? 'bg-brand-50 dark:bg-brand-500/10'
      : '';
    return `px-4 py-3 ${align} select-none whitespace-nowrap transition-colors ${drag}`.trim();
  }

  tdClass(col: DataTableColumn): string {
    const align = col.align === 'right' ? 'text-right'
                : col.align === 'center' ? 'text-center'
                : 'text-left';
    const num   = (col.type === 'currency' || col.type === 'number')
      ? 'font-medium tabular-nums'
      : '';
    return `px-4 py-4 text-sm text-gray-800 dark:text-white/90 whitespace-nowrap ${align} ${num}`.trim();
  }
}
