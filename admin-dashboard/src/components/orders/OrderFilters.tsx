import React, { useState, useEffect } from 'react';
import { DatePreset, OrderFilterParams, OrderStatus, SortOption } from '../../types/order';
import { resolveDatePreset } from '../../utils/orderFormatters';

interface OrderFiltersProps {
  filters: OrderFilterParams;
  onChange: (newFilters: OrderFilterParams) => void;
  datePreset: DatePreset;
  onDatePresetChange: (preset: DatePreset) => void;
  onReset: () => void;
}

const ALL_STATUSES: { label: string; value: OrderStatus }[] = [
  { label: 'Created', value: 'CREATED' },
  { label: 'Pending Warehouse', value: 'PENDING_WAREHOUSE' },
  { label: 'Packing', value: 'PACKING' },
  { label: 'Ready', value: 'READY' },
  { label: 'Dispatched', value: 'DISPATCHED' },
  { label: 'Delivered', value: 'DELIVERED' },
  { label: 'Cancelled', value: 'CANCELLED' }
];

export const OrderFilters: React.FC<OrderFiltersProps> = ({
  filters,
  onChange,
  datePreset,
  onDatePresetChange,
  onReset
}) => {
  // Local search text for debounce
  const [searchInput, setSearchInput] = useState<string>(filters.search || '');
  const [customStart, setCustomStart] = useState<string>(filters.startDate || '');
  const [customEnd, setCustomEnd] = useState<string>(filters.endDate || '');
  const [dateError, setDateError] = useState<string | null>(null);

  // Debounce search input by 350ms
  useEffect(() => {
    const timer = setTimeout(() => {
      if (searchInput !== (filters.search || '')) {
        onChange({ ...filters, page: 0, search: searchInput });
      }
    }, 350);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Sync internal search input if reset externally
  useEffect(() => {
    setSearchInput(filters.search || '');
  }, [filters.search]);

  // Sync internal custom dates when filters change (preset select or reset)
  useEffect(() => {
    setCustomStart(filters.startDate || '');
    setCustomEnd(filters.endDate || '');
  }, [filters.startDate, filters.endDate]);

  // Handle Date Preset change
  const handlePresetSelect = (preset: DatePreset) => {
    onDatePresetChange(preset);
    setDateError(null);
    if (preset !== 'CUSTOM') {
      const { startDate, endDate } = resolveDatePreset(preset);
      onChange({ ...filters, page: 0, startDate, endDate });
    }
  };

  // Apply custom dates with validation
  const handleApplyCustomDates = () => {
    if (!customStart || !customEnd) {
      setDateError('Both start and end dates are required.');
      return;
    }
    if (customStart > customEnd) {
      setDateError('Start date cannot be after end date.');
      return;
    }
    const start = new Date(customStart);
    const end = new Date(customEnd);
    const diffDays = Math.round((end.getTime() - start.getTime()) / (1000 * 3600 * 24));
    if (diffDays > 90) {
      setDateError('Date range cannot exceed 90 calendar days.');
      return;
    }
    setDateError(null);
    onChange({ ...filters, page: 0, startDate: customStart, endDate: customEnd });
  };

  // Toggle status selection
  const handleToggleStatus = (status: OrderStatus) => {
    const current = filters.status || [];
    let updated: OrderStatus[];
    if (current.includes(status)) {
      updated = current.filter(s => s !== status);
    } else {
      updated = [...current, status];
    }
    onChange({ ...filters, page: 0, status: updated.length > 0 ? updated : undefined });
  };

  // Check if any filter is active
  const hasActiveFilters =
    Boolean(filters.search) ||
    Boolean(filters.status && filters.status.length > 0) ||
    datePreset !== 'TODAY' ||
    filters.hasLocation !== undefined ||
    filters.needsAttention !== undefined ||
    (filters.sort && filters.sort !== 'createdAt,desc');

  return (
    <div className="order-filters-card glass">
      {/* Top Row: Search and Sort */}
      <div className="filters-top-row">
        <div className="search-box-container">
          <span className="search-icon" aria-hidden="true">🔍</span>
          <input
            type="text"
            className="input-field search-input"
            aria-label="Search orders by ID, Customer Name, or Phone"
            placeholder="Search by Order ID, Display ID, Customer Name, or Phone..."
            value={searchInput}
            onChange={e => setSearchInput(e.target.value)}
          />
          {searchInput && (
            <button
              type="button"
              className="clear-search-btn"
              aria-label="Clear search"
              onClick={() => setSearchInput('')}
            >
              ✕
            </button>
          )}
        </div>

        <div className="sort-and-reset-group">
          <div className="sort-selector-container">
            <label className="filter-label" htmlFor="order-sort-select">Sort By:</label>
            <select
              id="order-sort-select"
              className="input-field select-field"
              aria-label="Sort orders"
              value={filters.sort || 'createdAt,desc'}
              onChange={e => onChange({ ...filters, page: 0, sort: e.target.value as SortOption })}
            >
              <option value="createdAt,desc">Newest First</option>
              <option value="createdAt,asc">Oldest First</option>
              <option value="totalAmount,desc">Total: High to Low</option>
              <option value="totalAmount,asc">Total: Low to High</option>
            </select>
          </div>

          {hasActiveFilters && (
            <button
              type="button"
              className="btn btn-secondary btn-sm reset-filters-btn"
              onClick={onReset}
            >
              Reset Filters
            </button>
          )}
        </div>
      </div>

      {/* Middle Row: Status Pills */}
      <div className="status-pills-row" role="group" aria-label="Filter by order status">
        <span className="filter-label">Status:</span>
        <button
          type="button"
          className={`filter-pill ${(!filters.status || filters.status.length === 0) ? 'active' : ''}`}
          onClick={() => onChange({ ...filters, page: 0, status: undefined })}
        >
          All
        </button>
        {ALL_STATUSES.map(s => {
          const isSelected = filters.status?.includes(s.value);
          return (
            <button
              key={s.value}
              type="button"
              className={`filter-pill ${isSelected ? 'active' : ''}`}
              onClick={() => handleToggleStatus(s.value)}
            >
              {s.label}
            </button>
          );
        })}
      </div>

      {/* Bottom Row: Date Presets & Quick Dropdowns */}
      <div className="filters-bottom-row">
        <div className="date-presets-group" role="group" aria-label="Filter by date range">
          <span className="filter-label">Date Range:</span>
          {(['TODAY', 'YESTERDAY', 'LAST_7_DAYS', 'LAST_30_DAYS', 'ALL_TIME', 'CUSTOM'] as DatePreset[]).map(p => (
            <button
              key={p}
              type="button"
              className={`preset-btn ${datePreset === p ? 'active' : ''}`}
              onClick={() => handlePresetSelect(p)}
            >
              {p === 'TODAY' && 'Today'}
              {p === 'YESTERDAY' && 'Yesterday'}
              {p === 'LAST_7_DAYS' && 'Last 7 Days'}
              {p === 'LAST_30_DAYS' && 'Last 30 Days'}
              {p === 'ALL_TIME' && 'All Time'}
              {p === 'CUSTOM' && 'Custom'}
            </button>
          ))}
        </div>

        <div className="dropdowns-group">
          {/* Location Filter */}
          <div className="dropdown-item">
            <label className="filter-label" htmlFor="order-location-select">Location:</label>
            <select
              id="order-location-select"
              className="input-field select-field sm"
              aria-label="Filter by delivery location status"
              value={filters.hasLocation === undefined ? 'ANY' : filters.hasLocation.toString()}
              onChange={e => {
                const val = e.target.value;
                onChange({
                  ...filters,
                  page: 0,
                  hasLocation: val === 'ANY' ? undefined : val === 'true'
                });
              }}
            >
              <option value="ANY">Any Location</option>
              <option value="true">📍 Has Location</option>
              <option value="false">⚠️ Missing Location</option>
            </select>
          </div>

          {/* Attention Filter */}
          <div className="dropdown-item">
            <label className="filter-label" htmlFor="order-attention-select">Attention:</label>
            <select
              id="order-attention-select"
              className="input-field select-field sm"
              aria-label="Filter by operational attention status"
              value={filters.needsAttention === undefined ? 'ANY' : filters.needsAttention.toString()}
              onChange={e => {
                const val = e.target.value;
                onChange({
                  ...filters,
                  page: 0,
                  needsAttention: val === 'ANY' ? undefined : val === 'true'
                });
              }}
            >
              <option value="ANY">All Orders</option>
              <option value="true">⚠️ Needs Attention</option>
              <option value="false">✅ Normal</option>
            </select>
          </div>
        </div>
      </div>

      {/* Custom Date Range Picker (shown when preset === 'CUSTOM') */}
      {datePreset === 'CUSTOM' && (
        <div className="custom-date-row">
          <div className="custom-date-inputs">
            <div className="custom-date-field">
              <label className="input-label" htmlFor="custom-start-date">Start Date:</label>
              <input
                id="custom-start-date"
                type="date"
                className="input-field"
                aria-label="Start date"
                value={customStart}
                onChange={e => setCustomStart(e.target.value)}
              />
            </div>
            <div className="custom-date-field">
              <label className="input-label" htmlFor="custom-end-date">End Date:</label>
              <input
                id="custom-end-date"
                type="date"
                className="input-field"
                aria-label="End date"
                value={customEnd}
                onChange={e => setCustomEnd(e.target.value)}
              />
            </div>
            <button
              type="button"
              className="btn btn-primary btn-sm apply-dates-btn"
              onClick={handleApplyCustomDates}
            >
              Apply Dates
            </button>
          </div>
          {dateError && <p className="date-error-text" role="alert">{dateError}</p>}
        </div>
      )}
    </div>
  );
};
