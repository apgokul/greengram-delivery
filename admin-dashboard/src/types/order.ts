export type OrderStatus =
  | 'CREATED'
  | 'PENDING_WAREHOUSE'
  | 'PACKING'
  | 'READY'
  | 'DISPATCHED'
  | 'DELIVERED'
  | 'CANCELLED';

export interface OrderSummary {
  id: number;
  displayId: string;
  status: OrderStatus;
  totalAmount: number;
  createdAt: string;
  customerName: string;
  customerPhone: string | null;
  itemCount: number;
  hasLocation: boolean;
  stockReconciled: boolean;
  needsAttention: boolean;
  attentionReasons: string[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface OrderSummaryCounts {
  createdToday: number;
  inProgress: number;
  deliveredToday: number;
  needsAttention: number;
}

export interface CustomerInfo {
  id: number;
  name: string;
  whatsappNumber: string | null;
  addressNotes: string | null;
}

export interface DeliveryInfo {
  hasLocation: boolean;
  latitude: number | null;
  longitude: number | null;
  googleMapsUrl: string | null;
}

export interface DeliveryRunInfo {
  runId: number;
  riderPhoneNumber: string | null;
  dispatchedAt: string | null;
  status: string | null;
}

export interface OrderItemDetail {
  id: number;
  productSku: string;
  productName: string;
  packageQuantity: number | null;
  packageUnit: string | null;
  quantity: number;
  lockedPrice: number;
  lineTotal: number;
  deductedQuantity: number | null;
  deductedUnit: string | null;
  stockReconciliationRequired: boolean;
}

export interface TimelineEvent {
  milestone: string;
  title: string;
  timestamp: string | null;
  recorded: boolean;
  status: 'COMPLETED' | 'PENDING' | 'SKIPPED';
  detail: string | null;
}

export interface OrderDetail {
  id: number;
  displayId: string;
  status: OrderStatus;
  totalAmount: number;
  createdAt: string;
  readyAt: string | null;
  deliveredAt: string | null;
  cancellationReason: string | null;
  stockReconciled: boolean;
  needsAttention: boolean;
  attentionReasons: string[];
  customer: CustomerInfo | null;
  delivery: DeliveryInfo;
  deliveryRun: DeliveryRunInfo | null;
  items: OrderItemDetail[];
  timeline: TimelineEvent[];
}

export type DatePreset = 'TODAY' | 'YESTERDAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'ALL_TIME' | 'CUSTOM';

export type SortOption =
  | 'createdAt,desc'
  | 'createdAt,asc'
  | 'totalAmount,desc'
  | 'totalAmount,asc'
  | 'id,desc'
  | 'id,asc';

export interface OrderFilterParams {
  page?: number;
  size?: number;
  search?: string;
  status?: OrderStatus[];
  startDate?: string;
  endDate?: string;
  hasLocation?: boolean;
  needsAttention?: boolean;
  sort?: SortOption;
}
