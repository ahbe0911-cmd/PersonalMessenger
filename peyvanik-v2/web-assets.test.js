import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import assets from './web-assets.js';
for(const name of ['index.html','style.css','app.js','icon.svg','keymaker.html','keymaker.js'])test('embedded asset matches source: '+name,async()=>{assert.equal(assets['/'+name].body,await readFile(new URL('./web/'+name,import.meta.url),'utf8'))});
