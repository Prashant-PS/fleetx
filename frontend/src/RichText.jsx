/** Renders the light markdown used in answers: paragraphs, bullets, tables, **bold**, *italic*, [links](url). */
export default function RichText({ text }) {
  const blocks = [];
  let list = null;
  let table = null;
  text.split('\n').forEach((line, i) => {
    const trimmed = line.trim();
    if (/^\|.*\|$/.test(trimmed)) {
      list = null;
      const cells = trimmed.slice(1, -1).split('|').map((c) => c.trim());
      if (cells.every((c) => /^:?-{2,}:?$/.test(c))) return; // header separator row
      if (!table) {
        table = { type: 'table', head: cells, rows: [], key: i };
        blocks.push(table);
      } else {
        table.rows.push(cells);
      }
      return;
    }
    table = null;
    if (/^[-*•]\s+/.test(trimmed)) {
      if (!list) {
        list = { type: 'ul', items: [], key: i };
        blocks.push(list);
      }
      list.items.push(trimmed.replace(/^[-*•]\s+/, ''));
    } else {
      list = null;
      const heading = trimmed.match(/^(#{1,3})\s+(.*)$/);
      if (heading) blocks.push({ type: `h${heading[1].length}`, text: heading[2], key: i });
      else if (trimmed.startsWith('>')) blocks.push({ type: 'quote', text: trimmed.replace(/^>\s*/, ''), key: i });
      else if (trimmed) blocks.push({ type: 'p', text: trimmed, key: i });
    }
  });

  return blocks.map((b) => {
    if (b.type === 'ul') {
      return <ul key={b.key}>{b.items.map((it, j) => <li key={j}><Inline text={it} /></li>)}</ul>;
    }
    if (b.type === 'table') {
      return (
        <div className="table-wrap" key={b.key}>
          <table className="md-table">
            <thead>
              <tr>{b.head.map((c, j) => <th key={j}><Inline text={c} /></th>)}</tr>
            </thead>
            <tbody>
              {b.rows.map((r, j) => (
                <tr key={j}>{r.map((c, k) => (k === 0 ? <th key={k}><StatusCell text={c} /></th> : <td key={k}><StatusCell text={c} /></td>))}</tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }
    if (b.type === 'h1') return <h1 key={b.key}><Inline text={b.text} /></h1>;
    if (b.type === 'h2') return <h2 key={b.key}><Inline text={b.text} /></h2>;
    if (b.type === 'h3') return <h3 key={b.key}><Inline text={b.text} /></h3>;
    if (b.type === 'quote') return <blockquote key={b.key}><Inline text={b.text} /></blockquote>;
    return <p key={b.key}><Inline text={b.text} /></p>;
  });
}

const STATUS_CLASS = { D: 'st-d', P: 'st-p', A: 'st-a', NV: 'st-nv' };

function StatusCell({ text }) {
  const cls = STATUS_CLASS[text];
  if (cls) return <span className={`status ${cls}`}>{text}</span>;
  const severity = text.match(/^(HIGH|MEDIUM|LOW)( \(\d+\))?$/);
  if (severity) return <span className={`sev ${severity[1]}`}>{text.toLowerCase()}</span>;
  return <Inline text={text} />;
}

function Inline({ text }) {
  return text.split(/(\*\*[^*]+\*\*|\[[^\]]+\]\(https?:\/\/[^)\s]+\)|\*[^*\s][^*]*\*)/g).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) return <strong key={i}>{part.slice(2, -2)}</strong>;
    const link = part.match(/^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/);
    if (link) return <a key={i} href={link[2]} target="_blank" rel="noreferrer">{link[1]}</a>;
    if (part.startsWith('*') && part.endsWith('*') && part.length > 2) return <em key={i}>{part.slice(1, -1)}</em>;
    return part;
  });
}
