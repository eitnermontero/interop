import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from '../api-config';
import type {
  AdvancePaymentsReportQuery,
  CashPaymentsQuery,
  CashPaymentsResponse,
  CollectionReportQuery,
  ComprobantePdfQuery,
  ComprobantePdfResponse,
  DebtsQuery,
  DebtsResponse,
  PaymentsQuery,
  PaymentsResponse,
  ReconciliationReportQuery,
  ReportLinesResponse,
  ReprintQuery,
  ReprintResponse,
} from './soboce.types';

@Injectable({ providedIn: 'root' })
export class SobocApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly base = `${this.config.baseUrl}/services/hubreportservice/api/v1/soboce`;

  getDebts(query: DebtsQuery): Observable<DebtsResponse> {
    return this.http.get<DebtsResponse>(`${this.base}/debts`, {
      params: this.toParams(query),
    });
  }

  getPayments(query: PaymentsQuery): Observable<PaymentsResponse> {
    return this.http.get<PaymentsResponse>(`${this.base}/payments`, {
      params: this.toParams(query),
    });
  }

  getCashPayments(query: CashPaymentsQuery): Observable<CashPaymentsResponse> {
    return this.http.get<CashPaymentsResponse>(`${this.base}/payments/cash`, {
      params: this.toParams(query),
    });
  }

  reprintPayments(query: ReprintQuery): Observable<ReprintResponse> {
    return this.http.get<ReprintResponse>(`${this.base}/payments/reprint`, {
      params: this.toParams(query),
    });
  }

  getComprobantePdf(query: ComprobantePdfQuery): Observable<ComprobantePdfResponse> {
    return this.http.get<ComprobantePdfResponse>(`${this.base}/comprobante/pdf`, {
      params: this.toParams(query),
    });
  }

  getCollectionReport(query: CollectionReportQuery): Observable<ReportLinesResponse> {
    return this.http.get<ReportLinesResponse>(`${this.base}/reports/collection`, {
      params: this.toParams(query),
    });
  }

  getAdvancePaymentsReport(query: AdvancePaymentsReportQuery): Observable<ReportLinesResponse> {
    return this.http.get<ReportLinesResponse>(`${this.base}/reports/advance-payments`, {
      params: this.toParams(query),
    });
  }

  getReconciliationReport(query: ReconciliationReportQuery): Observable<ReportLinesResponse> {
    return this.http.get<ReportLinesResponse>(`${this.base}/reports/reconciliation`, {
      params: this.toParams(query),
    });
  }

  private toParams(query: object): HttpParams {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return params;
  }
}
