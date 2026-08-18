import { createLowlight } from 'lowlight'
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import type { Element, ElementContent, Root } from 'hast'
import { visit } from 'unist-util-visit'

// Syntax highlighting for fenced code in rendered Markdown, over a chosen set of languages.
//
// This is rehype-highlight's job and started as rehype-highlight. It was replaced after measuring:
// that plugin does `import {common} from 'lowlight'` at module scope, so all thirty-seven of
// lowlight's common grammars are in the bundle whether or not you pass your own `languages` option
// — the import is live, so no bundler can shake it out. It cost ~600 kB raw / ~180 kB gzipped on a
// 738 kB bundle, and most of it was Ruby, Swift, Objective-C, PHP and Perl, which will not appear
// in an answer about this corpus.
//
// Ten grammars, chosen for what a computer-science course and this codebase actually contain. A
// fence in an unregistered language still renders — as a code block, unhighlighted — so the cost of
// being wrong about this list is monochrome text, not a broken page.
const lowlight = createLowlight({
  bash,
  c,
  cpp,
  java,
  javascript,
  json,
  python,
  sql,
  typescript,
  xml,
})

// `detect` is deliberately absent. highlight.js guesses the language of unlabelled code by scoring
// it against every grammar, and it guesses badly on short snippets — pseudocode in a lecture answer
// would come out coloured as whichever language happened to score highest. Unlabelled code stays
// unhighlighted.
export function rehypeHighlight() {
  return function (tree: Root) {
    visit(tree, 'element', (node: Element, _index, parent) => {
      if (node.tagName !== 'code' || !parent || parent.type !== 'element'
          || parent.tagName !== 'pre') {
        return
      }
      const language = languageOf(node)
      if (!language || !lowlight.registered(language)) {
        return
      }
      const source = textOf(node)
      if (!source) {
        return
      }
      node.properties.className = ['hljs', 'language-' + language]
      // A hast Root's children are typed as RootContent, which admits doctypes and comments that
      // cannot appear inside an element. lowlight emits only spans and text, so the narrowing is
      // sound and the alternative is a filter that can never remove anything.
      node.children = lowlight.highlight(language, source).children as ElementContent[]
    })
  }
}

function languageOf(node: Element): string | null {
  const classes = node.properties?.className
  const list = Array.isArray(classes) ? classes : []
  for (const entry of list) {
    const name = String(entry)
    if (name.startsWith('language-')) {
      return name.slice('language-'.length)
    }
  }
  return null
}

// The code element's text, before highlighting replaced its children. Markdown fences hold nothing
// but text nodes, so this needs no general hast-to-text pass.
function textOf(node: Element): string {
  return node.children
    .map((child) => (child.type === 'text' ? child.value : ''))
    .join('')
}
