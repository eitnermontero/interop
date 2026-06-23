import { NgClass } from '@angular/common';
import { Component, booleanAttribute, computed, input } from '@angular/core';

@Component({
  selector: 'app-table',
  imports: [NgClass],
  template: `
    @if (unwrapped()) {
      <table class="min-w-full" [ngClass]="className()"><ng-content></ng-content></table>
    } @else {
      <div class="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-white/[0.05] dark:bg-white/[0.03]">
        <div class="max-w-full overflow-x-auto">
          <table class="min-w-full" [ngClass]="className()"><ng-content></ng-content></table>
        </div>
      </div>
    }
  `,
})
export class TableComponent {
  unwrapped = input(false, { transform: booleanAttribute });
  className = input('');
}

// Host element IS the <thead> — display: table-header-group so el navegador lo reconoce como sección válida
@Component({
  selector: 'app-table-header',
  template: `<ng-content></ng-content>`,
  styles: `:host { display: table-header-group; }`,
  host: { '[class]': 'hostClass()' },
})
export class TableHeaderComponent {
  className = input('');
  hostClass = computed(() =>
    ['border-b border-gray-100 bg-gray-50 dark:border-white/[0.05] dark:bg-white/[0.02]', this.className()]
      .filter(Boolean).join(' ')
  );
}

// Host element IS the <tbody>
@Component({
  selector: 'app-table-body',
  template: `<ng-content></ng-content>`,
  styles: `:host { display: table-row-group; }`,
  host: { '[class]': 'hostClass()' },
})
export class TableBodyComponent {
  className = input('');
  hostClass = computed(() =>
    ['divide-y divide-gray-100 dark:divide-white/[0.05]', this.className()]
      .filter(Boolean).join(' ')
  );
}

// Host element IS the <tr>
@Component({
  selector: 'app-table-row',
  template: `<ng-content></ng-content>`,
  styles: `:host { display: table-row; }`,
  host: { '[class]': 'rowClass()' },
})
export class TableRowComponent {
  noHover   = input(false, { transform: booleanAttribute });
  className = input('');

  rowClass = computed(() =>
    [!this.noHover() && 'hover:bg-gray-50 dark:hover:bg-white/[0.02]', this.className()]
      .filter(Boolean).join(' ')
  );
}

type CellAlign   = 'left' | 'right' | 'center';
type CellVariant = 'default' | 'muted' | 'accent' | 'strong' | 'none';

const ALIGN_CLASS: Record<CellAlign, string> = {
  left:   'text-left',
  right:  'text-right',
  center: 'text-center',
};

const HEADER_BASE = 'text-xs font-medium uppercase text-gray-500 dark:text-gray-400';

const CELL_VARIANT: Record<CellVariant, string> = {
  default: 'text-sm text-gray-800 dark:text-white/90',
  muted:   'text-sm text-gray-500 dark:text-gray-400',
  accent:  'text-sm font-medium text-brand-500',
  strong:  'text-sm font-medium text-gray-800 dark:text-white/90',
  none:    '',
};

// Host element IS the <th>/<td> — display: table-cell
@Component({
  selector: 'app-table-cell',
  template: `<ng-content></ng-content>`,
  styles: `:host { display: table-cell; vertical-align: middle; }`,
  host: {
    '[attr.role]': 'isHeader() ? "columnheader" : "cell"',
    '[class]': 'cellClass()',
  },
})
export class TableCellComponent {
  isHeader  = input(false, { transform: booleanAttribute });
  align     = input<CellAlign>('left');
  variant   = input<CellVariant>('default');
  className = input('');

  cellClass = computed(() => {
    const padding = this.isHeader() ? 'px-5 py-3' : 'px-5 py-4';
    const style   = this.isHeader() ? HEADER_BASE : CELL_VARIANT[this.variant()];
    const align   = ALIGN_CLASS[this.align()];
    return [padding, style, align, this.className()].filter(Boolean).join(' ');
  });
}
