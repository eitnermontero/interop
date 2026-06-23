import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { RuntimeConfig } from './app/runtime-config';

fetch('assets/config.json')
  .then((r) => {
    if (!r.ok) {
      throw new Error('assets/config.json fetch failed: ' + r.status);
    }
    return r.json() as Promise<RuntimeConfig>;
  })
  .then((cfg) => bootstrapApplication(App, appConfig(cfg)))
  .catch((err) => console.error('Bootstrap aborted:', err));
