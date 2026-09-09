// Saving bytes the API returned as a file on the reader's disk (Phase 29.1).
//
// **A download here cannot be a link.** Every route is bearer-authenticated and an `<a href>`
// carries no Authorization header, so a plain link to an export endpoint gets a 401 rendered as a
// page. The bytes come back through `fetchBlob` instead, and this hands them to a temporary object
// URL — a same-origin `blob:` address the browser is willing to save from.

// The anchor dance, in one place. It was already written twice by the time there was a third
// caller, and the copies disagreed about the two details below.
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  // In the document, not detached: Firefox ignores a click on an anchor that is not in the tree.
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // Revoking synchronously can beat the browser to reading the URL it was just given. One turn of
  // the event loop is enough, and the URL cannot outlive it.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

// A course or note name becomes a filename, and names contain characters a filesystem refuses.
// Replaced with a space rather than stripped, so "Algorithms: Part 2" stays two words instead of
// running together.
export function filenameSafe(name: string, fallback: string): string {
  const cleaned = name
    .replace(/[\\/:*?"<>|]/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^\.+/, '')
    .trim()
  return cleaned.length > 0 ? cleaned.slice(0, 80).trim() : fallback
}
