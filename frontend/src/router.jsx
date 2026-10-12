import { useEffect, useState } from 'react';

const EVENT = 'fleetboard:navigate';

/** Client-side navigation with the History API. */
export function navigate(to, { replace = false } = {}) {
  if (to === window.location.pathname + window.location.search) return;
  if (replace) window.history.replaceState({}, '', to);
  else window.history.pushState({}, '', to);
  window.dispatchEvent(new Event(EVENT));
  window.scrollTo(0, 0);
}

/** Current { path, query } that re-renders on navigation and back/forward. */
export function useRoute() {
  const read = () => ({ path: window.location.pathname, query: new URLSearchParams(window.location.search) });
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const update = () => setRoute(read());
    window.addEventListener('popstate', update);
    window.addEventListener(EVENT, update);
    return () => {
      window.removeEventListener('popstate', update);
      window.removeEventListener(EVENT, update);
    };
  }, []);
  return route;
}

/** An <a> that navigates without a page load. */
export function Link({ to, children, className, onClick, ...rest }) {
  return (
    <a
      href={to}
      className={className}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        onClick?.(e);
        navigate(to);
      }}
      {...rest}
    >
      {children}
    </a>
  );
}
