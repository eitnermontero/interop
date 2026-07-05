import { HubAuthConfig } from '@hub/auth';

export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8000',
  auth: {
    authority: 'http://localhost:8080',
    realm: 'hub-admin',
    clientId: 'hub-public-fe',
    bearerUrls: ['^http://localhost:8000/.*'],
  } satisfies HubAuthConfig,
};
