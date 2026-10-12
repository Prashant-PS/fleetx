import { useEffect, useRef, useState } from 'react';
import RichText from './RichText.jsx';
import { splitRelated, streamAnswer } from './ai.js';

/**
 * Streams Claude's answer for the search, then lets the user keep asking follow-ups in the same box.
 * Remount it (key = query) to start a new conversation.
 */
export default function AiOverview({ query, onSearch }) {
  const [turns, setTurns] = useState([]);
  const [followUp, setFollowUp] = useState('');
  const controllerRef = useRef(null);
  const endRef = useRef(null);

  const busy = turns.some((t) => t.streaming);

  const ask = (question, previous) => {
    const controller = new AbortController();
    controllerRef.current = controller;
    const index = previous.length;
    const history = previous
      .filter((t) => !t.error && t.text)
      .map((t) => ({ question: t.question, answer: splitRelated(t.text).answer }));
    const update = (patch) =>
      setTurns((ts) => ts.map((t, i) => (i === index ? { ...t, ...(typeof patch === 'function' ? patch(t) : patch) } : t)));

    setTurns([...previous, { question, text: '', status: 'Thinking…', sources: [], streaming: true }]);
    streamAnswer(
      question,
      history,
      {
        status: (message) => update({ status: message }),
        delta: (text) => update((t) => ({ text: t.text + text })),
        sources: (sources) => update({ sources }),
        error: (message) => update({ error: message }),
        done: () => update({ streaming: false }),
      },
      controller.signal
    )
      .catch((e) => {
        if (e.name !== 'AbortError') update({ error: 'Could not reach the AI service.' });
      })
      .finally(() => update({ streaming: false }));
  };

  useEffect(() => {
    ask(query, []);
    return () => controllerRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  useEffect(() => {
    if (turns.length > 1) endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [turns.length]);

  const submitFollowUp = (e) => {
    e.preventDefault();
    const q = followUp.trim();
    if (!q || busy) return;
    setFollowUp('');
    ask(q, turns);
  };

  const stop = () => controllerRef.current?.abort();

  return (
    <section className="answer ai">
      <div className="answer-head">
        <span className={busy ? 'answer-icon pulsing' : 'answer-icon'} aria-hidden>✦</span>
        <span className="answer-title">AI Overview</span>
        <span className="badge">Claude</span>
      </div>

      {turns.map((turn, i) => (
        <Turn key={i} turn={turn} showQuestion={i > 0} isLast={i === turns.length - 1} onAsk={(q) => !busy && ask(q, turns)} onSearch={onSearch} />
      ))}

      <form className="follow-up" onSubmit={submitFollowUp}>
        <input
          value={followUp}
          onChange={(e) => setFollowUp(e.target.value)}
          placeholder={busy ? 'Answering…' : 'Ask a follow-up, or compare (e.g. "vs Volvo")'}
          aria-label="Ask a follow-up question"
        />
        {busy ? (
          <button type="button" onClick={stop} className="follow-up-btn" aria-label="Stop answering">■</button>
        ) : (
          <button type="submit" className="follow-up-btn" disabled={!followUp.trim()} aria-label="Ask">➤</button>
        )}
      </form>
      <p className="ai-disclaimer">AI answers can be wrong. Check important facts against the sources.</p>
      <div ref={endRef} />
    </section>
  );
}

function Turn({ turn, showQuestion, isLast, onAsk, onSearch }) {
  const { answer, related } = splitRelated(turn.text);
  return (
    <div className="turn">
      {showQuestion && <div className="turn-question">{turn.question}</div>}
      {turn.streaming && !answer && (
        <div className="ai-status">
          <span className="spinner" /> {turn.status}
        </div>
      )}
      {answer && (
        <div className="answer-body">
          <RichText text={answer} />
          {turn.streaming && <span className="caret" aria-hidden />}
        </div>
      )}
      {turn.streaming && answer && turn.status === 'Searching the web…' && (
        <div className="ai-status small"><span className="spinner" /> {turn.status}</div>
      )}
      {turn.error && <div className="ai-error">{turn.error}</div>}
      {turn.sources.length > 0 && (
        <div className="answer-sources">
          {turn.sources.slice(0, 8).map((s) => (
            <a key={s.url} href={s.url} target="_blank" rel="noreferrer" className="source-chip" title={s.title || s.label}>
              <span className="source-id">{hostname(s.url).replace(/^www\./, '')}</span>
              {s.label.length > 40 ? s.label.slice(0, 38) + '…' : s.label}
            </a>
          ))}
        </div>
      )}
      {isLast && !turn.streaming && related.length > 0 && (
        <div className="related">
          <div className="related-title">People also ask</div>
          {related.map((q) => (
            <div className="related-row" key={q}>
              <button type="button" className="related-q" onClick={() => onAsk(q)}>{q}</button>
              <button type="button" className="related-search" onClick={() => onSearch(q)} title="Search this instead">↗</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function hostname(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
}
