import type { DatePreset } from '../types/order';

/**
 * Returns a date string formatted as YYYY-MM-DD in the Asia/Kolkata timezone.
 */
export function getKolkataDateString(date: Date = new Date()): string {
  try {
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Kolkata',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).format(date);
  } catch {
    // Fallback if Intl timeZone is unsupported
    return date.toISOString().split('T')[0];
  }
}

/**
 * Returns the date string N calendar days ago in Asia/Kolkata.
 */
export function getDaysAgoKolkata(days: number): string {
  const todayStr = getKolkataDateString();
  const [year, month, day] = todayStr.split('-').map(Number);
  const d = new Date(Date.UTC(year, month - 1, day - days, 12, 0, 0));
  return d.toISOString().split('T')[0];
}

/**
 * Calculates start and end date strings for a given DatePreset.
 */
export function resolveDatePreset(
  preset: DatePreset,
  customStart?: string,
  customEnd?: string
): { startDate?: string; endDate?: string } {
  const today = getKolkataDateString();
  switch (preset) {
    case 'TODAY':
      return { startDate: today, endDate: today };
    case 'YESTERDAY': {
      const yesterday = getDaysAgoKolkata(1);
      return { startDate: yesterday, endDate: yesterday };
    }
    case 'LAST_7_DAYS':
      return { startDate: getDaysAgoKolkata(6), endDate: today };
    case 'LAST_30_DAYS':
      return { startDate: getDaysAgoKolkata(29), endDate: today };
    case 'ALL_TIME':
      // Passing only endDate allows backend to apply < startOfTomorrow with no start boundary
      return { endDate: today };
    case 'CUSTOM':
      return { startDate: customStart || undefined, endDate: customEnd || undefined };
    default:
      return { startDate: today, endDate: today };
  }
}

/**
 * Formats a monetary number in Indian Rupees (INR).
 */
export function formatCurrency(amount: number | null | undefined): string {
  if (amount == null || isNaN(amount)) return '₹0.00';
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(amount);
}

/**
 * Formats an ISO timestamp into a readable date & time string in Asia/Kolkata.
 * Treats timezone-less ISO strings (such as Java LocalDateTime) as UTC wall-clock values.
 */
export function formatTimestamp(isoString: string | null | undefined): string {
  if (!isoString) return '—';
  try {
    let s = isoString.trim();
    if (!s) return '—';

    // If string has a time component (T or space) but no timezone offset (Z or +/-HH:mm),
    // treat it as UTC per backend storage contract
    if (/(?:T|\s)\d{2}:\d{2}/.test(s) && !/(?:Z|[+-]\d{2}(?::?\d{2})?)$/i.test(s)) {
      s = s.replace(' ', 'T') + 'Z';
    }

    // Parse ISO string
    const d = new Date(s);
    if (isNaN(d.getTime())) {
      // Fallback: direct string split
      const parts = isoString.split('T');
      if (parts.length === 2) {
        return `${parts[0]} ${parts[1].substring(0, 5)}`;
      }
      return isoString;
    }
    return new Intl.DateTimeFormat('en-IN', {
      timeZone: 'Asia/Kolkata',
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: true
    }).format(d);
  } catch {
    return isoString;
  }
}

/**
 * Formats a phone number cleanly for display.
 */
export function formatPhone(phone: string | null | undefined): string {
  if (!phone) return '—';
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('91')) {
    return `+91 ${digits.substring(2, 7)} ${digits.substring(7)}`;
  }
  if (digits.length === 10) {
    return `+91 ${digits.substring(0, 5)} ${digits.substring(5)}`;
  }
  return phone;
}
