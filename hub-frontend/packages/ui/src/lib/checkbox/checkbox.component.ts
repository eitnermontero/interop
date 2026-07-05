import { Component, Input, Output, EventEmitter, computed } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-checkbox',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './checkbox.component.html',
  styles: ``,
})
export class CheckboxComponent {
  @Input() label: string = '';
  @Input() checked: boolean = false;
  @Input() disabled: boolean = false;
  @Input() id: string = '';
  @Input() ariaLabel: string = '';
  @Output() checkedChange = new EventEmitter<boolean>();

  onToggle() {
    if (!this.disabled) {
      this.checked = !this.checked;
      this.checkedChange.emit(this.checked);
    }
  }

  get containerClasses(): string {
    return `flex items-center gap-2 cursor-pointer ${this.disabled ? 'opacity-60 cursor-not-allowed' : ''}`;
  }

  get checkboxClasses(): string {
    return `${this.disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
  }
}
