export const RELATED_MARKER = '<<related>>';

/**
 * POSTs a question to the backend and reads its server-sent events.
 * handlers: { status(message), delta(text), sources(list), error(message), done() }
 */
export async function streamAnswer(question, history, handlers, signal) {
  const res = await fetch('/api/ai/answer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ question, history }),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(`AI request failed (${res.status})`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary;
    while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
      dispatch(raw, handlers);
    }
  }
}

function dispatch(raw, handlers) {
  let event = 'message';
  const data = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (!data.length && event !== 'done') return;
  const payload = data.length ? JSON.parse(data.join('\n')) : {};
  if (event === 'status') handlers.status?.(payload.message);
  else if (event === 'delta') handlers.delta?.(payload.text);
  else if (event === 'sources') handlers.sources?.(payload);
  else if (event === 'error') handlers.error?.(payload.message);
  else if (event === 'done') handlers.done?.();
}

/** Splits the model's text into the visible answer and its suggested follow-up questions. */
export function splitRelated(text) {
  const i = text.indexOf(RELATED_MARKER);
  if (i < 0) {
    // Hide a marker that is still arriving, e.g. "<<rel".
    const partial = text.lastIndexOf('<<');
    const visible = partial >= 0 && RELATED_MARKER.startsWith(text.slice(partial)) ? text.slice(0, partial) : text;
    return { answer: visible, related: [] };
  }
  const related = text
    .slice(i + RELATED_MARKER.length)
    .split('\n')
    .map((l) => l.replace(/^[-*\d.)\s]+/, '').trim())
    .filter(Boolean)
    .slice(0, 4);
  return { answer: text.slice(0, i).trim(), related };
}
