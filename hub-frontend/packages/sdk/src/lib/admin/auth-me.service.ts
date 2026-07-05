import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { MeResponse, PermissionsTreeResponse } from './admin.types';

@Injectable({ providedIn: 'root' })
export class AuthMeApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/auth`;

  me(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${this.base}/me`);
  }

  mePermissions(): Observable<PermissionsTreeResponse> {
    return this.http.get<PermissionsTreeResponse>(`${this.base}/me/permissions`);
  }
}
