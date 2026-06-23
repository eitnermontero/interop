import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { API_CONFIG, ApiConfig } from './api-config';

export function provideMdqrSdk(config: ApiConfig): EnvironmentProviders {
  return makeEnvironmentProviders([{ provide: API_CONFIG, useValue: config }]);
}
