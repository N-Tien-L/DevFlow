import { describe, expect, it } from 'vitest';
import type { ChatFrame } from '../../types/chat';
import { applyFrame, chatErrorMessage, createPendingStream, isRetryable, parseChatFrame } from './chatStream';

const frame = (patch: Partial<ChatFrame>): ChatFrame => ({
  conversationId: 'conv-1',
  seq: 0,
  type: 'CHUNK',
  content: '',
  metadata: null,
  ...patch,
});

describe('chatStream', () => {
  it('parses valid frames and rejects malformed ones', () => {
    expect(parseChatFrame('{"conversationId":"c","seq":2,"type":"CHUNK","content":"hi","metadata":null}')).toEqual({
      conversationId: 'c',
      seq: 2,
      type: 'CHUNK',
      content: 'hi',
      metadata: null,
    });
    expect(parseChatFrame('not json')).toBeNull();
    expect(parseChatFrame('{"conversationId":"c","seq":"1","type":"CHUNK"}')).toBeNull();
    expect(parseChatFrame('{"conversationId":"c","seq":1,"type":"EVIL"}')).toBeNull();
  });

  it('reassembles chunks by seq, even when END overtakes the last chunk', () => {
    const stream = createPendingStream('conv-1', 'msg-1', 'q');
    expect(applyFrame(stream, frame({ seq: 1, content: 'world' }))).toEqual({ kind: 'ignore' });
    expect(applyFrame(stream, frame({ seq: 0, content: 'hello ' }))).toEqual({ kind: 'text', text: 'hello world', done: false });
    expect(applyFrame(stream, frame({ type: 'END', seq: 3 }))).toEqual({ kind: 'ignore' });
    expect(applyFrame(stream, frame({ seq: 2, content: '!' }))).toEqual({ kind: 'text', text: '!', done: true });
  });

  it('drops duplicates and frames of other conversations', () => {
    const stream = createPendingStream('conv-1', 'msg-1', 'q');
    expect(applyFrame(stream, frame({ conversationId: 'other-tab', content: 'leak' }))).toEqual({ kind: 'ignore' });
    expect(applyFrame(null, frame({ content: 'late' }))).toEqual({ kind: 'ignore' });
    applyFrame(stream, frame({ seq: 0, content: 'a' }));
    expect(applyFrame(stream, frame({ seq: 0, content: 'a' }))).toEqual({ kind: 'ignore' });
  });

  it('maps proposals, cancel acks and errors', () => {
    const stream = createPendingStream('conv-1', 'msg-1', 'q');
    const proposal = applyFrame(
      stream,
      frame({
        type: 'TASK_PROPOSAL',
        content: 'Gợi ý',
        metadata: { proposals: [{ title: 'Build API', description: 'd', checklist: ['x'] }, { title: ' ', description: '' }] },
      }),
    );
    expect(proposal).toEqual({
      kind: 'proposal',
      content: 'Gợi ý',
      proposals: [{ title: 'Build API', description: 'd', checklist: ['x'] }],
    });
    expect(applyFrame(stream, frame({ type: 'END', seq: 1 }))).toEqual({ kind: 'text', text: '', done: true });
    expect(applyFrame(stream, frame({ type: 'END', seq: -1 }))).toEqual({ kind: 'cancelled' });
    expect(applyFrame(stream, frame({ type: 'ERROR', seq: -1, content: 'Too many', metadata: { code: 'AI_RATE_LIMITED' } }))).toEqual({
      kind: 'error',
      code: 'AI_RATE_LIMITED',
      message: 'Too many',
    });
  });

  it('localises known error codes and decides whether a retry makes sense', () => {
    expect(chatErrorMessage('WORKSPACE_ACCESS_DENIED', 'You do not have access')).toBe('Bạn không có quyền truy cập workspace này.');
    expect(chatErrorMessage('SOMETHING_NEW', 'Server says no')).toBe('Server says no');
    expect(isRetryable('WORKSPACE_ACCESS_DENIED')).toBe(false);
    expect(isRetryable('AI_CHAT_BUSY')).toBe(true);
    expect(isRetryable(undefined)).toBe(true);
  });
});
