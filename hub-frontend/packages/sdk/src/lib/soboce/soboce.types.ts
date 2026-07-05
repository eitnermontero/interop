export interface Debt {
  descripcion: string;
  llave: string;
  monto: number;
}

export interface DebtsResponse {
  debts: Debt[];
}

export interface Payment {
  clientCode: string;
  codDep: number;
  status: string;
  date: string;
  time: string;
  amount: number;
  movKey: string;
  paymentNumber: string;
  oriTra: string;
  sequential: number;
}

export interface PaymentsResponse {
  clientCode: string;
  billingName: string;
  payments: Payment[];
}

export interface CashPayment {
  codDep: number;
  status: string;
  date: string;
  time: string;
  amount: number;
  movKey: string;
  paymentNumber: string;
  oriTra: string;
  sequential: number;
}

export interface CashPaymentsResponse {
  count: number;
  payments: CashPayment[];
}

export interface ReprintResponse {
  count: number;
  pdfs: string[];
}

export interface ComprobantePdfResponse {
  pdf: string;
}

export interface ReportLinesResponse {
  count: number;
  lines: string[];
}

export interface DebtsQuery {
  code: string;
}

export interface PaymentsQuery {
  code: string;
  from: string;
  to: string;
  service?: number;
}

export interface CashPaymentsQuery {
  code: string;
  from: string;
  to: string;
  type?: string;
  company?: string;
}

export interface ReprintQuery {
  code: string;
  movement: string;
  sequential: number;
  type?: string;
}

export interface ComprobantePdfQuery {
  code: string;
  from: string;
  to: string;
}

export interface CollectionReportQuery {
  date: string;
  type: string;
  company: string;
}

export interface AdvancePaymentsReportQuery {
  date: string;
  type: string;
}

export interface ReconciliationReportQuery {
  from: string;
  to: string;
}
