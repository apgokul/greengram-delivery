import { apiClient } from './client';
import {
  OrderDetail,
  OrderFilterParams,
  OrderSummary,
  OrderSummaryCounts,
  PageResponse
} from '../types/order';

export const ordersApi = {
  /**
   * Fetches paginated, searchable, filterable list of orders.
   */
  async getOrders(params: OrderFilterParams = {}): Promise<PageResponse<OrderSummary>> {
    const query = new URLSearchParams();

    if (params.page !== undefined && params.page >= 0) {
      query.set('page', params.page.toString());
    }
    if (params.size !== undefined && params.size > 0) {
      query.set('size', params.size.toString());
    }
    if (params.search && params.search.trim().length > 0) {
      query.set('search', params.search.trim());
    }
    if (params.status && params.status.length > 0) {
      query.set('status', params.status.join(','));
    }
    if (params.startDate) {
      query.set('startDate', params.startDate);
    }
    if (params.endDate) {
      query.set('endDate', params.endDate);
    }
    if (params.hasLocation !== undefined) {
      query.set('hasLocation', params.hasLocation.toString());
    }
    if (params.needsAttention !== undefined) {
      query.set('needsAttention', params.needsAttention.toString());
    }
    if (params.sort) {
      query.set('sort', params.sort);
    }

    const qs = query.toString();
    const endpoint = `/api/admin/orders${qs ? `?${qs}` : ''}`;
    return apiClient.get<PageResponse<OrderSummary>>(endpoint);
  },

  /**
   * Fetches global summary KPI counts for today in Asia/Kolkata.
   */
  async getSummaryCounts(): Promise<OrderSummaryCounts> {
    return apiClient.get<OrderSummaryCounts>('/api/admin/orders/summary');
  },

  /**
   * Fetches comprehensive details for a single order by its numeric ID.
   */
  async getOrderById(id: number): Promise<OrderDetail> {
    return apiClient.get<OrderDetail>(`/api/admin/orders/${id}`);
  }
};
