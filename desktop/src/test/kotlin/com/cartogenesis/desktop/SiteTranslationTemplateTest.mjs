import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {renderTemplate, validateCatalog} from '../../../../../../../site/i18n/template.mjs';

const root = new URL('../../../../../../../', import.meta.url);
const template = readFileSync(new URL('site/index.html', root), 'utf8');
const english = JSON.parse(readFileSync(new URL('site/i18n/en.json', root), 'utf8'));

test('English rendering preserves every byte except translation markers', () => {
  validateCatalog(english, english);
  assert.equal(renderTemplate(template, english.strings), template.replace(/\sdata-i18n(?:-[\w-]+)?="[^"]+"/g, ''));
});

test('missing, extra and blank translations are rejected', () => {
  const missing = structuredClone(english);
  delete missing.strings['opening.h1.text'];
  assert.throws(() => validateCatalog(english, missing), /keys differ/);
  const extra = structuredClone(english);
  extra.strings.extra = 'Extra';
  assert.throws(() => validateCatalog(english, extra), /keys differ/);
  const blank = structuredClone(english);
  blank.strings['opening.h1.text'] = ' ';
  assert.throws(() => validateCatalog(english, blank), /empty translation/);
});

test('repeated roadmap releases preserve every row', () => {
  const expected = {};
  for (const line of readFileSync(new URL('ROADMAP.md', root), 'utf8').split(/\r?\n/)) {
    const cells = line.split('|').slice(1, -1).map(cell => cell.trim());
    if (cells.length !== 2 || cells[0] === 'Release' || /^-+$/.test(cells[0])) continue;
    const release = cells[0].replace('(current)', '').trim();
    (expected[release] ||= []).push(cells[1]);
  }
  assert.deepEqual(english.roadmap, expected);
  assert.ok(english.roadmap['3.x'].length > 1);
  const incomplete = structuredClone(english);
  incomplete.roadmap['3.x'].pop();
  assert.throws(() => validateCatalog(english, incomplete), /Roadmap rows/);
});

test('translations cannot change links, code or dynamic placeholders', () => {
  const strings = {...english.strings};
  strings['opening.band-seed.text'] = strings['opening.band-seed.text'].replace('/app/?seed=1', '/elsewhere/');
  assert.throws(() => renderTemplate(template, strings), /changes markup/);
  const catalog = structuredClone(english);
  catalog.strings['script.step'] = 'Step {step}';
  assert.throws(() => validateCatalog(english, catalog), /changes placeholders/);
  assert.throws(() => renderTemplate('<p data-i18n="example">Run <code>apt upgrade</code>.</p>',
    {example: 'Run <code>apt install</code>.'}), /changes code/);
});

test('unsafe attribute text and nested translation regions are rejected', () => {
  assert.throws(() => renderTemplate('<img alt="Map" data-i18n-alt="map">', {map: '\" onload=\"bad'}), /Unsafe attribute/);
  assert.throws(() => renderTemplate('<p data-i18n="outer"><span data-i18n="inner">Text</span></p>',
    {outer: 'Text', inner: 'Text'}), /Nested translation/);
});
