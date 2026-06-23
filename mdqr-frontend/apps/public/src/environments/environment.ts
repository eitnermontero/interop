import { MdqrAuthConfig } from '@mdqr/auth';

export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8000',
  auth: {
    authority: 'http://localhost:8080',
    realm: 'middleware-core',
    clientId: 'mdqr-public-fe',
    bearerUrls: ['^http://localhost:8000/.*'],
  } satisfies MdqrAuthConfig,
};
