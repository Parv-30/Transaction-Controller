import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { apiFetch, ApiError } from './client';

describe('apiFetch', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('attaches the Authorization header with the current access token', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    );

    await apiFetch('/accounts', {}, 'token-abc');

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/accounts'),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer token-abc' }),
      }),
    );
  });

  it('parses the backend error body shape and throws ApiError with the message and status', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(JSON.stringify({ error: 'account not found' }), { status: 404 }),
    );

    await expect(apiFetch('/accounts/missing', {}, 'token-abc')).rejects.toMatchObject({
      status: 404,
      message: 'account not found',
    });
  });

  it('throws an ApiError with status 401 on an unauthorized response', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(JSON.stringify({ error: 'unauthorized' }), { status: 401 }),
    );

    await expect(apiFetch('/accounts', {}, 'token-abc')).rejects.toBeInstanceOf(ApiError);
    await expect(apiFetch('/accounts', {}, 'token-abc')).rejects.toMatchObject({ status: 401 });
  });

  it('returns the parsed JSON body on success', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(JSON.stringify({ accountRef: 'alice-usd' }), { status: 200 }),
    );

    const result = await apiFetch<{ accountRef: string }>('/accounts/alice-usd', {}, 'token-abc');

    expect(result.accountRef).toBe('alice-usd');
  });
});
