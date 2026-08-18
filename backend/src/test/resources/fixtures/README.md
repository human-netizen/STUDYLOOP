# Fixture corpus — attribution and licence

These PDFs are the corpus the retrieval evaluation harness measures against. They are committed to
the repository on purpose: the harness seeds its own course, uploads these files, measures, and
tears down, so an evaluation run produces the same numbers on any machine and inside CI.

## Source

**Open Data Structures (in Java), Edition 0.1G** by **Pat Morin**
<https://opendatastructures.org>

Licensed under a **Creative Commons Attribution 2.5 Canada Licence** (CC BY 2.5 CA):
<https://creativecommons.org/licenses/by/2.5/ca/>

> The book and accompanying source code are free (*libre* and *gratis*) and are released under a
> Creative Commons Attribution License. Users are free to copy, distribute, use, and adapt the text
> and source code, even commercially.

The licence permits redistribution and adaptation, including commercially, provided the author is
credited. This notice is that credit.

## What was changed

The book's single 334-page PDF was split into one file per chapter, and the front matter,
bibliography and index were dropped. Nothing inside a chapter was edited — page content and reading
order are exactly as published. Each file's page 1 is the chapter's first page, which is what the
golden set's `expectedPages` refer to.

| File | Source pages |
|---|---|
| `01-introduction.pdf` | 13–40 |
| `02-array-based-lists.pdf` | 41–74 |
| `03-linked-lists.pdf` | 75–98 |
| `04-skiplists.pdf` | 99–118 |
| `05-hash-tables.pdf` | 119–144 |
| `06-binary-trees.pdf` | 145–164 |
| `07-random-binary-search-trees.pdf` | 165–184 |
| `08-scapegoat-trees.pdf` | 185–196 |
| `09-red-black-trees.pdf` | 197–222 |
| `10-heaps.pdf` | 223–236 |
| `11-sorting-algorithms.pdf` | 237–258 |
| `12-graphs.pdf` | 259–276 |
| `13-data-structures-for-integers.pdf` | 277–294 |
| `14-external-memory-searching.pdf` | 295–318 |

## Why this book

- **The licence allows it.** A public repository cannot carry lecture material that belongs to an
  instructor, and an evaluation corpus that cannot be published is one nobody can reproduce.
- **It is shaped like real course material** — headings, tables, figures, pseudocode and
  cross-references between chapters — which is what the chunking work is measured against.
- **Its chapters are deliberately hard to tell apart.** Skiplists, treaps, scapegoat trees and
  red-black trees all answer *"what is the expected search time"*. Retrieval has to discriminate
  between near neighbours rather than between unrelated topics, which is the realistic difficulty
  and the one a corpus of unrelated documents would hide.

## Changing this corpus

`FixtureCorpusTest` guards it: every file must extract real text, page numbers must start at 1 and
be contiguous, and the corpus as a whole must stay above a chunk-count floor. If you replace or
re-cut a file, the golden set's `expectedPages` for that document are no longer valid and must be
re-checked — the metrics will otherwise keep printing plausible numbers against a corpus that no
longer says what the questions claim.
