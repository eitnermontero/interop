import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  AutoRefreshTokenService,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
  includeBearerTokenInterceptor,
  provideKeycloak,
  UserActivityService,
  withAutoRefreshToken,
} from 'keycloak-angular';
import { HUB_AUTH_CONFIG, HubAuthConfig } from './auth-config';

export function provideHubAuth(config: HubAuthConfig): EnvironmentProviders {
  const bearerConditions = config.bearerUrls.map((urlPattern) =>
    createInterceptorCondition<IncludeBearerTokenCondition>({
      urlPattern: new RegExp(urlPattern),
    }),
  );

  return makeEnvironmentProviders([
    { provide: HUB_AUTH_CONFIG, useValue: config },
    provideKeycloak({
      config: {
        url: config.authority,
        realm: config.realm,
        clientId: config.clientId,
      },
      initOptions: {
        onLoad: 'check-sso',
        // Relativo al <base href> (context-path runtime), no al origin: bajo
        // /hubadmin/ el iframe debe cargar /hubadmin/assets/..., no /assets/...
        silentCheckSsoRedirectUri: new URL('assets/silent-check-sso.html', document.baseURI).href,
        pkceMethod: 'S256',
        // 3rd-party cookies are blocked by modern browsers; the login-status iframe
        // then times out / 403s. Token auto-refresh covers session expiry instead.
        checkLoginIframe: false,
      },
      features: [
        withAutoRefreshToken({
          onInactivityTimeout: 'logout',
          sessionTimeout: 1800_000,
        }),
      ],
      providers: [
        AutoRefreshTokenService,
        UserActivityService,
        { provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG, useValue: bearerConditions },
      ],
    }),
    provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
  ]);
}
