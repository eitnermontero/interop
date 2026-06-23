import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthPageLayoutComponent } from '../layout/auth-page-layout/auth-page-layout.component';
import { ButtonComponent } from '../button/button.component';
import { LAYOUT_USER_PROVIDER } from '../layout/layout-user.token';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [AuthPageLayoutComponent, ButtonComponent],
  templateUrl: './login-page.component.html',
})
export class LoginPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly userProvider = inject(LAYOUT_USER_PROVIDER, { optional: true });

  readonly isLoading = signal(false);
  readonly errorMessage = signal('');

  ngOnInit(): void {
    const error = this.route.snapshot.queryParamMap.get('error');
    if (error === 'authentication_failed') {
      this.errorMessage.set('Error al autenticarse. Intente de nuevo.');
      return;
    }

    if (this.userProvider?.user()) {
      const redirect = this.route.snapshot.queryParamMap.get('redirect') ?? '/';
      this.router.navigateByUrl(redirect);
    }
  }

  onLogin(): void {
    this.isLoading.set(true);
    this.userProvider?.login?.();
  }
}
