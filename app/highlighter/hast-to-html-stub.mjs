// Stub for the highlighter bundle's --alias:hast-util-to-html: the
// DrydockShiki wrapper only uses the codeToTokens path, which never calls
// toHtml. If this ever fires, the wrapper started using an HTML-rendering
// path -- fix the wrapper, do not implement HTML rendering here (pulling the
// real hast-util-to-html would add its ~10-package transitive closure).
export function toHtml() {
  throw new Error('DrydockShiki: hast toHtml() must not be called; the wrapper renders tokens itself');
}
