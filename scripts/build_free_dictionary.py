"""Build the offline dictionary. Run after fetching source archives into build/english-sources.
Sources and SHA-256s are recorded in the distributed manifest; no paid APIs or AI.
"""
from pathlib import Path
import csv, gzip, hashlib, io, json, re, shutil, sqlite3, zipfile

root = Path(__file__).resolve().parents[1]
src = root / 'build/english-sources'
out = root / 'app/src/main/assets/dictionary'
(out / 'licenses').mkdir(parents=True, exist_ok=True)
# Keep everyday multiword expressions without bundling hundreds of thousands of technical names.
common = set()
with (src / 'ecdict.csv').open(encoding='utf-8-sig', newline='') as f:
    for row in csv.DictReader(f):
        if any(0 < int(row.get(k) or 0) <= 5000 for k in ['bnc','frq']): common.add(row['word'].lower())
common |= {'sb','sth',"one's",'a','i'}
words = {}
aliases = {}
with (src / 'ecdict.csv').open(encoding='utf-8-sig', newline='') as f:
    for row in csv.DictReader(f):
        word = row['word'].strip().lower()
        rank = lambda key: 0 < int(row.get(key) or 0) <= 50000
        phrase = all(t in common for t in word.split()) and ' ' in word and 2 <= len(word.split()) <= 5 and len(word) <= 60 and re.fullmatch(r"[a-z '-]+", word)
        if not (row.get('tag') or rank('bnc') or rank('frq') or phrase) or not word or len(word) > 80:
            continue
        words[word] = dict(word=word, phonetic=row.get('phonetic',''), translation=row.get('translation',''),
            partOfSpeech=row.get('pos',''), exchange=row.get('exchange',''), definitions=[], examples=[])
        for change in row.get('exchange','').split('/'):
            if ':' in change:
                kind, value = change.split(':', 1)
                if kind in {'p','d','i','3','r','t','s'}:
                    aliases.setdefault(value.lower(),word)

with zipfile.ZipFile(src / 'wordnet.zip') as z:
    (out / 'licenses/WordNet-LICENSE.txt').write_bytes(z.read('wordnet/LICENSE'))
    for filename, pos in [('noun','n.'),('verb','v.'),('adj','adj.'),('adv','adv.')]:
        for line in z.read('wordnet/data.' + filename).decode('utf-8').splitlines():
            if not line or not line[0].isdigit() or ' | ' not in line: continue
            meta, gloss = line.split(' | ',1)
            fields = meta.split()
            lemmas = [fields[4+i*2].replace('_',' ').lower() for i in range(int(fields[3],16))]
            examples = re.findall(r'"([^"]+)"', gloss)
            definition = re.sub(r'"[^"]+";?','',gloss).strip(' ;')
            for lemma in lemmas:
                if lemma not in words: continue
                w = words[lemma]
                if len(w['definitions']) < 5:
                    w['definitions'].append(dict(pos=pos, text=definition, source='WordNet 3.0'))
                forms = [lemma] + [x.split(':',1)[1] for x in w['exchange'].split('/') if ':' in x]
                for example in examples:
                    # A synset example may illustrate a synonym rather than this spelling.
                    if len(w['examples']) < 2 and any(re.search(r'(?<![a-z])'+re.escape(form)+r'(?![a-z])',example,re.I) for form in forms):
                        w['examples'].append(dict(english=example, chinese='', source='WordNet 3.0', url='https://wordnet.princeton.edu/'))

with zipfile.ZipFile(src / 'cmn-eng.zip') as z:
    for name in z.namelist():
        if name.lower().endswith('.txt') and 'about' in name.lower():
            (out / 'licenses/Tatoeba-README.txt').write_bytes(z.read(name))
    corpus = next(n for n in z.namelist() if n.endswith('cmn.txt'))
    pairs = [line.split('\t') for line in z.read(corpus).decode('utf-8-sig').splitlines()]
    # Prefer complete but short examples; retain original author attribution for BOTH sentences.
    pairs.sort(key=lambda p: abs(len(p[0].split()) - 9))
    found = {}
    for pair in pairs:
        if len(pair) < 3: continue
        english,chinese,credit = pair[:3]
        tokens = re.findall(r"[a-z]+(?:'[a-z]+)?",english.lower())
        if not 5 <= len(tokens) <= 24: continue
        candidates = set(tokens) | {aliases[t] for t in tokens if t in aliases}
        candidates |= {' '.join(tokens[i:i+n]) for n in range(2,6) for i in range(len(tokens)-n+1)}
        for word in candidates:
            if word not in words or len(found.get(word,[])) >= 3: continue
            rows = found.setdefault(word,[])
            if any(x['english'] == english for x in rows): continue
            ids = re.findall(r'#(\d+)',credit)
            rows.append(dict(english=english,chinese=chinese,source='Tatoeba · CC BY 2.0 · '+credit,
                url='https://tatoeba.org/en/sentences/show/'+ids[0] if ids else 'https://tatoeba.org/'))
    for word, examples in found.items():
        words[word]['examples'] = (examples + words[word]['examples'])[:4]

dbfile = src / 'free-dictionary-v1.db'
if dbfile.exists(): dbfile.unlink()
db = sqlite3.connect(dbfile)
db.executescript('CREATE TABLE words(word TEXT PRIMARY KEY, payload TEXT NOT NULL); CREATE TABLE aliases(alias TEXT PRIMARY KEY, word TEXT NOT NULL);')
db.executemany('INSERT INTO words VALUES (?,?)',[(word,json.dumps(data,ensure_ascii=False,separators=(',',':'))) for word,data in sorted(words.items())])
db.executemany('INSERT INTO aliases VALUES (?,?)',sorted(aliases.items()))
db.commit(); db.execute('VACUUM'); db.close()
with dbfile.open('rb') as f, (out/'free-dictionary-v1.db.pack').open('wb') as raw:
    with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as zipped: shutil.copyfileobj(f,zipped)
for name in ['ECDICT-LICENSE.txt','FSRS-LICENSE.txt']: shutil.copyfile(src/name,out/'licenses'/name)
manifest = dict(version=1, words=len(words), bilingualExampleWords=len(found),
    databaseSha256=hashlib.sha256(dbfile.read_bytes()).hexdigest(),
    sources=[dict(file=name,sha256=hashlib.sha256((src/name).read_bytes()).hexdigest())
        for name in ['ecdict.csv','wordnet.zip','cmn-eng.zip','fsrs.js']],
    urls=['https://github.com/skywind3000/ECDICT','https://wordnet.princeton.edu/',
        'https://www.manythings.org/anki/','https://tatoeba.org/','https://github.com/open-spaced-repetition/fsrs4anki'])
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(manifest,ensure_ascii=False,indent=2))
