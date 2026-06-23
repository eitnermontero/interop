import { Component, input, output, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { InputFieldComponent } from '../input/input-field.component';
import { SelectComponent } from '../select/select.component';
import { MultiSelectComponent } from '../multi-select/multi-select.component';
import { DatePickerComponent } from '../date-picker/date-picker.component';

export type DynamicFilterOperator =
  | 'contains'
  | 'equals'
  | 'greaterThan'
  | 'lessThan'
  | 'greaterThanOrEqual'
  | 'lessThanOrEqual'
  | 'specified'
  | 'in';

export interface DynamicFilterDateRangeValue {
  from: string | null;
  to: string | null;
}

export type DynamicFilterValue =
  | string
  | number
  | boolean
  | string[]
  | DynamicFilterDateRangeValue
  | null;

export type DynamicFiltersValue = Record<string, DynamicFilterValue>;

export interface SelectOption {
  value: string;
  label: string;
}

// Base config for all filter types
export interface DynamicFilterBase {
  field: string;
  queryField?: string;
  label: string;
  placeholder?: string;
  operator?: DynamicFilterOperator;
  debounceMs?: number;
}

export interface DynamicTextFilterConfig extends DynamicFilterBase {
  type: 'text';
}

export interface DynamicNumberFilterConfig extends DynamicFilterBase {
  type: 'number';
}

export interface DynamicDateFilterConfig extends DynamicFilterBase {
  type: 'date';
}

export interface DynamicSelectFilterConfig extends DynamicFilterBase {
  type: 'select' | 'boolean';
  options?: SelectOption[];
}

export interface DynamicMultiSelectFilterConfig extends DynamicFilterBase {
  type: 'multi-select';
  options: SelectOption[];
  operator?: 'in';
}

export interface DynamicDateRangeFilterConfig extends DynamicFilterBase {
  type: 'date-range';
  fromLabel?: string;
  toLabel?: string;
  fromPlaceholder?: string;
  toPlaceholder?: string;
  rangeOperators?: {
    from: Exclude<DynamicFilterOperator, 'contains' | 'equals' | 'in'>;
    to: Exclude<DynamicFilterOperator, 'contains' | 'equals' | 'in'>;
  };
}

export type DynamicFilterConfig =
  | DynamicTextFilterConfig
  | DynamicNumberFilterConfig
  | DynamicDateFilterConfig
  | DynamicSelectFilterConfig
  | DynamicMultiSelectFilterConfig
  | DynamicDateRangeFilterConfig;

@Component({
  selector: 'app-dynamic-filters',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    InputFieldComponent,
    SelectComponent,
    MultiSelectComponent,
    DatePickerComponent,
  ],
  templateUrl: './dynamic-filters.component.html',
})
export class DynamicFiltersComponent {
  filters = input<DynamicFilterConfig[]>([]);
  initialValue = input<DynamicFiltersValue>({});

  filtersChange = output<DynamicFiltersValue>();
  cleared = output<void>();

  values = signal<DynamicFiltersValue>(this.initialValue());
  debounceTimers = signal<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  hasActiveFilters = computed(() => {
    const vals = this.values();
    return Object.values(vals).some(v => {
      if (v === null || v === '' || v === false) return false;
      if (Array.isArray(v) && v.length === 0) return false;
      if (typeof v === 'object' && 'from' in v && 'to' in v) {
        return (v as DynamicFilterDateRangeValue).from !== null || (v as DynamicFilterDateRangeValue).to !== null;
      }
      return true;
    });
  });

  onFilterChange(field: string, value: DynamicFilterValue) {
    this.values.update(vals => ({ ...vals, [field]: value }));

    const timers = this.debounceTimers();
    if (timers.has(field)) {
      clearTimeout(timers.get(field)!);
    }

    const debounce = this.getFilterDebounce(field);
    const timer = setTimeout(() => {
      this.filtersChange.emit(this.values());
    }, debounce);

    timers.set(field, timer);
    this.debounceTimers.set(timers);
  }

  onDateRangeChange(field: string, from: string | null, to: string | null) {
    this.onFilterChange(field, { from, to } as DynamicFilterDateRangeValue);
  }

  clearFilters() {
    const empty: DynamicFiltersValue = {};
    this.filters().forEach(f => {
      empty[f.field] = f.type === 'multi-select' ? [] : f.type === 'date-range' ? { from: null, to: null } : null;
    });
    this.values.set(empty);
    this.cleared.emit();
    this.filtersChange.emit(empty);
  }

  private getFilterDebounce(field: string): number {
    const filter = this.filters().find(f => f.field === field);
    return filter?.debounceMs ?? 300;
  }

  getBooleanOptions(): SelectOption[] {
    return [
      { value: 'true', label: 'Sí' },
      { value: 'false', label: 'No' },
    ];
  }

  getDateRangeFrom(field: string): string {
    const val = this.values()[field];
    const from = typeof val === 'object' && val && 'from' in val ? (val as DynamicFilterDateRangeValue).from : null;
    return from || '';
  }

  getDateRangeTo(field: string): string {
    const val = this.values()[field];
    const to = typeof val === 'object' && val && 'to' in val ? (val as DynamicFilterDateRangeValue).to : null;
    return to || '';
  }

  getMultiSelectValue(field: string): string[] {
    const val = this.values()[field];
    return Array.isArray(val) ? val : [];
  }

  getSelectValue(field: string): string {
    const val = this.values()[field];
    if (val === null || val === undefined) return '';
    if (typeof val === 'string') return val;
    if (typeof val === 'boolean') return String(val);
    if (typeof val === 'number') return String(val);
    return '';
  }

  getNumberValue(field: string): string {
    const val = this.values()[field];
    if (val === null || val === undefined) return '';
    if (typeof val === 'number') return String(val);
    if (typeof val === 'string') return val;
    return '';
  }

  getDateValue(field: string): string {
    const val = this.values()[field];
    if (typeof val === 'string') return val;
    if (val === null || val === undefined) return '';
    return '';
  }

  getNumberFromEvent(event: Event): number | null {
    const value = (event.target as HTMLInputElement).value;
    return value === '' ? null : Number(value);
  }
}
