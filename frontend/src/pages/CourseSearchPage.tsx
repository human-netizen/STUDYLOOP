import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { coursesApi, errorMessage, searchApi } from '../lib/api'
import type { SearchDocument, SearchHit, SearchResponse, SnippetPart } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { PdfViewer, type PdfTarget } from '../components/PdfViewer'
import {
  Button,
  Empty,
  ErrorText,
  Input,
  Loading,
  Meta,
  PageTitle,
  Panel,
  Pill,
  Row,
  Rows,
} from '../components/ui'

// Search the course's materials (Phase 9.3). The same hybrid retrieval the assistant runs, shown
// as passages instead of as an answer — no generation, and no confidence gate, so a weak match is
// still listed and the reader judges it. That makes this the page to reach for when the assistant
// refuses: the passages were always there, they just weren't strong enough to answer from.
//
// The query lives in the URL, so a result page can be linked, reloaded and backed out of.
export function CourseSearchPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const query = params.get('q') ?? ''

  const [courseName, setCourseName] = useState<string | undefined>()
  const [input, setInput] = useState(query)
  const [result, setResult] = useState<SearchResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<PdfTarget | null>(null)

  useEffect(() => {
    let active = true
    coursesApi
      .get(id)
      .then((course) => {
        if (active) setCourseName(course.name)
      })
      .catch(() => {
        // Cosmetic — the rail's course label. The search below reports its own failures.
      })
    return () => {
      active = false
    }
  }, [id])

  // Follow the URL rather than the form: that way the back button, a pasted link and a submit
  // all take the same path through here.
  useEffect(() => {
    setInput(query)
    if (!query.trim()) {
      setResult(null)
      setError(null)
      return
    }
    let active = true
    setLoading(true)
    searchApi
      .query(id, query)
      .then((response) => {
        if (!active) return
        setResult(response)
        setError(null)
      })
      .catch((err) => {
        if (!active) return
        setError(errorMessage(err, 'Could not run that search.'))
        setResult(null)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id, query])

  return (
    <AppShell courseName={courseName}>
      <PageTitle
        eyebrow="Materials"
        title="Search"
        sub="Find the passage yourself. This is the retrieval the assistant runs, without the answer on top — so it shows near misses too."
      />

      <form
        className="mb-8 flex items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setParams(input.trim() ? { q: input.trim() } : {})
        }}
      >
        <Input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="A word, a phrase, or the question you'd have asked…"
          className="flex-1"
          autoFocus
        />
        <Button type="submit" variant="primary">
          Search
        </Button>
      </form>

      {loading && <Loading>Searching</Loading>}
      {error && <ErrorText>{error}</ErrorText>}

      {!loading && !error && !query.trim() && (
        <Empty>
          Search matches meaning as well as words — “how do I stop overfitting” finds the
          regularization slide even if it never uses that phrase.
        </Empty>
      )}

      {!loading && !error && result && (
        <>
          <Meta className="mb-3 block">
            {result.hitCount === 0
              ? 'Nothing matched.'
              : `${result.hitCount} ${plural(result.hitCount, 'passage')} in ${result.documents.length} ${plural(result.documents.length, 'document')}.`}
          </Meta>

          {result.hitCount === 0 ? (
            <Empty>
              Nothing in this course's materials came close.{' '}
              <Link to={`/courses/${id}/forum`} className="text-accent">
                Ask the class
              </Link>{' '}
              — an accepted answer becomes material, and this search will find it.
            </Empty>
          ) : (
            <div className="grid gap-4">
              {result.documents.map((document) => (
                <DocumentResult
                  key={document.documentId}
                  document={document}
                  onOpen={setTarget}
                />
              ))}
            </div>
          )}
        </>
      )}

      {target && (
        <PdfViewer
          key={target.documentId}
          courseId={id}
          target={target}
          onClose={() => setTarget(null)}
        />
      )}
    </AppShell>
  )
}

// One document and everything that matched inside it. Grouped rather than listed flat, because
// five hits in one lecture are one finding — "it's in week 4" — not five competing results.
function DocumentResult({
  document,
  onOpen,
}: {
  document: SearchDocument
  onOpen: (target: PdfTarget) => void
}) {
  // Forum answers are corpus, not material: there is no file to open and no page to open it at.
  const openable = document.source !== 'FORUM'
  return (
    <Panel className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-4">
        <p className="m-0 min-w-0 truncate font-mono text-[13px] text-ink">{document.filename}</p>
        <Pill tone={openable ? 'neutral' : 'warn'}>
          {openable
            ? `${document.hitCount} ${plural(document.hitCount, 'match')}`
            : 'from the forum'}
        </Pill>
      </div>
      <Rows>
        {document.hits.map((hit) => (
          <Row key={hit.chunkId} interactive={openable}>
            <HitRow
              hit={hit}
              onOpen={
                openable
                  ? () =>
                      onOpen({
                        documentId: document.documentId,
                        filename: document.filename,
                        pageNumber: hit.pageNumber,
                      })
                  : undefined
              }
            />
          </Row>
        ))}
      </Rows>
    </Panel>
  )
}

// The passage itself. Clickable when there's a PDF behind it — the click opens the viewer at the
// page the passage starts on, exactly like a citation in chat.
function HitRow({ hit, onOpen }: { hit: SearchHit; onOpen?: () => void }) {
  const body = (
    <>
      <p className="m-0 text-[14px] leading-relaxed text-ink-2">
        <Snippet parts={hit.snippet} />
      </p>
      <div className="mt-1.5 flex items-center gap-3">
        <Meta>
          {hit.pageNumber != null ? `p.${hit.pageNumber}` : 'answered by the class'}
          {/* The cosine against the query — the same number the assistant's gate reads. Absent
              when the passage was found by wording alone. */}
          {hit.similarity != null && ` · similarity ${hit.similarity.toFixed(2)}`}
        </Meta>
      </div>
    </>
  )

  if (!onOpen) {
    return <div className="px-5 py-3.5">{body}</div>
  }
  return (
    <button
      type="button"
      onClick={onOpen}
      className="w-full cursor-pointer border-0 bg-transparent px-5 py-3.5 text-left"
    >
      {body}
    </button>
  )
}

// The server already split the extract into plain and matched runs, so this is a render, not a
// second pass of matching.
function Snippet({ parts }: { parts: SnippetPart[] }) {
  return (
    <>
      {parts.map((part, i) =>
        part.match ? (
          <mark key={i} className="rounded-[2px] bg-accent/20 px-0.5 text-ink">
            {part.text}
          </mark>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </>
  )
}

// "passage" → "passages", "match" → "matches".
function plural(value: number, word: string): string {
  if (value === 1) return word
  return word.endsWith('h') || word.endsWith('s') ? `${word}es` : `${word}s`
}
