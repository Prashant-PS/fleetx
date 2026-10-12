import { useEffect, useRef, useState } from 'react';
import Icon from '../ui/Icon.jsx';

export const MODES = [
  { id: 'search', label: 'Search', icon: 'search', hint: 'Quick answers from your fleet, the OEM workbook, news and the web' },
  { id: 'analysis', label: 'Deep analysis', icon: 'analysis', hint: 'Takes longer, digs into patterns and root causes' },
  { id: 'report', label: 'Create report', icon: 'report', hint: 'A structured report you can save or schedule' },
  { id: 'action', label: 'Take action', icon: 'action', hint: 'Proposes schedules, reminders and fixes for you to confirm' },
];

const SpeechRecognition = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition);

/** The "Ask anything about your fleet…" box with mode chips, voice input and send. */
export default function PromptBox({ onSubmit, busy, onStop, autoFocus, initialMode = '', compact }) {
  const [text, setText] = useState('');
  const [mode, setMode] = useState(initialMode);
  const [listening, setListening] = useState(false);
  const recognition = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => () => recognition.current?.abort(), []);

  const submit = () => {
    if (!text.trim() || busy) return;
    onSubmit(text.trim(), mode);
    setText('');
  };

  const toggleMic = () => {
    if (listening) {
      recognition.current?.stop();
      return;
    }
    const r = new SpeechRecognition();
    r.lang = navigator.language || 'en-IN';
    r.interimResults = true;
    r.onresult = (e) => setText(Array.from(e.results).map((res) => res[0].transcript).join(''));
    r.onend = () => setListening(false);
    r.onerror = () => setListening(false);
    recognition.current = r;
    setListening(true);
    r.start();
  };

  return (
    <div className={compact ? 'prompt compact' : 'prompt'}>
      <textarea
        ref={inputRef}
        value={text}
        autoFocus={autoFocus}
        rows={compact ? 1 : 2}
        placeholder={listening ? 'Listening…' : 'Ask anything about your fleet…'}
        aria-label="Ask anything about your fleet"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <div className="prompt-bar">
        <div className="modes" role="radiogroup" aria-label="Answer mode">
          {MODES.map((m) => (
            <button
              key={m.id}
              type="button"
              role="radio"
              aria-checked={mode === m.id}
              className={mode === m.id ? 'mode active' : 'mode'}
              title={m.hint}
              onClick={() => {
                setMode(mode === m.id ? '' : m.id);
                inputRef.current?.focus();
              }}
            >
              <Icon name={m.icon} size={16} /> {m.label}
            </button>
          ))}
        </div>
        <div className="prompt-actions">
          {SpeechRecognition && (
            <button type="button" className={listening ? 'icon-btn listening' : 'icon-btn'} onClick={toggleMic}
              aria-label={listening ? 'Stop voice input' : 'Voice input'} title="Voice input">
              <Icon name="mic" />
            </button>
          )}
          {busy ? (
            <button type="button" className="send" onClick={onStop} aria-label="Stop answering">
              <Icon name="stop" />
            </button>
          ) : (
            <button type="button" className="send" onClick={submit} disabled={!text.trim()} aria-label="Send">
              <Icon name="send" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
