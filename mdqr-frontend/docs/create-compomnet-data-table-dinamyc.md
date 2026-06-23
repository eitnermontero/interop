# Documentación de Componentes Dinámicos

## Tabla de contenidos
- [Data Table Component](#data-table-component)
- [Dynamic Filters Component](#dynamic-filters-component)
- [Ejemplo de uso integrado](#ejemplo-de-uso-integrado)

---

## Data Table Component

Un componente de tabla altamente configurable con soporte para búsqueda, paginación, ordenamiento, exportación y configuración de columnas.

### Características principales

- ✅ **Búsqueda global** con debounce configurable
- ✅ **Paginación** (client-side y server-side)
- ✅ **Ordenamiento** por columna
- ✅ **Exportación** a Excel, CSV, JSON
- ✅ **Configuración de columnas** (mostrar/ocultar)
- ✅ **Acciones personalizadas** por fila
- ✅ **Templates personalizados** para celdas
- ✅ **localStorage** para persistencia de configuración

### Tipos exportados

```typescript
// Dirección de ordenamiento
type SortDirection = 'asc' | 'desc' | null;

// Variante visual para botones de acción
type DataTableActionVariant = 'default' | 'destructive' | 'ghost';

// Un botón de acción dentro de una celda
interface DataTableAction<T = Record<string, unknown>> {
  id: string;                              // ID único
  label: string;                           // Texto tooltip/aria-label
  icon?: LucideIcon;                       // Icono Lucide (opcional)
  text?: string;                           // Texto visible (opcional)
  variant?: DataTableActionVariant;        // Estilo: 'default' | 'destructive' | 'ghost'
  onClick: (row: T, event: MouseEvent) => void;  // Función al hacer click
  hidden?: (row: T) => boolean;            // Ocultar acción condicionalmente
  disabled?: (row: T) => boolean;          // Deshabilitar acción condicionalmente
}

// Definición de columna
interface DataTableColumn<T = Record<string, unknown>> {
  key: string;                             // Propiedad en el objeto row
  label: string;                           // Encabezado visible
  sortable?: boolean;                      // Permite ordenar (default: false)
  cellTemplate?: TemplateRef<{ $implicit: T; value: unknown }>;  // Template personalizado
  actions?: DataTableAction<T>[];          // Botones de acción en la celda
  cellClass?: string;                      // Clases CSS personalizadas para <td>
  headerClass?: string;                    // Clases CSS personalizadas para <th>
  defaultVisible?: boolean;                // Visible por defecto (default: true)
  configurable?: boolean;                  // Se puede toglear (default: true)
  visible?: boolean;                       // Estado inicial de visibilidad
}

// Evento de cambio de página
interface DataTablePageEvent {
  page: number;
  pageSize: number;
}

// Evento de ordenamiento
interface DataTableSortEvent {
  key: string;
  direction: SortDirection;
}

// Formatos de exportación
type DataTableExportFormat = 'excel' | 'csv' | 'json';
```

### Inputs

| Input | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `data` | `T[]` | `[]` | Array de datos a mostrar |
| `columns` | `DataTableColumn<T>[]` | `[]` | Definición de columnas |
| `searchable` | `boolean` | `true` | Habilitar búsqueda global |
| `searchPlaceholder` | `string` | `'Buscar…'` | Placeholder de búsqueda |
| `paginated` | `boolean` | `true` | Habilitar paginación |
| `pageSize` | `number` | `10` | Cantidad de filas por página |
| `pageSizeOptions` | `number[]` | `[5, 10, 25, 50]` | Opciones de tamaño de página |
| `searchDebounceMs` | `number` | `300` | Debounce de búsqueda (ms) |
| `emptyMessage` | `string` | `'Sin resultados.'` | Mensaje cuando no hay datos |
| `serverTotalPages` | `number \| null` | `null` | Total de páginas (server-side) |
| `serverTotalElements` | `number \| null` | `null` | Total de elementos (server-side) |
| `serverCurrentPage` | `number \| null` | `null` | Página actual (server-side) |
| `rowTrackBy` | `(row: T) => unknown \| null` | `null` | Función para track de filas |
| `exportable` | `boolean` | `false` | Habilitar exportación |
| `exportFormats` | `DataTableExportFormat[]` | `['excel', 'csv', 'json']` | Formatos disponibles |
| `exportFileName` | `string` | `'tabla'` | Nombre base para archivos |
| `columnConfigurable` | `boolean` | `true` | Habilitar configurador de columnas |
| `columnConfigStorageKey` | `string \| null` | `null` | Clave para localStorage |

### Outputs

| Output | Tipo | Descripción |
|--------|------|-------------|
| `pageChange` | `DataTablePageEvent` | Se emite cuando cambia página o tamaño |
| `sortChange` | `DataTableSortEvent` | Se emite cuando cambia ordenamiento |
| `searchChange` | `string` | Se emite cuando cambia búsqueda |
| `columnVisibilityChange` | `Record<string, boolean>` | Se emite cuando cambia visibilidad |

### Ejemplo de uso básico

```typescript
import { Component } from '@angular/core';
import { DataTableComponent, DataTableColumn } from './path/to/data-table';

interface User {
  id: number;
  name: string;
  email: string;
  status: string;
  createdAt: Date;
}

@Component({
  selector: 'app-users-table',
  template: `
    <app-data-table
      [data]="users"
      [columns]="columns"
      [pageSize]="15"
      [searchable]="true"
      [exportable]="true"
      [columnConfigurable]="true"
      columnConfigStorageKey="users-table-config"
      (pageChange)="onPageChange($event)"
      (sortChange)="onSortChange($event)"
      (searchChange)="onSearchChange($event)"
    />
  `,
  standalone: true,
  imports: [DataTableComponent]
})
export class UsersTableComponent {
  users: User[] = [
    { id: 1, name: 'Juan', email: 'juan@example.com', status: 'active', createdAt: new Date() },
    { id: 2, name: 'María', email: 'maria@example.com', status: 'inactive', createdAt: new Date() },
  ];

  columns: DataTableColumn<User>[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'name', label: 'Nombre', sortable: true },
    { key: 'email', label: 'Email', sortable: true },
    { key: 'status', label: 'Estado', sortable: true },
    { key: 'createdAt', label: 'Creado', sortable: true },
  ];

  onPageChange(event: DataTablePageEvent): void {
    console.log(`Página ${event.page}, tamaño ${event.pageSize}`);
  }

  onSortChange(event: DataTableSortEvent): void {
    console.log(`Ordenar por ${event.key}: ${event.direction}`);
  }

  onSearchChange(query: string): void {
    console.log(`Búsqueda: ${query}`);
  }
}
```

### Ejemplo con acciones personalizadas

```typescript
const columns: DataTableColumn<User>[] = [
  { key: 'name', label: 'Nombre' },
  { key: 'email', label: 'Email' },
  {
    key: '_actions',
    label: 'Acciones',
    actions: [
      {
        id: 'edit',
        label: 'Editar',
        icon: Edit,
        variant: 'default',
        onClick: (row: User) => {
          console.log('Editar:', row);
        },
      },
      {
        id: 'delete',
        label: 'Eliminar',
        icon: Trash2,
        variant: 'destructive',
        onClick: (row: User) => {
          console.log('Eliminar:', row);
        },
        hidden: (row: User) => row.status === 'deleted',
      },
    ],
  },
];
```

### Ejemplo con template personalizado

```typescript
@Component({
  template: `
    <ng-template #statusTemplate let-row="$implicit" let-value="value">
      <span [class.status-active]="value === 'active'" [class.status-inactive]="value !== 'active'">
        {{ value }}
      </span>
    </ng-template>

    <app-data-table
      [data]="users"
      [columns]="columns"
    />
  `,
})
export class Example {
  @ViewChild('statusTemplate') statusTemplate!: TemplateRef<any>;

  columns: DataTableColumn<User>[] = [
    { key: 'name', label: 'Nombre' },
    {
      key: 'status',
      label: 'Estado',
      cellTemplate: this.statusTemplate,
    },
  ];
}
```

### Ejemplo con paginación server-side

```typescript
export class ServerPaginationComponent {
  users = signal<User[]>([]);
  serverTotalPages = signal<number | null>(null);
  serverTotalElements = signal<number | null>(null);
  serverCurrentPage = signal<number | null>(null);

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.fetchData();
  }

  fetchData(page: number = 1, pageSize: number = 10): void {
    this.api.getUsers(page, pageSize).subscribe((response) => {
      this.users.set(response.data);
      this.serverCurrentPage.set(response.currentPage);
      this.serverTotalPages.set(response.totalPages);
      this.serverTotalElements.set(response.totalElements);
    });
  }

  onPageChange(event: DataTablePageEvent): void {
    this.fetchData(event.page, event.pageSize);
  }
}
```

### Exportación automática de datos

El componente soporta exportación a tres formatos:

- **Excel (.xls)**: Tabla HTML con estilos básicos
- **CSV (.csv)**: Valores separados por comas, con escape de comillas
- **JSON (.json)**: Array de objetos con identación

Las columnas con acciones (aquellas con `actions`) son automáticamente excluidas de la exportación.

---

## Dynamic Filters Component

Componente de filtrado dinámico con soporte para múltiples tipos de filtros y debounce automático.

### Características principales

- ✅ **6 tipos de filtros**: texto, número, fecha, select, multi-select, rango de fechas
- ✅ **Debounce automático** para texto y números
- ✅ **Validación** integrada
- ✅ **localStorage** para persistencia (opcional)
- ✅ **Operadores de comparación** configurables
- ✅ **Booleanos** con opciones predefinidas

### Tipos exportados

```typescript
// Operadores de filtrado
type DynamicFilterOperator =
  | 'contains'
  | 'equals'
  | 'greaterThan'
  | 'lessThan'
  | 'greaterThanOrEqual'
  | 'lessThanOrEqual'
  | 'specified'
  | 'in';

// Valor de rango de fechas
interface DynamicFilterDateRangeValue {
  from: string | null;  // Formato ISO: YYYY-MM-DD
  to: string | null;    // Formato ISO: YYYY-MM-DD
}

// Valores de filtros
type DynamicFilterValue =
  | string
  | number
  | boolean
  | string[]
  | DynamicFilterDateRangeValue
  | null;

type DynamicFiltersValue = Record<string, DynamicFilterValue>;

// Configuración base de filtro
interface DynamicFilterBase {
  field: string;           // Nombre del campo en la consulta
  queryField?: string;     // Nombre alternativo para la API (opcional)
  label: string;           // Etiqueta visible
  placeholder?: string;    // Placeholder del input
  operator?: DynamicFilterOperator;  // Operador por defecto
  debounceMs?: number;     // Debounce para texto/número (default: 300)
}

// Filtro de texto
interface DynamicTextFilterConfig extends DynamicFilterBase {
  type: 'text';
}

// Filtro de número
interface DynamicNumberFilterConfig extends DynamicFilterBase {
  type: 'number';
}

// Filtro de fecha única
interface DynamicDateFilterConfig extends DynamicFilterBase {
  type: 'date';
}

// Filtro select (dropdown)
interface DynamicSelectFilterConfig extends DynamicFilterBase {
  type: 'select' | 'boolean';
  options?: SelectOption[];
}

// Filtro multi-select
interface DynamicMultiSelectFilterConfig extends DynamicFilterBase {
  type: 'multi-select';
  options: SelectOption[];  // REQUERIDO
  operator?: 'in';
}

// Filtro rango de fechas
interface DynamicDateRangeFilterConfig extends DynamicFilterBase {
  type: 'date-range';
  fromLabel?: string;      // Etiqueta "desde" (default: 'Desde')
  toLabel?: string;        // Etiqueta "hasta" (default: 'Hasta')
  fromPlaceholder?: string;
  toPlaceholder?: string;
  rangeOperators?: {
    from: Exclude<DynamicFilterOperator, 'contains' | 'equals' | 'in'>;
    to: Exclude<DynamicFilterOperator, 'contains' | 'equals' | 'in'>;
  };
}

// Union de todos los tipos
type DynamicFilterConfig =
  | DynamicTextFilterConfig
  | DynamicNumberFilterConfig
  | DynamicDateFilterConfig
  | DynamicSelectFilterConfig
  | DynamicMultiSelectFilterConfig
  | DynamicDateRangeFilterConfig;
```

### Inputs

| Input | Tipo | Default | Descripción |
|-------|------|---------|-------------|
| `filters` | `DynamicFilterConfig[]` | `[]` | Configuración de filtros |
| `initialValue` | `DynamicFiltersValue` | `{}` | Valores iniciales |

### Outputs

| Output | Tipo | Descripción |
|--------|------|-------------|
| `filtersChange` | `DynamicFiltersValue` | Se emite cuando cambian los filtros |
| `cleared` | `void` | Se emite cuando se limpian los filtros |

### Ejemplo de uso básico

```typescript
import { Component } from '@angular/core';
import { DynamicFiltersComponent, DynamicFilterConfig, DynamicFiltersValue } from './path/to/dynamic-filters';

@Component({
  selector: 'app-users-filter',
  template: `
    <app-dynamic-filters
      [filters]="filterConfigs"
      [initialValue]="initialFilters"
      (filtersChange)="onFiltersChange($event)"
      (cleared)="onFiltersCleared()"
    />
  `,
  standalone: true,
  imports: [DynamicFiltersComponent]
})
export class UsersFilterComponent {
  filterConfigs: DynamicFilterConfig[] = [
    {
      type: 'text',
      field: 'name',
      label: 'Nombre',
      placeholder: 'Buscar por nombre...',
      operator: 'contains',
    },
    {
      type: 'select',
      field: 'status',
      label: 'Estado',
      options: [
        { value: 'active', label: 'Activo' },
        { value: 'inactive', label: 'Inactivo' },
      ],
    },
    {
      type: 'date-range',
      field: 'createdAt',
      label: 'Fecha de creación',
      fromLabel: 'Desde',
      toLabel: 'Hasta',
    },
  ];

  initialFilters: DynamicFiltersValue = {
    name: '',
    status: null,
    createdAt: { from: null, to: null },
  };

  onFiltersChange(filters: DynamicFiltersValue): void {
    console.log('Filtros actualizados:', filters);
    // Aquí actualizar la tabla o hacer una petición a la API
  }

  onFiltersCleared(): void {
    console.log('Filtros limpios');
  }
}
```

### Tipos de filtros con ejemplos

#### 1. Filtro de texto

```typescript
{
  type: 'text',
  field: 'description',
  label: 'Descripción',
  placeholder: 'Ingresa texto...',
  operator: 'contains',        // 'contains', 'equals'
  debounceMs: 500,             // Personalizar debounce
}
```

**Salida**: `{ description: 'mi texto' }`

#### 2. Filtro de número

```typescript
{
  type: 'number',
  field: 'amount',
  label: 'Monto',
  placeholder: 'Ingresa un número...',
  operator: 'greaterThan',     // 'greaterThan', 'lessThan', etc.
  debounceMs: 300,
}
```

**Salida**: `{ amount: 1000 }`

#### 3. Filtro de fecha

```typescript
{
  type: 'date',
  field: 'transactionDate',
  label: 'Fecha de transacción',
  operator: 'equals',
}
```

**Salida**: `{ transactionDate: '2026-05-22' }`

#### 4. Filtro select (dropdown)

```typescript
{
  type: 'select',
  field: 'currency',
  label: 'Moneda',
  placeholder: 'Seleccionar...',
  options: [
    { value: 'usd', label: 'USD' },
    { value: 'eur', label: 'EUR' },
    { value: 'cop', label: 'COP' },
  ],
  operator: 'equals',
}
```

**Salida**: `{ currency: 'usd' }`

#### 5. Filtro booleano

```typescript
{
  type: 'boolean',
  field: 'isActive',
  label: '¿Activo?',
  placeholder: 'Todos',
  options: [  // Opcional, usa predeterminados: [{ value: 'true', label: 'Si' }, { value: 'false', label: 'No' }]
    { value: 'true', label: 'Sí' },
    { value: 'false', label: 'No' },
  ],
}
```

**Salida**: `{ isActive: 'true' }`

#### 6. Filtro multi-select

```typescript
{
  type: 'multi-select',
  field: 'roles',
  label: 'Roles',
  options: [
    { value: 'admin', label: 'Administrador' },
    { value: 'user', label: 'Usuario' },
    { value: 'guest', label: 'Invitado' },
  ],
  operator: 'in',
}
```

**Salida**: `{ roles: ['admin', 'user'] }`

#### 7. Filtro de rango de fechas

```typescript
{
  type: 'date-range',
  field: 'dateRange',
  label: 'Período',
  fromLabel: 'Fecha de inicio',
  toLabel: 'Fecha de fin',
  rangeOperators: {
    from: 'greaterThanOrEqual',
    to: 'lessThanOrEqual',
  },
}
```

**Salida**: `{ dateRange: { from: '2026-01-01', to: '2026-12-31' } }`

### Integración con tabla de datos

```typescript
@Component({
  template: `
    <div class="space-y-4">
      <app-dynamic-filters
        [filters]="filterConfigs"
        (filtersChange)="onFiltersChange($event)"
      />
      
      <app-data-table
        [data]="filteredData"
        [columns]="columns"
      />
    </div>
  `,
  standalone: true,
  imports: [DynamicFiltersComponent, DataTableComponent]
})
export class DashboardComponent {
  filterConfigs: DynamicFilterConfig[] = [
    { type: 'text', field: 'name', label: 'Nombre' },
    { type: 'select', field: 'status', label: 'Estado', options: [...] },
  ];

  allData: User[] = [/* datos */];
  filteredData = signal<User[]>(this.allData);
  columns: DataTableColumn<User>[] = [/* columnas */];

  onFiltersChange(filters: DynamicFiltersValue): void {
    this.filteredData.set(
      this.allData.filter(user => {
        const nameMatch = !filters.name || 
          user.name.toLowerCase().includes(String(filters.name).toLowerCase());
        const statusMatch = !filters.status || user.status === filters.status;
        return nameMatch && statusMatch;
      })
    );
  }
}
```

---

## Ejemplo de uso integrado

Ejemplo completo con data-table + dynamic-filters:

```typescript
import { Component, signal } from '@angular/core';
import { DataTableComponent, DataTableColumn } from './data-table';
import { DynamicFiltersComponent, DynamicFilterConfig, DynamicFiltersValue } from './dynamic-filters';

interface Transaction {
  id: number;
  reference: string;
  amount: number;
  currency: string;
  status: 'pending' | 'completed' | 'failed';
  date: Date;
  sender: string;
  receiver: string;
}

@Component({
  selector: 'app-transactions',
  template: `
    <div class="space-y-6">
      <div class="container mx-auto">
        <h1 class="text-2xl font-bold mb-4">Transacciones</h1>
        
        <app-dynamic-filters
          [filters]="filterConfigs"
          [initialValue]="initialFilters"
          (filtersChange)="onFiltersChange($event)"
        />
        
        <app-data-table
          [data]="displayedTransactions()"
          [columns]="columns"
          [paginated]="true"
          [pageSize]="20"
          [searchable]="true"
          [exportable]="true"
          [columnConfigurable]="true"
          exportFileName="transacciones"
          columnConfigStorageKey="transactions-table-config"
          (pageChange)="onPageChange($event)"
          (sortChange)="onSortChange($event)"
        />
      </div>
    </div>
  `,
  standalone: true,
  imports: [DynamicFiltersComponent, DataTableComponent]
})
export class TransactionsComponent {
  // Datos base
  allTransactions: Transaction[] = [
    {
      id: 1,
      reference: 'TRX001',
      amount: 1500.50,
      currency: 'USD',
      status: 'completed',
      date: new Date('2026-05-20'),
      sender: 'Juan Perez',
      receiver: 'Maria Garcia',
    },
    {
      id: 2,
      reference: 'TRX002',
      amount: 2300.00,
      currency: 'COP',
      status: 'pending',
      date: new Date('2026-05-21'),
      sender: 'Carlos Lopez',
      receiver: 'Ana Rodriguez',
    },
    // ... más transacciones
  ];

  // Estado reactivo
  filteredTransactions = signal<Transaction[]>(this.allTransactions);
  displayedTransactions = signal<Transaction[]>(this.allTransactions);

  // Configuración de filtros
  filterConfigs: DynamicFilterConfig[] = [
    {
      type: 'text',
      field: 'reference',
      label: 'Referencia',
      placeholder: 'Ej: TRX001',
      operator: 'contains',
    },
    {
      type: 'number',
      field: 'amount',
      label: 'Monto',
      placeholder: 'Monto en dinero',
      operator: 'equals',
    },
    {
      type: 'select',
      field: 'currency',
      label: 'Moneda',
      options: [
        { value: 'USD', label: 'USD' },
        { value: 'COP', label: 'COP' },
        { value: 'EUR', label: 'EUR' },
      ],
    },
    {
      type: 'select',
      field: 'status',
      label: 'Estado',
      options: [
        { value: 'pending', label: 'Pendiente' },
        { value: 'completed', label: 'Completado' },
        { value: 'failed', label: 'Fallido' },
      ],
    },
    {
      type: 'date-range',
      field: 'dateRange',
      label: 'Período',
      fromLabel: 'Desde',
      toLabel: 'Hasta',
    },
  ];

  initialFilters: DynamicFiltersValue = {
    reference: '',
    amount: null,
    currency: null,
    status: null,
    dateRange: { from: null, to: null },
  };

  // Configuración de columnas
  columns: DataTableColumn<Transaction>[] = [
    { key: 'reference', label: 'Referencia', sortable: true },
    { key: 'sender', label: 'Remitente', sortable: true },
    { key: 'receiver', label: 'Receptor', sortable: true },
    {
      key: 'amount',
      label: 'Monto',
      sortable: true,
      cellClass: 'text-right font-semibold',
    },
    { key: 'currency', label: 'Moneda', sortable: true },
    {
      key: 'status',
      label: 'Estado',
      sortable: true,
      cellTemplate: this.statusTemplate, // Usar template personalizado
    },
    { key: 'date', label: 'Fecha', sortable: true },
    {
      key: '_actions',
      label: 'Acciones',
      actions: [
        {
          id: 'view',
          label: 'Ver detalles',
          text: 'Ver',
          variant: 'default',
          onClick: (row: Transaction) => this.viewTransaction(row),
        },
        {
          id: 'retry',
          label: 'Reintentar',
          text: 'Reintentar',
          variant: 'default',
          onClick: (row: Transaction) => this.retryTransaction(row),
          hidden: (row: Transaction) => row.status !== 'failed',
        },
      ],
    },
  ];

  onFiltersChange(filters: DynamicFiltersValue): void {
    this.filteredTransactions.set(
      this.allTransactions.filter((tx) => {
        // Filtro por referencia
        if (filters.reference && !tx.reference.includes(String(filters.reference))) {
          return false;
        }

        // Filtro por monto
        if (filters.amount && tx.amount !== filters.amount) {
          return false;
        }

        // Filtro por moneda
        if (filters.currency && tx.currency !== filters.currency) {
          return false;
        }

        // Filtro por estado
        if (filters.status && tx.status !== filters.status) {
          return false;
        }

        // Filtro por rango de fechas
        const dateRange = filters.dateRange as any;
        if (dateRange?.from) {
          const fromDate = new Date(dateRange.from);
          if (tx.date < fromDate) return false;
        }
        if (dateRange?.to) {
          const toDate = new Date(dateRange.to);
          if (tx.date > toDate) return false;
        }

        return true;
      })
    );

    this.displayedTransactions.set(this.filteredTransactions());
  }

  onPageChange(event: any): void {
    console.log('Cambio de página:', event);
  }

  onSortChange(event: any): void {
    console.log('Cambio de ordenamiento:', event);
  }

  viewTransaction(tx: Transaction): void {
    console.log('Ver detalles:', tx);
  }

  retryTransaction(tx: Transaction): void {
    console.log('Reintentar:', tx);
  }

  // Template para estado
  protected statusTemplate: any = null;
}
```

---

## Notas de implementación

### Dependencias requeridas

```json
{
  "dependencies": {
    "@angular/common": "^17.0.0",
    "@angular/core": "^17.0.0",
    "@angular/forms": "^17.0.0",
    "lucide-angular": "^latest",
    "rxjs": "^7.0.0"
  }
}
```

### Estilos Tailwind CSS

Asegúrate de tener Tailwind CSS configurado. Los componentes usan clases como:
- `flex`, `gap`, `rounded-md`, `border`, `bg-background`, etc.

### Componentes dependientes

Ambos componentes requieren componentes adicionales:

- **DataTableComponent**: LucideAngularModule (iconos)
- **DynamicFiltersComponent**: ButtonComponent, InputComponent, SelectComponent

### Accessibility (a11y)

Ambos componentes incluyen:
- aria-labels en botones e inputs
- aria-sort en encabezados ordenables
- aria-current en páginas activas
- Labels asociados con inputs

### Performance

- Búsqueda con debounce configurable (evita peticiones innecesarias)
- OnPush change detection en ambos componentes
- Computed signals para optimizar re-cálculos
- Track keys en *ngFor para optimizar rendering

---

## Cambios frecuentes / Troubleshooting

### Tabla vacía pero hay datos

✓ Verifica que `columns[].key` coincida con las propiedades del objeto `data`
✓ Asegúrate de que `data` es un array no vacío

### Filtros no funcionan

✓ Verifica el nombre del campo en `filter.field`
✓ Comprueba que el operator es válido para el tipo de filtro
✓ Asegúrate de que el componente padre actualiza `filteredData` en `onFiltersChange`

### Ordenamiento lento

✓ Habilita `usesServerPagination` si tienes muchos datos
✓ Reduce el debounce de búsqueda
✓ Implementa virtual scrolling para tablas muy grandes

### localStorage no funciona

✓ Asegúrate de habilitar `columnConfigStorageKey`
✓ Verifica que el navegador no está en modo incógnito
✓ Comprueba que no hay restricciones de cookies/storage

---

Documentación generada: **2026-05-22**
