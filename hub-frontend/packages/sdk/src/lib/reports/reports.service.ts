import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type { PageRequest, PageResponse } from '../http/page-response';
import type { ReportRequest, ReportResponse } from './reports.types';

@Injectable({ providedIn: 'root' })
export class ReportsApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubreportservice/api/v1/reports`;

  create(body: ReportRequest): Observable<ReportResponse> {
    return this.http.post<ReportResponse>(this.base, body);
  }

  list(page: PageRequest = {}): Observable<PageResponse<ReportResponse>> {
    let params = new HttpParams();
    if (page.page !== undefined) params = params.set('page', String(page.page));
    if (page.size !== undefined) params = params.set('size', String(page.size));
    return this.http.get<PageResponse<ReportResponse>>(this.base, { params });
  }

  getById(id: string): Observable<ReportResponse> {
    return this.http.get<ReportResponse>(`${this.base}/${id}`);
  }

  getByCode(code: string): Observable<ReportResponse> {
    return this.http.get<ReportResponse>(`${this.base}/code/${code}`);
  }
}
