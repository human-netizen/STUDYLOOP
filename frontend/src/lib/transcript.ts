import type { ConversationTranscript, TranscriptMessage } from './types'

// Phase 29.1's last bullet, which needed 28.1's transcript endpoint before it could exist: a chat
// thread as a Markdown file somebody can keep.
//
// **Built on the client, from the transcript the server already returns.** A server-side exporter
// would be a second renderer of the same rows — one for the screen and one for the file — and the
// two would disagree the first time a citation shape changed. There is nothing here the page does
// not already have on screen.
//
// The citations are written out as a list under each answer rather than left as bare `[n]` markers.
// A marker with nothing to point at is the dead-link problem V29 exists to avoid, and a file that
// leaves the reader's machine has no viewer to click into at all.

export function threadToMarkdown(transcript: ConversationTranscript): string {
  const lines: string[] = []

  lines.push(`# ${transcript.title?.trim() || 'Conversation'}`)
  lines.push('')
  lines.push(`_Asked in StudyLoop on ${formatDate(transcript.createdAt)}._`)
  lines.push('')

  for (const message of transcript.messages) {
    lines.push(...messageLines(message))
  }

  return lines.join('\n')
}

function messageLines(message: TranscriptMessage): string[] {
  const lines: string[] = []

  if (message.role === 'USER') {
    lines.push('---')
    lines.push('')
    // Blockquoted, so a question spanning several lines still reads as one question next to the
    // answer under it.
    for (const line of message.content.split('\n')) {
      lines.push(`> ${line}`)
    }
    lines.push('')
    return lines
  }

  if (message.role === 'GENERAL') {
    // The one turn in a transcript that was never grounded in the course's materials. It is
    // labelled here for the same reason it is labelled on screen and in the replay to the model:
    // a paragraph indistinguishable from a cited one is a paragraph somebody will cite.
    lines.push('**Answered from general knowledge — not from this course.**')
    lines.push('')
  }

  lines.push(message.content.trim())
  lines.push('')

  if (message.citations.length > 0) {
    lines.push('**Sources**')
    lines.push('')
    for (const citation of message.citations) {
      const where =
        citation.documentSource === 'FORUM'
          ? 'answered by the class'
          : [citation.pageNumber != null ? `p.${citation.pageNumber}` : null,
             citation.visual ? 'figure' : null]
              .filter(Boolean)
              .join(' · ')
      lines.push(`- [${citation.index}] ${citation.filename}${where ? ` · ${where}` : ''}`)
    }
    lines.push('')
  }

  return lines
}

function formatDate(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString()
}
