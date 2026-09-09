import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, coursesApi, documentsApi } from '../lib/api'
import type { CourseOutline, DocumentOutline } from '../lib/types'
import { AppShell } from '../components/AppShell'
import { Empty, ErrorText, Eyebrow, Loading, Meta, PageTitle, Panel, Pill } from '../components/ui'

// Phase 28.4 — what is actually in this course.
//
// The instructor has a confusion heatmap; a student had a list of filenames, which is the table of
// contents of a folder rather than of a subject. Everything on this page is read from columns that
// have been written on every ingest since Phase 13.4 and read by one class each: `section_path` fed
// small-to-big expansion, `document_terms` fed the glossary on one page, and
// `question_event_documents` fed the instructor's heatmap.
//
// **"Never asked" leads, because it is the useful half.** The instructor's page reports what
// confused people; this reports what nobody has touched, which a week before an exam is the more
// actionable of the two.
export function CourseOutlinePage() {
  const { id = '' } = useParams()
  const [courseName, setCourseName] = useState<string | undefined>()
  const [outline, setOutline] = useState<CourseOutline | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    coursesApi
      .get(id)
      .then((course) => setCourseName(course.name))
      .catch(() => {
        // The rail's label is cosmetic; the outline below carries the real failure.
      })
  }, [id])

  useEffect(() => {
    let active = true
    setLoading(true)
    documentsApi
      .outline(id)
      .then((result) => {
        if (active) setOutline(result)
      })
      .catch((err) => {
        if (active) {
          setError(err instanceof ApiError ? err.message : 'Could not load this course’s contents.')
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [id])

  return (
    <AppShell courseName={courseName}>
      <PageTitle
        eyebrow={courseName ?? 'Course'}
        title="What's in this course"
        sub="Every document's sections, the pages they cover, and the terms they define."
      />

      {loading && <Loading />}
      {error && <ErrorText>{error}</ErrorText>}

      {outline && !loading && (
        <>
          {outline.documents.length === 0 ? (
            <Empty>Nothing has been ingested into this course yet.</Empty>
          ) : (
            <>
              <Meta className="mb-6 block">
                {outline.documents.length}{' '}
                {outline.documents.length === 1 ? 'document' : 'documents'}
                {outline.neverAsked > 0 && (
                  <>
                    {' · '}
                    <strong className="text-ink">
                      {outline.neverAsked} nobody has ever asked about
                    </strong>
                  </>
                )}
                . Question counts are all-time, not a rolling window — the claim is that nothing has
                ever landed here, and a 30-day window would say that about a quiet week.
              </Meta>

              <div className="flex flex-col gap-4">
                {outline.documents.map((document) => (
                  <DocumentCard key={document.documentId} document={document} />
                ))}
              </div>
            </>
          )}
        </>
      )}
    </AppShell>
  )
}

function DocumentCard({ document }: { document: DocumentOutline }) {
  const untouched = document.questionCount === 0
  return (
    <Panel className="px-5 py-4">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="m-0 truncate font-display text-[17px] font-bold tracking-[-0.015em] text-ink">
            {document.filename}
          </p>
          <Meta>
            {document.pageCount > 0 && `${document.pageCount} pages · `}
            {document.chunkCount} passages
            {document.sections.length > 0 && ` · ${document.sections.length} sections`}
          </Meta>
        </div>
        {/* The count is the point of the badge, and zero is the interesting value — so it is a
            warning tone rather than a neutral one, and it says what it means rather than "0". */}
        <Pill tone={untouched ? 'warn' : 'neutral'}>
          {untouched
            ? 'never asked about'
            : `${document.questionCount} question${document.questionCount === 1 ? '' : 's'}`}
        </Pill>
      </div>

      {document.sections.length === 0 ? (
        <Meta className="block">
          No headings were detected in this document, so it has no outline — only its passages.
        </Meta>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {document.sections.map((section) => (
            <li
              key={section.sectionPath}
              className="border-t border-line-soft pt-2 first:border-t-0 first:pt-0"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="min-w-0 text-[13.5px] text-ink-2">{section.sectionPath}</span>
                <span className="tnum shrink-0 font-mono text-[11px] text-ink-muted">
                  {pageSpan(section.firstPage, section.lastPage)}
                  {' · '}
                  {section.chunkCount} passage{section.chunkCount === 1 ? '' : 's'}
                </span>
              </div>
              {section.terms.length > 0 && (
                <div className="mt-1 flex flex-wrap gap-1">
                  {section.terms.map((term) => (
                    <span
                      key={term}
                      className="rounded-full border border-line bg-ground-2 px-2 py-0.5 text-[11px] text-ink-muted"
                    >
                      {term}
                    </span>
                  ))}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <Eyebrow className="mt-3 block text-ink-muted">
        {termCount(document)} term{termCount(document) === 1 ? '' : 's'} defined
      </Eyebrow>
    </Panel>
  )
}

// A section can span one page or several, and a chunk that never got a page number leaves both
// ends null — which happens for text extracted without page boundaries. "—" rather than "p.null".
function pageSpan(first: number | null, last: number | null): string {
  if (first == null && last == null) return '—'
  if (first == null || last == null || first === last) return `p.${first ?? last}`
  return `pp.${first}–${last}`
}

// Terms are attributed to a section by looking for them in its text, so one term can appear under
// two sections. This counts the distinct ones, which is what "defined in this document" means.
function termCount(document: DocumentOutline): number {
  return new Set(document.sections.flatMap((section) => section.terms)).size
}
