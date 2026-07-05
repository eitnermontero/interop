import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { CreateMenuRequest, MenuDto, UpdateMenuRequest } from './admin.types';

@Injectable({ providedIn: 'root' })
export class MenusAdminApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubadminservice/admin/menus`;

  list(): Observable<MenuDto[]> {
    return this.http.get<MenuDto[]>(this.base);
  }

  getById(id: number): Observable<MenuDto> {
    return this.http.get<MenuDto>(`${this.base}/${id}`);
  }

  create(body: CreateMenuRequest): Observable<MenuDto> {
    return this.http.post<MenuDto>(this.base, body);
  }

  update(id: number, body: UpdateMenuRequest): Observable<MenuDto> {
    return this.http.put<MenuDto>(`${this.base}/${id}`, body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  reorder(items: Array<{ id: number; orderIndex: number }>): Observable<void> {
    return this.http.put<void>(`${this.base}/reorder`, { items });
  }
}
