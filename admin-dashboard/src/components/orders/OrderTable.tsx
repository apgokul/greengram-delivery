import React from 'react';
import { OrderStatus, OrderSummary, PageResponse } from '../../types/order';
import { formatCurrency, formatPhone, formatTimestamp } from '../../utils/orderFormatters';

interface OrderTableProps {
  pageData: PageResponse<OrderSummary> | null;
  loading: boolean;
  error: string | null;
  onPageChange: (newPage: number) => void;
  onViewDetails: (orderId: number) => void;
  onRetry: () => void;
}

export const OrderTable: React.FC<OrderTableProps> = ({
  pageData,
  loading,
  error,
  onPageChange,
  onViewDetails,
  onRetry
}) => {
  if (loading) {
    return (
      <div className="table-container" style={{ padding: '3.5rem', textAlign: 'center' }}>
        <div className="spinner" style={{ margin: '0 auto', borderColor: 'rgba(59, 130, 246, 0.3)', borderTopColor: '#3b82f6' }} />
        <p style={{ marginTop: '1rem', color: 'var(--text-secondary)' }}>Loading orders...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="table-container" style={{ padding: '2.5rem', textAlign: 'center' }}>
        <div className="alert alert-error" style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', gap: '0.75rem' }}>
          <p>⚠️ Error loading orders: {error}</p>
          <button className="btn btn-secondary btn-sm" onClick={onRetry}>
            Retry Loading
          </button>
        </div>
      </div>
    );
  }

  const orders = pageData?.content || [];

  if (orders.length === 0) {
    return (
      <div className="table-container" style={{ padding: '3rem', textAlign: 'center' }}>
        <p style={{ fontSize: '1.125rem', color: 'var(--text-primary)', marginBottom: '0.5rem' }}>
          📦 No orders found
        </p>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
          Try clearing search, adjusting status filters, or selecting a different date range.
        </p>
      </div>
    );
  }

  const getStatusBadge = (status: OrderStatus) => {
    switch (status) {
      case 'CREATED':
        return <span className="status-badge status-created">Created</span>;
      case 'PENDING_WAREHOUSE':
        return <span className="status-badge status-pending">Pending Warehouse</span>;
      case 'PACKING':
        return <span className="status-badge status-packing">Packing</span>;
      case 'READY':
        return <span className="status-badge status-ready">Ready for Pickup</span>;
      case 'DISPATCHED':
        return <span className="status-badge status-dispatched">Out for Delivery</span>;
      case 'DELIVERED':
        return <span className="status-badge status-delivered">Delivered</span>;
      case 'CANCELLED':
        return <span className="status-badge status-cancelled">Cancelled</span>;
      default:
        return <span className="badge badge-neutral">{status}</span>;
    }
  };

  const currentPage = pageData?.page ?? 0;
  const totalPages = pageData?.totalPages ?? 1;
  const totalElements = pageData?.totalElements ?? 0;
  const pageSize = pageData?.size ?? 20;

  const startRecord = totalElements > 0 ? currentPage * pageSize + 1 : 0;
  const endRecord = Math.min((currentPage + 1) * pageSize, totalElements);

  return (
    <div className="table-container">
      <table className="data-table">
        <thead>
          <tr>
            <th>Order ID</th>
            <th>Customer</th>
            <th>Placed At</th>
            <th>Status</th>
            <th style={{ textAlign: 'center' }}>Items</th>
            <th style={{ textAlign: 'right' }}>Total Amount</th>
            <th>Location</th>
            <th>Attention</th>
            <th style={{ textAlign: 'center' }}>Action</th>
          </tr>
        </thead>
        <tbody>
          {orders.map(order => (
            <tr key={order.id} className="order-row">
              {/* 1. Order ID */}
              <td>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{order.displayId}</span>
                  <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>ID: #{order.id}</span>
                </div>
              </td>

              {/* 2. Customer */}
              <td>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <span style={{ fontWeight: 500 }}>{order.customerName}</span>
                  <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                    {formatPhone(order.customerPhone)}
                  </span>
                </div>
              </td>

              {/* 3. Placed At */}
              <td>
                <span style={{ fontSize: '0.85rem' }}>{formatTimestamp(order.createdAt)}</span>
              </td>

              {/* 4. Status */}
              <td>{getStatusBadge(order.status)}</td>

              {/* 5. Items */}
              <td style={{ textAlign: 'center' }}>
                <span className="items-count-badge">{order.itemCount}</span>
              </td>

              {/* 6. Total Amount */}
              <td style={{ textAlign: 'right', fontWeight: 600 }}>
                {formatCurrency(order.totalAmount)}
              </td>

              {/* 7. Location Availability */}
              <td>
                {order.hasLocation ? (
                  <span className="location-badge location-present" title="GPS coordinates confirmed">
                    📍 Confirmed
                  </span>
                ) : (
                  <span className="location-badge location-missing" title="No coordinates provided">
                    ⚠️ Missing
                  </span>
                )}
              </td>

              {/* 8. Attention Indicators */}
              <td>
                {order.needsAttention ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    {order.attentionReasons.map(reason => {
                      if (reason === 'STOCK_RECONCILIATION_REQUIRED') {
                        return (
                          <span key={reason} className="attention-tag tag-stock" title="Stock reconciliation needed">
                            Stock Discrepancy
                          </span>
                        );
                      }
                      if (reason === 'LOCATION_MISSING') {
                        return (
                          <span key={reason} className="attention-tag tag-location" title="Delivery location missing for active order">
                            Pin Missing
                          </span>
                        );
                      }
                      return (
                        <span key={reason} className="attention-tag tag-general">
                          {reason}
                        </span>
                      );
                    })}
                  </div>
                ) : (
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>—</span>
                )}
              </td>

              {/* 9. Action */}
              <td style={{ textAlign: 'center' }}>
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => onViewDetails(order.id)}
                  title="View complete order details, items snapshot and timeline"
                >
                  View Details
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Pagination Footer */}
      <div className="table-pagination-footer">
        <div className="pagination-info">
          Showing <span style={{ fontWeight: 600 }}>{startRecord}</span> to{' '}
          <span style={{ fontWeight: 600 }}>{endRecord}</span> of{' '}
          <span style={{ fontWeight: 600 }}>{totalElements}</span> orders
        </div>

        <div className="pagination-controls">
          <button
            className="btn btn-secondary btn-sm"
            disabled={currentPage === 0}
            onClick={() => onPageChange(currentPage - 1)}
          >
            ← Previous
          </button>

          <span className="page-indicator">
            Page {currentPage + 1} of {Math.max(1, totalPages)}
          </span>

          <button
            className="btn btn-secondary btn-sm"
            disabled={currentPage >= totalPages - 1 || pageData?.last}
            onClick={() => onPageChange(currentPage + 1)}
          >
            Next →
          </button>
        </div>
      </div>
    </div>
  );
};
