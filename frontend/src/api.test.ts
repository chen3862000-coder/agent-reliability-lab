import { describe, expect, it } from 'vitest';
import {
  AgentResponse,
  confirmationCommitted,
  confirmationFrom,
  newIdempotencyKey,
  shortId
} from './api';

describe('confirmationFrom', () => {
  it('extracts reservation confirmation data from structured tool results', () => {
    const response: AgentResponse = {
      status: 'SUCCESS',
      message: 'Reservation requires confirmation before stock changes.',
      errorCode: null,
      traceId: 'trace-1',
      provider: 'deepseek',
      model: 'deepseek-flash',
      toolCalls: ['reserve_inventory'],
      results: [
        {
          tool: 'reserve_inventory',
          message: 'Reservation requires confirmation before stock changes.',
          errorCode: null,
          data: {
            confirmationId: 'confirm-123',
            sku: 'SKU-1001',
            quantity: 3,
            expiresAt: '2026-10-19T00:00:00Z'
          }
        }
      ]
    };

    expect(confirmationFrom(response)).toEqual({
      confirmationId: 'confirm-123',
      sku: 'SKU-1001',
      quantity: 3,
      expiresAt: '2026-10-19T00:00:00Z'
    });
  });

  it('ignores failed reservation results', () => {
    expect(
      confirmationFrom({
        status: 'ERROR',
        message: 'INSUFFICIENT INVENTORY',
        errorCode: 'INSUFFICIENT_INVENTORY',
        traceId: 'trace-2',
        provider: 'deepseek',
        model: 'deepseek-flash',
        toolCalls: ['reserve_inventory'],
        results: [
          {
            tool: 'reserve_inventory',
            message: 'INSUFFICIENT INVENTORY',
            data: null,
            errorCode: 'INSUFFICIENT_INVENTORY'
          }
        ]
      })
    ).toBeNull();
  });
});

describe('newIdempotencyKey', () => {
  it('includes a stable confirmation prefix while remaining client generated', () => {
    const key = newIdempotencyKey('abcdef12-3456');
    expect(key.startsWith('frontend-abcdef12-')).toBe(true);
    expect(key.length).toBeGreaterThan(20);
  });
});

describe('confirmationCommitted', () => {
  it('only treats null errorCode commits as applied writes', () => {
    expect(
      confirmationCommitted({
        status: 'SUCCESS',
        message: 'Reserved 3 units of SKU-1001.',
        errorCode: null,
        confirmationId: 'confirm-123',
        idempotencyKey: 'idem-12345',
        sku: 'SKU-1001',
        quantity: 3,
        remaining: 17
      })
    ).toBe(true);
    expect(
      confirmationCommitted({
        status: 'ERROR',
        message: 'IDEMPOTENCY KEY REUSED',
        errorCode: 'IDEMPOTENCY_KEY_REUSED',
        confirmationId: 'confirm-123',
        idempotencyKey: 'idem-12345',
        sku: null,
        quantity: 0,
        remaining: null
      })
    ).toBe(false);
  });
});

describe('shortId', () => {
  it('shortens long IDs for display', () => {
    expect(shortId('1234567890abcdefxyz')).toBe('12345678...defxyz');
    expect(shortId('short')).toBe('short');
    expect(shortId(null)).toBe('n/a');
  });
});
