import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface MarkdownRendererProps {
  content: string;
}

/**
 * Renders AI answers as Markdown. Raw HTML is never parsed (no rehype-raw), unsafe URL schemes are dropped by
 * react-markdown's default URL transform, and images are not rendered: an injected answer could otherwise make
 * the browser fetch an attacker URL carrying project data.
 */
const DISALLOWED_ELEMENTS = ['img', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'button'];

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  return (
    <div className="min-w-0 break-words text-sm leading-6 text-slate-800">
      <Markdown
        remarkPlugins={[remarkGfm]}
        disallowedElements={DISALLOWED_ELEMENTS}
        unwrapDisallowed
        components={{
          a: ({ href, children }) => (
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer nofollow"
              className="font-medium text-indigo-600 underline-offset-2 hover:underline"
            >
              {children}
            </a>
          ),
          code: ({ className, children }) => {
            const block = /language-(\w+)/.test(className ?? '') || String(children).includes('\n');
            return block ? (
              <code className="my-2 block overflow-x-auto rounded-lg bg-slate-900 p-3 font-mono text-xs text-slate-100">
                {children}
              </code>
            ) : (
              <code className="rounded bg-slate-100 px-1 py-0.5 font-mono text-xs text-indigo-700">{children}</code>
            );
          },
          pre: ({ children }) => <pre className="my-0">{children}</pre>,
          ul: ({ children }) => <ul className="my-1.5 list-disc space-y-1 pl-4">{children}</ul>,
          ol: ({ children }) => <ol className="my-1.5 list-decimal space-y-1 pl-4">{children}</ol>,
          p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
          h1: ({ children }) => <h3 className="my-2 text-sm font-bold text-slate-950">{children}</h3>,
          h2: ({ children }) => <h3 className="my-2 text-sm font-bold text-slate-950">{children}</h3>,
          h3: ({ children }) => <h4 className="my-1.5 text-sm font-semibold text-slate-950">{children}</h4>,
          blockquote: ({ children }) => (
            <blockquote className="my-2 border-l-4 border-slate-200 pl-3 text-slate-600">{children}</blockquote>
          ),
          table: ({ children }) => (
            <div className="my-2 overflow-x-auto">
              <table className="min-w-full border-collapse text-xs">{children}</table>
            </div>
          ),
          th: ({ children }) => <th className="border border-slate-200 bg-slate-50 px-2 py-1 text-left font-semibold">{children}</th>,
          td: ({ children }) => <td className="border border-slate-200 px-2 py-1">{children}</td>,
        }}
      >
        {content}
      </Markdown>
    </div>
  );
}
