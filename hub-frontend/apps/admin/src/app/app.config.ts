import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideHubAuth } from '@hub/auth';
import { provideHubSdk } from '@hub/sdk';
import { provideI18n } from '@hub/utils';

import { routes } from './app.routes';
import { layoutUserProvider } from './layout-user.provider';
import { RuntimeConfig, escapeRegExp } from './runtime-config';

export function appConfig(cfg: RuntimeConfig): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      provideHttpClient(),
      provideRouter(routes),
      provideHubAuth({
        authority: cfg.keycloak.url,
        realm: cfg.keycloak.realm,
        clientId: cfg.keycloak.clientId,
        bearerUrls: ['^' + escapeRegExp(cfg.apiUrl) + '/.*'],
      }),
      provideHubSdk({ baseUrl: cfg.apiUrl }),
      provideI18n(),
      layoutUserProvider,
    ],
  };
}
