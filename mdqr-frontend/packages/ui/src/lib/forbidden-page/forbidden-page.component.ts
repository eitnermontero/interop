import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterModule } from '@angular/router';
import { ButtonComponent } from '../button/button.component';
import { LAYOUT_USER_PROVIDER } from '../layout/layout-user.token';

@Component({
  selector: 'app-forbidden-page',
  standalone: true,
  imports: [CommonModule, RouterModule, ButtonComponent],
  templateUrl: './forbidden-page.component.html',
})
export class ForbiddenPageComponent {
  private readonly userProvider = inject(LAYOUT_USER_PROVIDER, { optional: true });

  readonly user = this.userProvider?.user;
  readonly year = new Date().getFullYear();

  logout() {
    this.userProvider?.logout();
  }
}
