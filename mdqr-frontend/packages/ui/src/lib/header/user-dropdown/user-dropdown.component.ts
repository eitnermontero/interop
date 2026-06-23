import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DropdownComponent } from '../../dropdown/dropdown.component';
import { LAYOUT_USER_PROVIDER } from '../../layout/layout-user.token';

@Component({
  selector: 'app-user-dropdown',
  templateUrl: './user-dropdown.component.html',
  imports: [CommonModule, RouterModule, TranslateModule, DropdownComponent],
})
export class UserDropdownComponent {
  private readonly provider = inject(LAYOUT_USER_PROVIDER, { optional: true });

  readonly user = this.provider?.user ?? computed(() => null);
  readonly displayName = computed(() => {
    const u = this.user();
    if (!u) return '';
    const full = [u.firstName, u.lastName].filter(Boolean).join(' ').trim();
    return full || u.username;
  });

  isOpen = false;

  toggleDropdown() {
    this.isOpen = !this.isOpen;
  }

  closeDropdown() {
    this.isOpen = false;
  }

  logout() {
    this.provider?.logout();
  }
}
