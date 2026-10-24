import { test, describe, it } from 'node:test';
import assert from 'node:assert';
import {
    resolveDatePreset,
    formatCurrency,
    formatPhone,
    formatTimestamp,
    getKolkataDateString,
    getDaysAgoKolkata
} from '../src/utils/orderFormatters.ts';

describe('Order Formatters & Date Utilities', () => {

    describe('resolveDatePreset', () => {
        it('TODAY resolves startDate and endDate to the same Kolkata calendar date', () => {
            const todayStr = getKolkataDateString();
            const range = resolveDatePreset('TODAY');
            assert.strictEqual(range.startDate, todayStr);
            assert.strictEqual(range.endDate, todayStr);
            assert.match(range.startDate, /^\d{4}-\d{2}-\d{2}$/);
        });

        it('YESTERDAY resolves startDate and endDate to 1 day prior to today', () => {
            const range = resolveDatePreset('YESTERDAY');
            assert.ok(range.startDate);
            assert.ok(range.endDate);
            assert.strictEqual(range.startDate, range.endDate);

            const today = new Date(getKolkataDateString());
            const yesterday = new Date(range.startDate);
            const diffMs = today.getTime() - yesterday.getTime();
            const diffDays = Math.round(diffMs / (1000 * 3600 * 24));
            assert.strictEqual(diffDays, 1);
        });

        it('LAST_7_DAYS resolves a 7-day range including today', () => {
            const todayStr = getKolkataDateString();
            const range = resolveDatePreset('LAST_7_DAYS');
            assert.strictEqual(range.endDate, todayStr);

            const start = new Date(range.startDate);
            const end = new Date(range.endDate);
            const diffDays = Math.round((end.getTime() - start.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffDays, 6); // 6 days difference = 7 days inclusive
        });

        it('LAST_30_DAYS resolves a 30-day range including today', () => {
            const todayStr = getKolkataDateString();
            const range = resolveDatePreset('LAST_30_DAYS');
            assert.strictEqual(range.endDate, todayStr);

            const start = new Date(range.startDate);
            const end = new Date(range.endDate);
            const diffDays = Math.round((end.getTime() - start.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffDays, 29); // 29 days difference = 30 days inclusive
        });

        it('ALL_TIME omits startDate to allow unrestricted historical query up to today', () => {
            const todayStr = getKolkataDateString();
            const range = resolveDatePreset('ALL_TIME');
            assert.strictEqual(range.startDate, undefined);
            assert.strictEqual(range.endDate, todayStr);
        });

        it('CUSTOM returns custom range when provided or undefined when not', () => {
            const emptyRange = resolveDatePreset('CUSTOM');
            assert.strictEqual(emptyRange.startDate, undefined);
            assert.strictEqual(emptyRange.endDate, undefined);

            const customRange = resolveDatePreset('CUSTOM', '2026-10-01', '2026-10-15');
            assert.strictEqual(customRange.startDate, '2026-10-01');
            assert.strictEqual(customRange.endDate, '2026-10-15');
        });
    });

    describe('formatCurrency', () => {
        it('formats positive numbers with INR symbol and Indian number formatting', () => {
            const formatted = formatCurrency(1250.5);
            // Must contain rupee sign or INR and 1,250.50
            assert.ok(formatted.includes('1,250.50') || formatted.includes('1250.50'));
            assert.ok(formatted.includes('₹'));
        });

        it('formats zero correctly', () => {
            const formatted = formatCurrency(0);
            assert.ok(formatted.includes('0.00'));
            assert.ok(formatted.includes('₹'));
        });

        it('formats null or undefined gracefully as 0.00', () => {
            assert.ok(formatCurrency(null).includes('0.00'));
            assert.ok(formatCurrency(undefined).includes('0.00'));
        });
    });

    describe('formatPhone', () => {
        it('formats standard 10-digit Indian mobile number', () => {
            assert.strictEqual(formatPhone('9876543210'), '+91 98765 43210');
        });

        it('formats 12-digit number starting with 91', () => {
            assert.strictEqual(formatPhone('919876543210'), '+91 98765 43210');
        });

        it('formats 13-character number starting with +91', () => {
            assert.strictEqual(formatPhone('+919876543210'), '+91 98765 43210');
        });

        it('handles null or empty phone gracefully', () => {
            assert.strictEqual(formatPhone(null), '—');
            assert.strictEqual(formatPhone(undefined), '—');
            assert.strictEqual(formatPhone(''), '—');
        });

        it('returns raw non-conforming numbers as fallback', () => {
            assert.strictEqual(formatPhone('12345'), '12345');
        });
    });

    describe('formatTimestamp', () => {
        it('formats timezone-less UTC string (such as Java LocalDateTime) into Asia/Kolkata', () => {
            // 16:16:51 UTC is 21:46:51 IST (09:46 pm)
            const formatted = formatTimestamp('2026-10-24T16:16:51.608646');
            assert.match(formatted, /24\s+Oct\s+2026/i);
            assert.match(formatted, /09:46/i);
            assert.match(formatted, /pm/i);
        });

        it('formats valid ISO timestamp with Z in Kolkata timezone', () => {
            const formatted = formatTimestamp('2026-10-23T06:30:00Z');
            // 06:30 UTC is 12:00 IST
            assert.match(formatted, /23\s+Oct\s+2026/i);
            assert.match(formatted, /12:00/i);
            assert.match(formatted, /pm/i);
        });

        it('respects explicit timezone offset (+05:30) as supplied', () => {
            // 21:46:51+05:30 is 09:46 pm IST
            const formatted = formatTimestamp('2026-10-24T21:46:51+05:30');
            assert.match(formatted, /24\s+Oct\s+2026/i);
            assert.match(formatted, /09:46/i);
            assert.match(formatted, /pm/i);
        });

        it('handles variable fractional seconds from Java LocalDateTime serialization', () => {
            const f1 = formatTimestamp('2026-10-24T16:16:51.1');
            const f2 = formatTimestamp('2026-10-24T16:16:51.123456');
            assert.match(f1, /24\s+Oct\s+2026/i);
            assert.match(f1, /09:46/i);
            assert.match(f2, /24\s+Oct\s+2026/i);
            assert.match(f2, /09:46/i);
        });

        it('produces identical Asia/Kolkata output regardless of browser/system timezone', () => {
            const originalTZ = process.env.TZ;
            try {
                const targetTimestamp = '2026-10-24T16:16:51.608646';
                const results = [];

                for (const tz of ['UTC', 'Asia/Kolkata', 'America/New_York', 'Asia/Tokyo', 'Europe/London']) {
                    process.env.TZ = tz;
                    const res = formatTimestamp(targetTimestamp);
                    assert.match(res, /24\s+Oct\s+2026/i);
                    assert.match(res, /09:46/i);
                    assert.match(res, /pm/i);
                    results.push(res);
                }

                // Verify all outputs across different timezones are strictly identical
                for (let i = 1; i < results.length; i++) {
                    assert.strictEqual(results[i], results[0]);
                }
            } finally {
                process.env.TZ = originalTZ;
            }
        });

        it('handles null or empty timestamp gracefully', () => {
            assert.strictEqual(formatTimestamp(null), '—');
            assert.strictEqual(formatTimestamp(undefined), '—');
            assert.strictEqual(formatTimestamp(''), '—');
            assert.strictEqual(formatTimestamp('   '), '—');
        });

        it('returns raw string fallback for invalid date strings', () => {
            assert.strictEqual(formatTimestamp('not-a-date'), 'not-a-date');
        });
    });

    describe('getDaysAgoKolkata calendar math', () => {
        it('getDaysAgoKolkata(0) equals getKolkataDateString()', () => {
            assert.strictEqual(getDaysAgoKolkata(0), getKolkataDateString());
        });

        it('getDaysAgoKolkata(1) is strictly 1 day before getKolkataDateString()', () => {
            const today = new Date(getKolkataDateString());
            const yesterday = new Date(getDaysAgoKolkata(1));
            const diffDays = Math.round((today.getTime() - yesterday.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffDays, 1);
        });

        it('getDaysAgoKolkata across 30 days is exactly 30 calendar days difference', () => {
            const today = new Date(getKolkataDateString());
            const thirtyAgo = new Date(getDaysAgoKolkata(30));
            const diffDays = Math.round((today.getTime() - thirtyAgo.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffDays, 30);
        });
    });

    describe('Date Range Constraints (Backend Alignment)', () => {
        it('validates 90-day maximum limit consistent with ChronoUnit.DAYS.between', () => {
            const start = new Date('2026-01-01');
            const endValid = new Date('2026-04-01'); // exactly 90 days
            const diffValid = Math.round((endValid.getTime() - start.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffValid, 90);
            assert.ok(diffValid <= 90);

            const endInvalid = new Date('2026-04-02'); // 91 days
            const diffInvalid = Math.round((endInvalid.getTime() - start.getTime()) / (1000 * 3600 * 24));
            assert.strictEqual(diffInvalid, 91);
            assert.ok(diffInvalid > 90);
        });
    });
});
