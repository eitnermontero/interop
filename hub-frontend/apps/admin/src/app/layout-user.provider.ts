import { Provider, computed, inject } from '@angular/core';
import { AuthFacade } from '@hub/auth';
import { LAYOUT_USER_PROVIDER, LayoutUser, LayoutUserProvider } from '@hub/ui';

export const layoutUserProvider: Provider = {
  provide: LAYOUT_USER_PROVIDER,
  useFactory: (): LayoutUserProvider => {
    const auth = inject(AuthFacade);
    return {
      user: computed<LayoutUser | null>(() => {
        const u = auth.user();
        if (!u) return null;
        return {
          username: u.username,
          email: u.email,
          firstName: u.firstName,
          lastName: u.lastName,
        };
      }),
      // document.baseURI (= origin + <base href>) respeta el context-path. Con
      // origin a secas, bajo /hubadmin/ el 302 de nginx a la raiz descartaria el
      // ?code de Keycloak y el callback fallaria.
      login: () => auth.login(document.baseURI),
      logout: () => auth.logout(document.baseURI),
    };
  },
};
