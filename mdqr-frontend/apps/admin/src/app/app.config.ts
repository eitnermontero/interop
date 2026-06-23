import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideMdqrAuth } from '@mdqr/auth';
import { provideMdqrSdk } from '@mdqr/sdk';
import { provideI18n } from '@mdqr/utils';

import { routes } from './app.routes';
import { layoutUserProvider } from './layout-user.provider';
import { RuntimeConfig, escapeRegExp } from './runtime-config';

export function appConfig(cfg: RuntimeConfig): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      provideHttpClient(),
      provideRouter(routes),
      provideMdqrAuth({
        authority: cfg.keycloak.url,
        realm: cfg.keycloak.realm,
        clientId: cfg.keycloak.clientId,
        bearerUrls: ['^' + escapeRegExp(cfg.apiUrl) + '/.*'],
      }),
      provideMdqrSdk({ baseUrl: cfg.apiUrl }),
      provideI18n(),
      layoutUserProvider,
    ],
  };
}
