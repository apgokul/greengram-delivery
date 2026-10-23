import React from 'react';
import { OrderSummaryCounts } from '../../types/order';

interface OrderSummaryCardsProps {
  counts: OrderSummaryCounts | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}

export const OrderSummaryCards: React.FC<OrderSummaryCardsProps> = ({
  counts,
  loading,
  error,
  onRetry
}) => {
  if (error) {
    return (
      <div className="alert alert-error" style={{ marginBottom: '1.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>⚠️ Failed to load store summary metrics: {error}</span>
        <button className="btn btn-secondary btn-sm" onClick={onRetry}>
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="summary-cards-grid">
      {/* 1. Created Today */}
      <div className="summary-card card-created">
        <div className="summary-card-header">
          <span className="summary-card-title">Created Today</span>
          <span className="summary-card-icon">📦</span>
        </div>
        <div className="summary-card-value">
          {loading ? <span className="skeleton-text" /> : (counts?.createdToday ?? 0)}
        </div>
        <span className="summary-card-sub">All new customer orders today</span>
      </div>

      {/* 2. In Progress */}
      <div className="summary-card card-progress">
        <div className="summary-card-header">
          <span className="summary-card-title">In Progress</span>
          <span className="summary-card-icon">⚡</span>
        </div>
        <div className="summary-card-value">
          {loading ? <span className="skeleton-text" /> : (counts?.inProgress ?? 0)}
        </div>
        <span className="summary-card-sub">Warehouse & gig rider delivery</span>
      </div>

      {/* 3. Delivered Today */}
      <div className="summary-card card-delivered">
        <div className="summary-card-header">
          <span className="summary-card-title">Delivered Today</span>
          <span className="summary-card-icon">✅</span>
        </div>
        <div className="summary-card-value">
          {loading ? <span className="skeleton-text" /> : (counts?.deliveredToday ?? 0)}
        </div>
        <span className="summary-card-sub">Completed customer handoffs</span>
      </div>

      {/* 4. Needs Attention */}
      <div className="summary-card card-attention">
        <div className="summary-card-header">
          <span className="summary-card-title">Needs Attention</span>
          <span className="summary-card-icon">⚠️</span>
        </div>
        <div className="summary-card-value" style={{ color: (counts?.needsAttention ?? 0) > 0 ? '#f87171' : 'inherit' }}>
          {loading ? <span className="skeleton-text" /> : (counts?.needsAttention ?? 0)}
        </div>
        <span className="summary-card-sub">Missing location or stock mismatch</span>
      </div>
    </div>
  );
};
