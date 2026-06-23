import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { AuditLogDto, AuditLogFilter } from './admin.types';

export interface AuditPageResponse {
  content: AuditLogDto[];
  pageable: { pageNumber: number; pageSize: number };
  totalElements: number;
  totalPages: number;
  last: boolean;
  first: boolean;
  numberOfElements: number;
  size: number;
  number: number;
  empty: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuditAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/mdqradminservice/admin/audit`;

  search(filter: AuditLogFilter & { page?: number; size?: number; sort?: string }): Observable<AuditPageResponse> {
    let params = new HttpParams();
    if (filter.from) params = params.set('from', filter.from);
    if (filter.to) params = params.set('to', filter.to);
    if (filter.username) params = params.set('username', filter.username);
    if (filter.userId) params = params.set('userId', filter.userId);
    if (filter.eventTypes?.length) filter.eventTypes.forEach(et => (params = params.append('eventTypes', et)));
    if (filter.modules?.length) filter.modules.forEach(m => (params = params.append('modules', m)));
    if (filter.serviceName) params = params.set('serviceName', filter.serviceName);
    if (filter.ipAddress) params = params.set('ipAddress', filter.ipAddress);
    if (filter.responseStatuses?.length) filter.responseStatuses.forEach(rs => (params = params.append('responseStatuses', String(rs))));
    if (filter.q) params = params.set('q', filter.q);
    if (filter.page !== undefined) params = params.set('page', String(filter.page));
    if (filter.size !== undefined) params = params.set('size', String(filter.size));
    if (filter.sort) params = params.set('sort', filter.sort);
    return this.http.get<AuditPageResponse>(this.base, { params });
  }

  getById(id: number): Observable<AuditLogDto> {
    return this.http.get<AuditLogDto>(`${this.base}/${id}`);
  }

  getEventTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/event-types`);
  }

  getModules(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/modules`);
  }

  exportUrl(filter: AuditLogFilter): string {
    let params = new HttpParams();
    if (filter.from) params = params.set('from', filter.from);
    if (filter.to) params = params.set('to', filter.to);
    if (filter.username) params = params.set('username', filter.username);
    if (filter.userId) params = params.set('userId', filter.userId);
    if (filter.eventTypes?.length) filter.eventTypes.forEach(et => (params = params.append('eventTypes', et)));
    if (filter.modules?.length) filter.modules.forEach(m => (params = params.append('modules', m)));
    if (filter.serviceName) params = params.set('serviceName', filter.serviceName);
    if (filter.ipAddress) params = params.set('ipAddress', filter.ipAddress);
    if (filter.responseStatuses?.length) filter.responseStatuses.forEach(rs => (params = params.append('responseStatuses', String(rs))));
    if (filter.q) params = params.set('q', filter.q);
    return `${this.base}/export?${params.toString()}`;
  }
}
