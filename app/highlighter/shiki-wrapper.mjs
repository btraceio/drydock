// Drydock's syntax highlighter wrapper: Shiki (VS Code's TextMate grammars)
// reduced to a single IIFE file the review code-viewer page loads locally.
//
// Rendering deliberately goes through the highlighter's codeToTokens + our own
// per-line HTML, NOT codeToHtml: the token path avoids the hast-util-to-html
// dependency subtree (10+ transitive packages) -- the build aliases that
// import to a loud-failure stub (hast-to-html-stub.mjs) -- and the drawer page
// needs its own line markup anyway (gutter numbers, target-line highlight,
// click-per-line). Token color/fontStyle are inlined as styles.
//
// Regenerated at Gradle build time by the fetchHighlighterDeps /
// buildHighlighterBundle tasks (pinned npm tarballs, checksum-verified;
// esbuild --bundle --format=iife, NODE_PATH pointing at the unpacked closure).
// Exposes window.DrydockShiki:
//   highlight(code, lang) -> Promise<{ html, bg, fg, themeName }>
//     html: one div.ln per line (id="L<n>", gutter span.no)
//   LANG_IDS: the bundled language ids (for ext -> lang mapping)
import { createHighlighterCore } from '@shikijs/core'
import { createJavaScriptRegexEngine } from '@shikijs/engine-javascript'

import githubDark from '@shikijs/themes/github-dark'
import c from '@shikijs/langs/c'
import cpp from '@shikijs/langs/cpp'
import csharp from '@shikijs/langs/csharp'
import css from '@shikijs/langs/css'
import diff from '@shikijs/langs/diff'
import go from '@shikijs/langs/go'
import html from '@shikijs/langs/html'
import java from '@shikijs/langs/java'
import javascript from '@shikijs/langs/javascript'
import json from '@shikijs/langs/json'
import kotlin from '@shikijs/langs/kotlin'
import markdown from '@shikijs/langs/markdown'
import php from '@shikijs/langs/php'
import python from '@shikijs/langs/python'
import ruby from '@shikijs/langs/ruby'
import rust from '@shikijs/langs/rust'
import shellscript from '@shikijs/langs/shellscript'
import sql from '@shikijs/langs/sql'
import swift from '@shikijs/langs/swift'
import toml from '@shikijs/langs/toml'
import typescript from '@shikijs/langs/typescript'
import xml from '@shikijs/langs/xml'
import yaml from '@shikijs/langs/yaml'
import zig from '@shikijs/langs/zig'

const GRAMMARS = {
  c, cpp, csharp, css, diff, go, html, java, javascript, json, kotlin,
  markdown, php, python, ruby, rust, shellscript, sql, swift, toml,
  typescript, xml, yaml, zig,
}

const THEME = 'github-dark'

// TextMate FontStyle bit flags (see @shikijs/types).
const BOLD = 1 << 0;
const ITALIC = 1 << 1;
const UNDERLINE = 1 << 2;

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function tokenStyle(token) {
  let style = '';
  if (token.color && token.color !== '#000000' && token.color !== 'inherit') {
    style += `color:${token.color};`;
  }
  const font = token.fontStyle || 0;
  if (font & BOLD) style += 'font-weight:bold;';
  if (font & ITALIC) style += 'font-style:italic;';
  if (font & UNDERLINE) style += 'text-decoration:underline;';
  return style;
}

const highlighterPromise = createHighlighterCore({
  themes: [githubDark],
  langs: Object.values(GRAMMARS),
  engine: createJavaScriptRegexEngine({ forgiving: true }),
});

window.DrydockShiki = {
  LANG_IDS: Object.keys(GRAMMARS),

  /** Highlights `code`; resolves to { html, bg, fg, themeName } -- one div.ln per line. */
  async highlight(code, lang) {
    const highlighter = await highlighterPromise;
    const result = highlighter.codeToTokens(code, {
      lang: GRAMMARS[lang] ? lang : 'text',
      theme: THEME,
    });
    const lines = result.tokens.map((tokens, index) => {
      const body = tokens.map(t => {
        const style = tokenStyle(t);
        const text = escapeHtml(t.content);
        return style ? `<span style="${style}">${text}</span>` : text;
      }).join('');
      const no = index + 1;
      return `<div class="ln" id="L${no}"><span class="no">${no}</span>${body || '\n'}</div>`;
    });
    return {
      html: lines.join('\n'),
      bg: result.bg || '#1e1e1e',
      fg: result.fg || '#d4d4d4',
      themeName: result.themeName || THEME,
    };
  },
};
