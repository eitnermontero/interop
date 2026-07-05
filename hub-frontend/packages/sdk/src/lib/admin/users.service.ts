import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { PageRequest, PageResponse } from '../http/page-response';
import type {
  CreateUserRequest,
  ResetPasswordRequest,
  UpdateRolesRequest,
  UpdateStatusRequest,
  UpdateUserRequest,
  UserDto,
} from './admin.types';

@Injectable({ providedIn: 'root' })
export class UsersAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/users`;

  list(filters?: { search?: string; enabled?: boolean; page?: number; size?: number }): Observable<PageResponse<UserDto>> {
    let params = new HttpParams();
    if (filters?.search) params = params.set('search', filters.search);
    if (filters?.enabled !== undefined) params = params.set('enabled', String(filters.enabled));
    if (filters?.page !== undefined) params = params.set('page', String(filters.page));
    if (filters?.size !== undefined) params = params.set('size', String(filters.size));
    return this.http.get<PageResponse<UserDto>>(this.base, { params });
  }

  getById(userId: string): Observable<UserDto> {
    return this.http.get<UserDto>(`${this.base}/${userId}`);
  }

  create(body: CreateUserRequest): Observable<UserDto> {
    return this.http.post<UserDto>(this.base, body);
  }

  update(userId: string, body: UpdateUserRequest): Observable<UserDto> {
    return this.http.put<UserDto>(`${this.base}/${userId}`, body);
  }

  delete(userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${userId}`);
  }

  resetPassword(userId: string, body: ResetPasswordRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/${userId}/password`, body);
  }

  updateStatus(userId: string, body: UpdateStatusRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/${userId}/status`, body);
  }

  getRoles(userId: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/${userId}/roles`);
  }

  updateRoles(userId: string, body: UpdateRolesRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/${userId}/roles`, body);
  }

  sendPasswordReset(userId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${userId}/send-reset`, {});
  }
}
