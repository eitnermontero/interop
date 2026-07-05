
import { Component, Input, Output, EventEmitter, ElementRef, ViewChild, AfterViewInit, OnDestroy } from '@angular/core';
import flatpickr from 'flatpickr';
import { LabelComponent } from '../label/label.component';
import 'flatpickr/dist/flatpickr.css';

@Component({
  selector: 'app-date-masck-picker',
  imports: [LabelComponent],
  templateUrl: './date-masck-picker.component.html',
  styles: ``
})
export class DateMasckPickerComponent implements AfterViewInit, OnDestroy {
  @Input() id!: string;
  @Input() label?: string;
  @Input() defaultDate?: string | Date;
  @Output() dateChange = new EventEmitter<{ date: Date | null; dateStr: string }>();

  @ViewChild('dateInput') dateInput!: ElementRef<HTMLInputElement>;

  private fp: flatpickr.Instance | undefined;
  private readonly EMPTY = '__/__/____';

  ngAfterViewInit(): void {
    const el = this.dateInput.nativeElement;

    this.fp = flatpickr(el, {
      static: true,
      monthSelectorType: 'static',
      dateFormat: 'd/m/Y',
      allowInput: true,
      onChange: (dates) => {
        if (dates.length > 0) {
          const d = dates[0];
          el.value = this.toMasked(d);
          this.dateChange.emit({ date: d, dateStr: el.value });
        }
      }
    });

    if (this.defaultDate) {
      const d = new Date(this.defaultDate as string);
      if (!isNaN(d.getTime())) { el.value = this.toMasked(d); return; }
    }
    el.value = this.EMPTY;
  }

  onKeyDown(event: KeyboardEvent): void {
    const input = event.target as HTMLInputElement;
    const pos = input.selectionStart ?? 0;
    const val = input.value;

    if (/^\d$/.test(event.key)) {
      event.preventDefault();
      const at = this.nextUnderscore(val, pos);
      if (at === -1) return;
      const chars = val.split('');
      chars[at] = event.key;
      input.value = chars.join('');
      const next = this.nextUnderscore(input.value, at + 1);
      const cur = next === -1 ? at + 1 : next;
      requestAnimationFrame(() => input.setSelectionRange(cur, cur));
      this.trySync(input.value);
      return;
    }

    if (event.key === 'Backspace') {
      event.preventDefault();
      const at = this.prevDigit(val, pos);
      if (at === -1) return;
      const chars = val.split('');
      chars[at] = '_';
      input.value = chars.join('');
      requestAnimationFrame(() => input.setSelectionRange(at, at));
      return;
    }

    if (event.key === 'Delete') {
      event.preventDefault();
      const at = this.digitAt(val, pos);
      if (at === -1) return;
      const chars = val.split('');
      chars[at] = '_';
      input.value = chars.join('');
      return;
    }

    if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Tab'].includes(event.key) ||
        event.ctrlKey || event.metaKey) return;

    event.preventDefault();
  }

  onFocus(event: FocusEvent): void {
    const input = event.target as HTMLInputElement;
    const first = this.nextUnderscore(input.value, 0);
    if (first !== -1) requestAnimationFrame(() => input.setSelectionRange(first, first));
  }

  onClick(event: MouseEvent): void {
    const input = event.target as HTMLInputElement;
    const pos = input.selectionStart ?? 0;
    const snap = this.nextUnderscore(input.value, pos);
    if (snap !== -1) requestAnimationFrame(() => input.setSelectionRange(snap, snap));
  }

  onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const digits = (event.clipboardData?.getData('text') ?? '').replace(/\D/g, '').substring(0, 8);
    if (!digits) return;
    const chars = this.EMPTY.split('');
    let di = 0;
    for (let i = 0; i < chars.length && di < digits.length; i++) {
      if (chars[i] === '_') chars[i] = digits[di++];
    }
    const input = event.target as HTMLInputElement;
    input.value = chars.join('');
    this.trySync(input.value);
  }

  private trySync(value: string): void {
    if (value.includes('_')) return;
    const [dd, mm, yyyy] = value.split('/');
    const day = +dd, month = +mm, year = +yyyy;
    if (year < 1900 || year > 2100) return;
    const d = new Date(year, month - 1, day);
    if (!isNaN(d.getTime()) && d.getDate() === day && d.getMonth() === month - 1) {
      this.fp?.setDate(d, true);
    }
  }

  private toMasked(d: Date): string {
    return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`;
  }

  private nextUnderscore(val: string, from: number): number {
    for (let i = from; i < val.length; i++) if (val[i] === '_') return i;
    return -1;
  }

  private prevDigit(val: string, from: number): number {
    for (let i = from - 1; i >= 0; i--) if (/\d/.test(val[i])) return i;
    return -1;
  }

  private digitAt(val: string, from: number): number {
    for (let i = from; i < val.length; i++) if (/\d/.test(val[i])) return i;
    return -1;
  }

  ngOnDestroy(): void {
    this.fp?.destroy();
  }
}
