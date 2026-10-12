import { createContext, useCallback, useContext, useRef, useState } from 'react';
import { postSse } from '../api.js';
import { splitRelated } from '../ai.js';

const ChatContext = createContext(null);

let nextId = 1;

/**
 * Holds the current AI+ conversation so it survives moving between pages.
 * Each turn: { id, question, mode, text, status, actions, sources, streaming, error }.
 */
export function ChatProvider({ userName, children }) {
  const [turns, setTurns] = useState([]);
  const turnsRef = useRef(turns);
  turnsRef.current = turns;
  const controllerRef = useRef(null);

  const update = (id, patch) =>
    setTurns((ts) => ts.map((t) => (t.id === id ? { ...t, ...(typeof patch === 'function' ? patch(t) : patch) } : t)));

  const ask = useCallback(
    (question, mode = '') => {
      const q = question.trim();
      if (!q || turnsRef.current.some((t) => t.streaming)) return;
      const id = nextId++;
      const history = turnsRef.current
        .filter((t) => t.text && !t.error)
        .map((t) => ({ question: t.question, answer: splitRelated(t.text).answer }));
      const controller = new AbortController();
      controllerRef.current = controller;
      setTurns((ts) => [...ts, { id, question: q, mode, text: '', status: 'Thinking…', actions: [], sources: [], streaming: true }]);

      postSse(
        '/api/assistant/chat',
        { message: q, mode, history, userName },
        {
          status: (d) => update(id, { status: d.message }),
          delta: (d) => update(id, (t) => ({ text: t.text + d.text })),
          action: (a) => update(id, (t) => ({ actions: [...t.actions, { ...a, state: 'pending' }] })),
          sources: (s) => update(id, { sources: s }),
          error: (d) => update(id, { error: d.message }),
          done: () => update(id, { streaming: false }),
        },
        controller.signal
      )
        .catch((e) => {
          if (e.name !== 'AbortError') update(id, { error: 'Could not reach the Fleetboard server.' });
        })
        .finally(() => update(id, { streaming: false }));
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [userName]
  );

  const stop = () => controllerRef.current?.abort();

  const reset = () => {
    controllerRef.current?.abort();
    setTurns([]);
  };

  const updateAction = (turnId, actionId, patch) =>
    update(turnId, (t) => ({ actions: t.actions.map((a) => (a.id === actionId ? { ...a, ...patch } : a)) }));

  return (
    <ChatContext.Provider value={{ turns, ask, stop, reset, updateAction, busy: turns.some((t) => t.streaming) }}>
      {children}
    </ChatContext.Provider>
  );
}

export function useChat() {
  return useContext(ChatContext);
}
