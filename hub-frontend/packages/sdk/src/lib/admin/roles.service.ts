import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { CreateRoleRequest, RoleDto, UpdateRoleRequest } from './admin.types';

@Injectable({ providedIn: 'root' })
export class RolesAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/roles`;

  list(): Observable<RoleDto[]> {
    return this.http.get<RoleDto[]>(this.base);
  }

  getByName(name: string): Observable<RoleDto> {
    return this.http.get<RoleDto>(`${this.base}/${name}`);
  }

  create(body: CreateRoleRequest): Observable<RoleDto> {
    return this.http.post<RoleDto>(this.base, body);
  }

  update(name: string, body: UpdateRoleRequest): Observable<RoleDto> {
    return this.http.put<RoleDto>(`${this.base}/${name}`, body);
  }

  delete(name: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${name}`);
  }

  getMenus(name: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/${name}/menus`);
  }

  updateMenus(name: string, body: { menuCodes: string[] }): Observable<void> {
    return this.http.put<void>(`${this.base}/${name}/menus`, body);
  }
}
