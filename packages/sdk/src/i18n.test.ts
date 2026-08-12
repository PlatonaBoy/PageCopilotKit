import { describe, expect, it } from 'vitest';
import { createTranslator, errorMessageKey } from './i18n';

describe('i18n', () => {
  it('serves Chinese strings by default', () => {
    expect(createTranslator()('send')).toBe('发送');
  });

  it('serves English when locale is en-US', () => {
    expect(createTranslator('en-US')('send')).toBe('Send');
  });

  it('falls back to the base language for regional variants', () => {
    expect(createTranslator('en-GB')('send')).toBe('Send');
  });

  it('falls back to Chinese for unsupported locales', () => {
    expect(createTranslator('fr-FR')('send')).toBe('发送');
  });

  it('maps gateway error codes to user-facing messages', () => {
    expect(errorMessageKey('unauthorized')).toBe('errorUnauthorized');
    expect(errorMessageKey('rate_limited')).toBe('errorRateLimited');
    expect(errorMessageKey('breaker_open')).toBe('errorModel');
    expect(errorMessageKey('something-new')).toBe('errorUnknown');
  });
});
