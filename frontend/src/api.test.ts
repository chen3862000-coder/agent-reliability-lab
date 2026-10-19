import { describe, expect, it } from 'vitest';
import { AgentResponse, confirmationFrom, newIdempotencyKey } from './api';

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
