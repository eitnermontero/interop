import { HttpClient } from '@angular/common/http';
import { EnvironmentProviders, importProvidersFrom } from '@angular/core';
import { TranslateLoader, TranslateModule } from '@ngx-translate/core';
import { TranslateHttpLoader } from '@ngx-translate/http-loader';

export function provideI18n(): EnvironmentProviders {
  return importProvidersFrom(
    TranslateModule.forRoot({
      defaultLanguage: 'es',
      loader: {
        provide: TranslateLoader,
        // Prefijo relativo (sin leading slash): el navegador resuelve la XHR contra
        // document.baseURI (<base href> = context-path), no contra el origin. Con
        // '/assets/...' absoluto, bajo /mdqradmin/ pediria /assets/... y daria 404.
        useFactory: (http: HttpClient) =>
          new TranslateHttpLoader(http, 'assets/i18n/', '.json'),
        deps: [HttpClient],
      },
    })
  );
}
