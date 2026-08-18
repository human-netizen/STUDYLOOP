import { Fragment, useMemo, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import type { Citation } from '../lib/types'
import { rehypeHighlight } from '../lib/highlight'
import { cx } from '../lib/style'
import 'katex/dist/katex.min.css'

// Everything the model writes — chat answers, document summaries, quiz explanations — arrives as
// Markdown, because that is what an instruction-tuned model emits whether or not you ask for it.
// Until this component existed the app rendered it verbatim, so a well-structured answer reached
// the reader as a wall of asterisks, pipes and backticks, and a formula reached them as LaTeX.
//
// Deliberately no rehype-raw. Raw HTML in the tree would mean the model can emit a <script> or an
// onerror handler, and the model's output is partly the user's input — anything typed into chat
// can end up quoted back inside an answer. Nothing in a course answer needs raw HTML, so the fix
// is to never enable it rather than to enable it and then sanitize.

// Sanitize runs first, before the plugins that generate markup. The trees KaTeX and highlight.js
// produce are ours and are trusted; the tree parsed out of model text is not. The two additions to
// the default schema are the class names remark-math leaves behind for rehype-katex to find —
// stripped by the default schema, which would silently turn every formula back into plain text.
const mathClasses = ['className', 'math', 'math-inline', 'math-display', 'language-math']
const schema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    span: [...(defaultSchema.attributes?.span ?? []), mathClasses],
    div: [...(defaultSchema.attributes?.div ?? []), mathClasses],
  },
}

const remarkPlugins = [remarkGfm, remarkMath]
const rehypePlugins = [[rehypeSanitize, schema], rehypeKatex, rehypeHighlight] as never

interface MarkdownProps {
  text: string
  // Chat only: the sources behind this answer's [n] markers. Given them, the markers render as
  // buttons that open the cited page instead of as literal text.
  citations?: Citation[]
  onCite?: (citation: Citation) => void
  // While an answer streams, the tail of the text is a half-written block. See BlockPrefix.
  streaming?: boolean
  className?: string
}

export function Markdown({ text, citations, onCite, streaming = false, className }: MarkdownProps) {
  const components = useMemo(
    () => buildComponents(citations ?? [], onCite),
    [citations, onCite],
  )

  // The completed blocks are the only part worth parsing: the trailing partial block changes on
  // every SSE token, and re-parsing the whole accumulated answer each time is O(n²) over the
  // length of the answer. Memoized on the prefix, so a token that only extends the tail re-renders
  // one <span> instead of the document.
  const [settled, pending] = streaming ? splitAtLastBlockBreak(text) : [text, '']
  const body = useMemo(
    () => (
      <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins} components={components}>
        {settled}
      </ReactMarkdown>
    ),
    [settled, components],
  )

  return (
    <div className={cx('md', className)}>
      {body}
      {/* The tail is held as plain text until its block closes. A half-typed table renders as
          pipes for a few hundred milliseconds, which is honest; parsing it renders it as a
          broken table that reshapes itself on every token. The caret rides the end of it, so
          "still writing" sits where the writing is happening. */}
      {streaming ? (
        <p className="my-2 whitespace-pre-wrap first:mt-0 last:mb-0">
          {pending}
          <span className="ml-0.5 inline-block animate-pulse text-accent">▋</span>
        </p>
      ) : (
        pending && <p className="my-2 whitespace-pre-wrap first:mt-0 last:mb-0">{pending}</p>
      )}
    </div>
  )
}

// Splits off the trailing incomplete block. Normally that is everything after the last blank line;
// inside an unclosed code fence it is everything from the opening fence, since a fence swallows the
// blank lines within it and half a fence parses as a paragraph of code.
function splitAtLastBlockBreak(text: string): [string, string] {
  const fences = text.match(/^```/gm)
  if (fences && fences.length % 2 === 1) {
    const open = text.lastIndexOf('\n```')
    return open === -1 ? ['', text] : [text.slice(0, open), text.slice(open + 1)]
  }
  const brk = text.lastIndexOf('\n\n')
  return brk === -1 ? ['', text] : [text.slice(0, brk), text.slice(brk + 2)]
}

// Citations stay React components rather than becoming text in a paragraph.
//
// This is the part of chat that is not the model's word against itself: [n] resolves server-side to
// a document and a page that retrieval actually returned, so a citation cannot be fabricated. That
// property is only worth having if the marker stays clickable, so every element that can hold prose
// runs its immediate string children through the splitter. `code` and `pre` are pointedly absent —
// a [1] inside a code block is code.
function buildComponents(citations: Citation[], onCite?: (citation: Citation) => void): Components {
  const cite = (children: ReactNode) => withCitations(children, citations, onCite)

  return {
    p: ({ children }) => <p className="my-2 first:mt-0 last:mb-0">{cite(children)}</p>,
    strong: ({ children }) => <strong className="font-semibold text-ink">{cite(children)}</strong>,
    em: ({ children }) => <em>{cite(children)}</em>,
    h1: ({ children }) => <h3 className="mt-4 mb-2 text-base first:mt-0">{cite(children)}</h3>,
    h2: ({ children }) => <h3 className="mt-4 mb-2 text-base first:mt-0">{cite(children)}</h3>,
    h3: ({ children }) => <h4 className="mt-3 mb-1.5 text-sm font-semibold text-ink first:mt-0">{cite(children)}</h4>,
    h4: ({ children }) => <h4 className="mt-3 mb-1.5 text-sm font-semibold text-ink first:mt-0">{cite(children)}</h4>,
    h5: ({ children }) => <h4 className="mt-3 mb-1.5 text-sm font-semibold text-ink first:mt-0">{cite(children)}</h4>,
    h6: ({ children }) => <h4 className="mt-3 mb-1.5 text-sm font-semibold text-ink first:mt-0">{cite(children)}</h4>,
    ul: ({ children }) => <ul className="my-2 list-disc pl-5 first:mt-0 last:mb-0">{children}</ul>,
    ol: ({ children }) => <ol className="my-2 list-decimal pl-5 first:mt-0 last:mb-0">{children}</ol>,
    li: ({ children }) => <li className="my-0.5">{cite(children)}</li>,
    blockquote: ({ children }) => (
      <blockquote className="my-2 border-l-2 border-line-strong pl-3 text-ink-2">{children}</blockquote>
    ),
    hr: () => <hr className="my-3 border-0 border-t border-line" />,
    // Links come from the model, so they open in a new tab and never carry the referrer.
    a: ({ children, href }) => (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer nofollow"
        className="text-accent underline decoration-accent-deep underline-offset-2 hover:decoration-accent"
      >
        {children}
      </a>
    ),
    // A table can be wider than the bubble; it scrolls inside itself rather than stretching the
    // thread and pushing the composer off screen.
    table: ({ children }) => (
      <div className="my-2.5 overflow-x-auto rounded-ctl border border-line">
        <table className="w-full border-collapse text-[13px]">{children}</table>
      </div>
    ),
    th: ({ children }) => (
      <th className="border-b border-line bg-surface-2 px-2.5 py-1.5 text-left font-semibold text-ink">
        {cite(children)}
      </th>
    ),
    td: ({ children }) => (
      <td className="border-b border-line-soft px-2.5 py-1.5 align-top">{cite(children)}</td>
    ),
    code: ({ children, className: codeClass }) => {
      // react-markdown gives fenced code a language- class and inline code none, which is the
      // only signal separating `O(n)` mid-sentence from a displayed block.
      const fenced = /language-/.test(codeClass ?? '')
      if (!fenced) {
        return (
          <code className="rounded-[3px] border border-line-soft bg-surface-2 px-1 py-0.5 font-mono text-[0.9em] text-ink">
            {children}
          </code>
        )
      }
      return <code className={cx('font-mono text-[12.5px]', codeClass)}>{children}</code>
    },
    pre: ({ children }) => (
      <pre className="my-2.5 overflow-x-auto rounded-ctl border border-line bg-ground-2 p-3">
        {children}
      </pre>
    ),
  }
}

// Replaces [n] in raw text with a chip. Only strings are touched; nested elements are left alone
// because they render through their own component override and get the same treatment there.
function withCitations(
  children: ReactNode,
  citations: Citation[],
  onCite?: (citation: Citation) => void,
): ReactNode {
  if (citations.length === 0 || !onCite) {
    return children
  }
  const nodes = Array.isArray(children) ? children : [children]
  return nodes.map((node, index) =>
    typeof node === 'string' ? (
      <Fragment key={index}>{splitMarkers(node, citations, onCite)}</Fragment>
    ) : (
      node
    ),
  )
}

function splitMarkers(
  text: string,
  citations: Citation[],
  onCite: (citation: Citation) => void,
): ReactNode[] {
  return text.split(/(\[\d+\])/g).map((part, index) => {
    const match = part.match(/^\[(\d+)\]$/)
    const citation = match ? citations.find((c) => c.index === Number(match[1])) : undefined
    if (!citation) {
      return <Fragment key={index}>{part}</Fragment>
    }
    return (
      <button
        key={index}
        type="button"
        onClick={() => onCite(citation)}
        className={cx(
          'tnum mx-0.5 cursor-pointer rounded-[3px] border border-accent-deep bg-surface-2 px-1 align-baseline',
          'font-mono text-[10.5px] text-ink transition duration-150 hover:bg-accent hover:text-on-accent',
        )}
      >
        {part}
      </button>
    )
  })
}
