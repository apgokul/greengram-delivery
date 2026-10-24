import React, { useState, useEffect } from 'react';
import { OrderDetail } from '../../types/order';
import { ordersApi } from '../../api/orders';
import { formatCurrency, formatPhone, formatTimestamp } from '../../utils/orderFormatters';

interface OrderDetailModalProps {
  orderId: number | null;
  onClose: () => void;
}

export const OrderDetailModal: React.FC<OrderDetailModalProps> = ({ orderId, onClose }) => {
  const [detail, setDetail] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [retryCount, setRetryCount] = useState<number>(0);

  // Close modal on Escape key
  useEffect(() => {
    if (orderId == null) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [orderId, onClose]);

  // Fetch details with stale-response protection
  useEffect(() => {
    if (orderId == null) {
      setDetail(null);
      setError(null);
      setLoading(false);
      return;
    }

    let isCurrent = true;
    setLoading(true);
    setError(null);
    setDetail(null); // Clear previous order data immediately to avoid flashing stale info

    ordersApi.getOrderById(orderId)
      .then(data => {
        if (isCurrent) {
          setDetail(data);
          setLoading(false);
        }
      })
      .catch((err: any) => {
        if (isCurrent) {
          setError(err.message || 'Failed to fetch order details');
          setLoading(false);
        }
      });

    return () => {
      isCurrent = false;
    };
  }, [orderId, retryCount]);

  if (orderId == null) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content order-modal-content" onClick={e => e.stopPropagation()}>
        {/* Modal Header */}
        <div className="modal-header">
          <div className="order-modal-header-title">
            <h2>{detail ? detail.displayId : `Order #${orderId}`}</h2>
            {detail && (
              <span className={`status-badge status-${detail.status.toLowerCase().replace('_', '-')}`}>
                {detail.status.replace('_', ' ')}
              </span>
            )}
          </div>
          <button className="btn btn-secondary btn-sm" aria-label="Close modal" onClick={onClose}>
            ✕
          </button>
        </div>

        {/* Modal Body */}
        <div className="modal-body" style={{ maxHeight: 'calc(85vh - 120px)', overflowY: 'auto' }}>
          {loading && (
            <div style={{ padding: '3rem', textAlign: 'center' }}>
              <div className="spinner" style={{ margin: '0 auto' }} />
              <p style={{ marginTop: '1rem', color: 'var(--text-secondary)' }}>Loading complete order details...</p>
            </div>
          )}

          {error && (
            <div className="alert alert-error" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>⚠️ {error}</span>
              <button className="btn btn-secondary btn-sm" onClick={() => setRetryCount(c => c + 1)}>
                Retry
              </button>
            </div>
          )}

          {!loading && !error && detail && (
            <div className="order-detail-container">
              {/* Attention Alert Banner */}
              {detail.needsAttention && (
                <div className="alert alert-error" style={{ marginBottom: '1.25rem' }}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                    <span style={{ fontWeight: 600 }}>⚠️ This order requires operational attention:</span>
                    <ul style={{ paddingLeft: '1.25rem', margin: 0, fontSize: '0.85rem' }}>
                      {detail.attentionReasons.map(r => (
                        <li key={r}>
                          {r === 'STOCK_RECONCILIATION_REQUIRED' && 'Manual stock reconciliation required due to un-snapshotted item cancellation.'}
                          {r === 'LOCATION_MISSING' && 'Delivery GPS coordinates are missing while order is in active fulfillment.'}
                          {r !== 'STOCK_RECONCILIATION_REQUIRED' && r !== 'LOCATION_MISSING' && r}
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}

              {/* 2-Column Info Grid: Customer & Delivery */}
              <div className="detail-info-grid">
                {/* Customer Card */}
                <div className="detail-card">
                  <h4 className="detail-card-title">👤 Customer Information</h4>
                  <div className="detail-card-row">
                    <span className="detail-label">Name:</span>
                    <span className="detail-val">{detail.customer?.name || 'Customer'}</span>
                  </div>
                  <div className="detail-card-row">
                    <span className="detail-label">WhatsApp:</span>
                    <span className="detail-val">
                      {detail.customer?.whatsappNumber ? (
                        <a
                          href={`https://wa.me/${detail.customer.whatsappNumber.replace(/\D/g, '')}`}
                          target="_blank"
                          rel="noreferrer noopener"
                          style={{ color: 'var(--primary-color)', textDecoration: 'none' }}
                        >
                          {formatPhone(detail.customer.whatsappNumber)} ↗
                        </a>
                      ) : (
                        '—'
                      )}
                    </span>
                  </div>
                  <div className="detail-card-row">
                    <span className="detail-label">Address Notes:</span>
                    <span className="detail-val">{detail.customer?.addressNotes || 'None'}</span>
                  </div>
                </div>

                {/* Delivery Card */}
                <div className="detail-card">
                  <h4 className="detail-card-title">📍 Delivery Location & Run</h4>
                  <div className="detail-card-row">
                    <span className="detail-label">Coordinates:</span>
                    <span className="detail-val">
                      {detail.delivery.hasLocation
                        ? `${detail.delivery.latitude?.toFixed(4)}, ${detail.delivery.longitude?.toFixed(4)}`
                        : '⚠️ Coordinates Missing'}
                    </span>
                  </div>
                  <div className="detail-card-row detail-card-row-map">
                    <span className="detail-label">Google Maps:</span>
                    <span className="detail-val">
                      {detail.delivery.hasLocation && detail.delivery.googleMapsUrl ? (
                        <a
                          href={detail.delivery.googleMapsUrl}
                          target="_blank"
                          rel="noreferrer noopener"
                          className="btn btn-secondary btn-sm"
                          style={{ textDecoration: 'none', display: 'inline-flex', padding: '0.2rem 0.5rem' }}
                        >
                          🗺️ Open in Google Maps ↗
                        </a>
                      ) : (
                        <span style={{ color: 'var(--text-secondary)' }}>Map link unavailable</span>
                      )}
                    </span>
                  </div>
                  {detail.deliveryRun && (
                    <>
                      <div className="detail-card-row">
                        <span className="detail-label">Delivery Run:</span>
                        <span className="detail-val">Run #{detail.deliveryRun.runId} ({detail.deliveryRun.status || 'Active'})</span>
                      </div>
                      <div className="detail-card-row">
                        <span className="detail-label">Rider Phone:</span>
                        <span className="detail-val">{formatPhone(detail.deliveryRun.riderPhoneNumber)}</span>
                      </div>
                    </>
                  )}
                </div>
              </div>

              {/* Order Items Table */}
              <div className="detail-section">
                <h4 style={{ marginBottom: '0.75rem', fontSize: '1rem' }}>🛒 Order Items & Inventory Snapshots</h4>
                <div className="table-container" style={{ maxHeight: '250px' }}>
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Product</th>
                        <th>Package</th>
                        <th style={{ textAlign: 'center' }}>Qty</th>
                        <th style={{ textAlign: 'right' }}>Locked Price</th>
                        <th style={{ textAlign: 'right' }}>Line Total</th>
                        <th>Deducted Stock Snapshot</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.items.map(item => (
                        <tr key={item.id}>
                          <td>
                            <div style={{ display: 'flex', flexDirection: 'column' }}>
                              <span style={{ fontWeight: 600 }}>{item.productName}</span>
                              <span style={{ fontSize: '0.72rem', color: 'var(--text-secondary)' }}>SKU: {item.productSku}</span>
                            </div>
                          </td>
                          <td style={{ whiteSpace: 'nowrap' }}>
                            {item.packageQuantity != null ? `${item.packageQuantity} ${item.packageUnit || ''}` : '—'}
                          </td>
                          <td style={{ textAlign: 'center', fontWeight: 600, whiteSpace: 'nowrap' }}>{item.quantity}</td>
                          <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{formatCurrency(item.lockedPrice)}</td>
                          <td style={{ textAlign: 'right', fontWeight: 600, whiteSpace: 'nowrap' }}>{formatCurrency(item.lineTotal)}</td>
                          <td style={{ whiteSpace: 'nowrap' }}>
                            {item.deductedQuantity != null ? (
                              <span style={{ fontSize: '0.85rem' }}>
                                {item.deductedQuantity} {item.deductedUnit || ''}
                              </span>
                            ) : (
                              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                                {item.stockReconciliationRequired ? '⚠️ Snapshot Missing' : 'Unconfirmed / None'}
                              </span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.75rem' }}>
                  <span style={{ fontSize: '1.1rem', fontWeight: 700 }}>
                    Total: {formatCurrency(detail.totalAmount)}
                  </span>
                </div>
              </div>

              {/* Milestone Timeline */}
              <div className="detail-section">
                <h4 style={{ marginBottom: '1rem', fontSize: '1rem' }}>⏱️ Milestone Progression</h4>
                <div className="timeline-wrapper">
                  {detail.timeline.map((event, index) => {
                    const isCompleted = event.status === 'COMPLETED';
                    const isPending = event.status === 'PENDING';
                    const isSkipped = event.status === 'SKIPPED';

                    return (
                      <div key={event.milestone || index} className={`timeline-node ${event.status.toLowerCase()}`}>
                        <div className="timeline-marker">
                          {isCompleted && '✓'}
                          {isPending && '○'}
                          {isSkipped && '✕'}
                        </div>
                        <div className="timeline-content">
                          <div className="timeline-header">
                            <span className="timeline-title">{event.title}</span>
                            {event.recorded && event.timestamp ? (
                              <span className="timeline-time">{formatTimestamp(event.timestamp)}</span>
                            ) : (
                              <span className="timeline-badge-unrecorded">
                                {isSkipped ? 'Skipped' : isPending ? 'Pending' : 'Unrecorded Time'}
                              </span>
                            )}
                          </div>
                          {event.detail && <p className="timeline-detail">{event.detail}</p>}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Modal Footer */}
        <div className="modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
