import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { RolePermissionsResponse, SetPermissionsRequest } from './admin.types';

@Injectable({ providedIn: 'root' })
export class PermissionsAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/roles`;

  getByRole(roleName: string): Observable<RolePermissionsResponse> {
    return this.http.get<RolePermissionsResponse>(`${this.base}/${roleName}/permissions`);
  }

  setByRole(roleName: string, body: SetPermissionsRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/${roleName}/permissions`, body);
  }
}
