import { useState, useEffect, useRef, useCallback } from 'react';
import { DatePreset, OrderFilterParams, OrderSummary, OrderSummaryCounts, PageResponse } from '../types/order';
import { ordersApi } from '../api/orders';
import { resolveDatePreset } from '../utils/orderFormatters';
import { OrderSummaryCards } from '../components/orders/OrderSummaryCards';
import { OrderFilters } from '../components/orders/OrderFilters';
import { OrderTable } from '../components/orders/OrderTable';
import { OrderDetailModal } from '../components/orders/OrderDetailModal';

export default function OrdersPage() {
  // Summary KPI state
  const [summaryCounts, setSummaryCounts] = useState<OrderSummaryCounts | null>(null);
  const [loadingSummary, setLoadingSummary] = useState<boolean>(true);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  // Table & Filter state
  const [datePreset, setDatePreset] = useState<DatePreset>('TODAY');
  const [filters, setFilters] = useState<OrderFilterParams>(() => {
    const { startDate, endDate } = resolveDatePreset('TODAY');
    return {
      page: 0,
      size: 20,
      startDate,
      endDate,
      sort: 'createdAt,desc'
    };
  });

  const [pageData, setPageData] = useState<PageResponse<OrderSummary>>({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    last: true
  });
  const [loadingOrders, setLoadingOrders] = useState<boolean>(true);
  const [ordersError, setOrdersError] = useState<string | null>(null);

  // Selected Order for Detail Modal
  const [selectedOrderId, setSelectedOrderId] = useState<number | null>(null);

  // Stale request protection
  const latestRequestIdRef = useRef<number>(0);

  // 1. Fetch Storewide Summary Counts (independent of table filters)
  const fetchSummaryCounts = useCallback(async () => {
    setLoadingSummary(true);
    setSummaryError(null);
    try {
      const data = await ordersApi.getSummaryCounts();
      setSummaryCounts(data);
    } catch (err: any) {
      setSummaryError(err.message || 'Failed to fetch summary counts');
    } finally {
      setLoadingSummary(false);
    }
  }, []);

  useEffect(() => {
    fetchSummaryCounts();
  }, [fetchSummaryCounts]);

  // 2. Fetch Filtered Paginated Orders
  const fetchOrders = useCallback(async (currentFilters: OrderFilterParams) => {
    const requestId = ++latestRequestIdRef.current;
    setLoadingOrders(true);
    setOrdersError(null);

    try {
      const data = await ordersApi.getOrders(currentFilters);
      // Only commit if this request is still the newest one
      if (requestId === latestRequestIdRef.current) {
        setPageData(data);
        setOrdersError(null);
      }
    } catch (err: any) {
      if (requestId === latestRequestIdRef.current) {
        setOrdersError(err.message || 'Failed to load orders');
        setPageData({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          last: true
        });
      }
    } finally {
      if (requestId === latestRequestIdRef.current) {
        setLoadingOrders(false);
      }
    }
  }, []);

  useEffect(() => {
    fetchOrders(filters);
  }, [filters, fetchOrders]);

  // Handlers
  const handleFiltersChange = (newFilters: OrderFilterParams) => {
    setFilters(newFilters);
  };

  const handlePageChange = (newPage: number) => {
    setFilters(prev => ({ ...prev, page: newPage }));
  };

  const handleResetFilters = () => {
    setDatePreset('TODAY');
    const { startDate, endDate } = resolveDatePreset('TODAY');
    setFilters({
      page: 0,
      size: 20,
      startDate,
      endDate,
      sort: 'createdAt,desc'
    });
  };

  return (
    <div className="orders-page-container">
      {/* Page Header */}
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h1 style={{ marginBottom: '0.25rem' }}>Orders Dashboard</h1>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Storewide operations, delivery fulfillment, and order details
          </p>
        </div>
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => {
            fetchSummaryCounts();
            fetchOrders(filters);
          }}
          title="Refresh summary counts and orders list"
        >
          🔄 Refresh
        </button>
      </div>

      {/* 1. Global KPI Summary Cards */}
      <OrderSummaryCards
        counts={summaryCounts}
        loading={loadingSummary}
        error={summaryError}
        onRetry={fetchSummaryCounts}
      />

      {/* 2. Interactive Search & Filters Bar */}
      <OrderFilters
        filters={filters}
        onChange={handleFiltersChange}
        datePreset={datePreset}
        onDatePresetChange={setDatePreset}
        onReset={handleResetFilters}
      />

      {/* 3. Paginated Orders Table */}
      <OrderTable
        pageData={pageData}
        loading={loadingOrders}
        error={ordersError}
        onPageChange={handlePageChange}
        onViewDetails={(id: number) => setSelectedOrderId(id)}
        onRetry={() => fetchOrders(filters)}
      />

      {/* 4. Order Details Modal */}
      <OrderDetailModal
        orderId={selectedOrderId}
        onClose={() => setSelectedOrderId(null)}
      />
    </div>
  );
}
