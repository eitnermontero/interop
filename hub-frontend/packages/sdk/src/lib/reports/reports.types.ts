export type ReportType = 'TRANSACTIONS' | 'AUDIT' | 'SUMMARY' | 'CUSTOM';
export type ReportStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface ReportRequest {
  title: string;
  type: ReportType;
  parameters?: string;
}

export interface ReportResponse {
  id: string;
  code: string;
  title: string;
  type: ReportType;
  status: ReportStatus;
  parameters: string | null;
  result: string | null;
  errorMessage: string | null;
  requestedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  createdDate: string;
  lastModifiedDate: string;
}
