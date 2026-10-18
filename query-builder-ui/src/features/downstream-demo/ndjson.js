/**
 * Splits streamed NDJSON text into complete lines. `carry` is the unterminated tail left over
 * from the previous chunk; the returned `rest` is the new tail (a row can span chunks).
 */
export function splitNdjson(carry, chunkText) {
  const text = carry + chunkText
  const lastNewline = text.lastIndexOf('\n')
  if (lastNewline === -1) return { lines: [], rest: text }
  const lines = text
    .slice(0, lastNewline)
    .split('\n')
    .filter((l) => l.length > 0)
  return { lines, rest: text.slice(lastNewline + 1) }
}
