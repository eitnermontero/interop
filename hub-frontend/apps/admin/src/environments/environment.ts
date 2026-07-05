import { HubAuthConfig } from '@hub/auth';

export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8080',
  auth: {
    authority: 'http://127.0.0.1:8180',
    realm: 'hub-admin',
    clientId: 'hub-admin-fe',
    bearerUrls: ['^http://localhost:8080/.*'],
  } satisfies HubAuthConfig,
};
