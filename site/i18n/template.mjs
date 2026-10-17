/** Keeps markup and code fixed while replacing the template's explicitly keyed text. */
const voidElements = new Set('area base br col embed hr img input link meta param source track wbr'.split(' '));
const tokens = /<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?<\/script>|<style\b[^>]*>[\s\S]*?<\/style>|<![^>]*>|<\/?[a-zA-Z][^>]*>/g;
const markers = /\sdata-i18n(?:-[\w-]+)?="[^"]+"/g;

function requireString(strings, key) {
  const value = strings[key];
  if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing or empty translation: ${key}`);
  return value;
}

function markupOf(fragment) {
  return (fragment.match(/<[^>]+>/g) || []).sort();
}

function requireSameMarkup(original, translated, key) {
  if (JSON.stringify(markupOf(original)) !== JSON.stringify(markupOf(translated))) {
    throw new Error(`Translation changes markup: ${key}`);
  }
  const code = fragment => (fragment.match(/<code\b[^>]*>[\s\S]*?<\/code>/g) || []).sort();
  if (JSON.stringify(code(original)) !== JSON.stringify(code(translated))) {
    throw new Error(`Translation changes code: ${key}`);
  }
}

/** Renders marked HTML text and attributes, preserving surrounding whitespace and all unmarked bytes. */
export function renderTemplate(template, strings) {
  const stack = [], edits = [];
  for (const match of template.matchAll(tokens)) {
    const raw = match[0];
    if (raw.startsWith('<!')) continue;
    const tag = raw.match(/^<\/?([\w-]+)/)[1].toLowerCase();
    if (raw.startsWith('</')) {
      const node = stack.pop();
      if (!node || node.tag !== tag) throw new Error(`Unbalanced template element: ${tag}`);
      if (node.key) {
        const original = template.slice(node.content, match.index);
        const translated = requireString(strings, node.key);
        requireSameMarkup(original, translated, node.key);
        edits.push({start: node.content, end: match.index,
          value: original.match(/^\s*/)[0] + translated + original.match(/\s*$/)[0]});
      }
      continue;
    }
    const opening = raw.slice(0, raw.indexOf('>') + 1);
    const key = opening.match(/\sdata-i18n="([^"]+)"/)?.[1];
    if (key && stack.some(node => node.key)) throw new Error(`Nested translation key: ${key}`);
    let renderedOpening = opening;
    for (const attribute of opening.matchAll(/\sdata-i18n-([\w-]+)="([^"]+)"/g)) {
      const [, name, attributeKey] = attribute;
      const value = requireString(strings, attributeKey);
      if (/[<>\"]/.test(value)) throw new Error(`Unsafe attribute translation: ${attributeKey}`);
      const pattern = new RegExp(`(\\s${name}=")[^"]*(")`);
      if (!pattern.test(renderedOpening)) throw new Error(`Missing translated attribute: ${name}`);
      renderedOpening = renderedOpening.replace(pattern, (_, prefix, suffix) => prefix + value + suffix);
    }
    renderedOpening = renderedOpening.replace(markers, '');
    if (renderedOpening !== opening) edits.push({start: match.index, end: match.index + opening.length, value: renderedOpening});
    if (tag !== 'script' && tag !== 'style' && !voidElements.has(tag) && !opening.endsWith('/>')) {
      stack.push({tag, key, content: match.index + opening.length});
    }
  }
  if (stack.length) throw new Error('Unclosed template elements');
  let result = template;
  for (const edit of edits.sort((left, right) => right.start - left.start)) {
    result = result.slice(0, edit.start) + edit.value + result.slice(edit.end);
  }
  return result;
}

/** Rejects missing keys and mismatched roadmap groups before any translated page is written. */
export function validateCatalog(source, translated) {
  for (const group of ['strings', 'roadmap', 'glossary']) {
    if (!translated[group] || JSON.stringify(Object.keys(source[group]).sort()) !== JSON.stringify(Object.keys(translated[group]).sort())) {
      throw new Error(`Catalog keys differ: ${group}`);
    }
    for (const key of Object.keys(source[group])) {
      if (group === 'roadmap') {
        const rows = translated.roadmap[key];
        if (!Array.isArray(rows) || rows.length !== source.roadmap[key].length || rows.some(row => typeof row !== 'string' || !row.trim())) {
          throw new Error(`Roadmap rows differ or are empty: ${key}`);
        }
      } else {
        const value = requireString(translated[group], key);
        const placeholders = text => (text.match(/\{[a-zA-Z]+\}|__[A-Z_]+__/g) || []).sort();
        if (JSON.stringify(placeholders(source[group][key])) !== JSON.stringify(placeholders(value))) {
          throw new Error(`Translation changes placeholders: ${key}`);
        }
      }
    }
  }
}
