import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';
import { HUB_AUTH_CONFIG } from './auth-config';

const isAccessAllowed = async (
  _route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
  data: AuthGuardData,
): Promise<boolean> => {
  const { authenticated, grantedRoles } = data;
  const config = inject(HUB_AUTH_CONFIG);
  const router = inject(Router);

  if (!authenticated) {
    router.navigate(['/auth/login'], { queryParams: { redirect: state.url } });
    return false;
  }

  const requiredRoles = config.requiredRoles ?? [];
  if (requiredRoles.length === 0) return true;

  const userRoles = grantedRoles.realmRoles ?? [];
  const allowed = requiredRoles.some((role) => userRoles.includes(role));

  if (!allowed) {
    router.navigate(['/forbidden']);
    return false;
  }

  return true;
};

export const hubAuthGuard: CanActivateFn = createAuthGuard<CanActivateFn>(isAccessAllowed as never);
