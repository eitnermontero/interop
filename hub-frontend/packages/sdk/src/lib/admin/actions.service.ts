import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { ActionDto, CreateActionRequest, UpdateActionRequest } from './admin.types';

@Injectable({ providedIn: 'root' })
export class ActionsAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/actions`;

  list(): Observable<ActionDto[]> {
    return this.http.get<ActionDto[]>(this.base);
  }

  getById(id: number): Observable<ActionDto> {
    return this.http.get<ActionDto>(`${this.base}/${id}`);
  }

  create(body: CreateActionRequest): Observable<ActionDto> {
    return this.http.post<ActionDto>(this.base, body);
  }

  update(id: number, body: UpdateActionRequest): Observable<ActionDto> {
    return this.http.put<ActionDto>(`${this.base}/${id}`, body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
