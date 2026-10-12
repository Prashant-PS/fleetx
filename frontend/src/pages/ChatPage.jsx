import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { splitRelated } from '../ai.js';
import ActionCard from '../chat/ActionCard.jsx';
import { useChat } from '../chat/ChatContext.jsx';
import PromptBox, { MODES } from '../chat/PromptBox.jsx';
import RichText from '../RichText.jsx';
import { navigate } from '../router.jsx';
import Icon from '../ui/Icon.jsx';

export default function ChatPage() {
  const { turns, ask, stop, busy, updateAction } = useChat();
  const endRef = useRef(null);

  useEffect(() => {
    if (!turns.length) navigate('/', { replace: true });
  }, [turns.length]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns.length]);

  const lastMode = turns.length ? turns[turns.length - 1].mode : '';

  return (
    <div className="chat-page">
      <div className="chat-thread">
        {turns.map((turn, i) => (
          <ChatTurn key={turn.id} turn={turn} isLast={i === turns.length - 1} busy={busy} onAsk={(q) => ask(q, turn.mode)}
            onAction={(actionId, patch) => updateAction(turn.id, actionId, patch)} />
        ))}
        <div ref={endRef} />
      </div>
      <div className="chat-input">
        <PromptBox key={lastMode} compact busy={busy} onStop={stop} onSubmit={ask} initialMode={lastMode} autoFocus />
        <p className="fine-print">AI+ can make mistakes. Check important facts; actions only run after you confirm them.</p>
      </div>
    </div>
  );
}

function ChatTurn({ turn, isLast, busy, onAsk, onAction }) {
  const { answer, related } = splitRelated(turn.text);
  const mode = MODES.find((m) => m.id === turn.mode);
  return (
    <article className="turn-block">
      <div className="user-bubble">
        {mode && <span className="mode-tag"><Icon name={mode.icon} size={14} /> {mode.label}</span>}
        {turn.question}
      </div>
      <div className="assistant">
        <span className={turn.streaming ? 'assistant-avatar pulsing' : 'assistant-avatar'}><Icon name="sparkle" size={18} /></span>
        <div className="assistant-body">
          {turn.streaming && !answer && (
            <div className="ai-status"><span className="spinner" /> {turn.status}</div>
          )}
          {answer && (
            <div className="answer-body">
              <RichText text={answer} />
              {turn.streaming && <span className="caret" aria-hidden />}
            </div>
          )}
          {turn.streaming && answer && turn.status && !['Writing…', 'Thinking…'].includes(turn.status) && (
            <div className="ai-status small"><span className="spinner" /> {turn.status}</div>
          )}
          {turn.error && <div className="ai-error">{turn.error}</div>}

          {turn.actions.length > 0 && (
            <div className="action-list">
              {turn.actions.map((a) => <ActionCard key={a.id} action={a} onChange={(patch) => onAction(a.id, patch)} />)}
            </div>
          )}

          {turn.sources.length > 0 && (
            <div className="answer-sources">
              {turn.sources.slice(0, 8).map((s) => (
                <a key={s.url} href={s.url} target="_blank" rel="noreferrer" className="source-chip" title={s.url}>
                  <span className="source-id">{host(s.url)}</span> {s.label.length > 40 ? s.label.slice(0, 38) + '…' : s.label}
                </a>
              ))}
            </div>
          )}

          {!turn.streaming && answer && <TurnTools question={turn.question} answer={answer} />}

          {isLast && !turn.streaming && related.length > 0 && (
            <div className="suggestions-row">
              {related.map((q) => (
                <button key={q} type="button" className="chip" disabled={busy} onClick={() => onAsk(q)}>{q}</button>
              ))}
            </div>
          )}
        </div>
      </div>
    </article>
  );
}

function TurnTools({ question, answer }) {
  const [state, setState] = useState('');
  const copy = async () => {
    await navigator.clipboard?.writeText(answer);
    setState('Copied');
  };
  const save = async () => {
    try {
      await api.post('/api/saved', { kind: 'chat', title: question, content: answer, source: 'chat' });
      setState('Saved');
    } catch (e) {
      setState(e.message);
    }
  };
  return (
    <div className="turn-tools">
      <button type="button" className="icon-btn small" onClick={copy} title="Copy answer"><Icon name="copy" size={16} /></button>
      <button type="button" className="icon-btn small" onClick={save} title="Save answer"><Icon name="bookmark" size={16} /></button>
      {state && <span className="tool-state">{state}</span>}
    </div>
  );
}

function host(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return '';
  }
}
