import json
from pathlib import Path
from jsonschema import Draft202012Validator
root=Path(__file__).resolve().parents[1]
schema=json.loads((root/'schemas/mission.schema.json').read_text(encoding='utf-8'))
validator=Draft202012Validator(schema)
files=list((root/'app/src/main/assets/missions').glob('*.json'))
for file in files:
 m=json.loads(file.read_text(encoding='utf-8'));validator.validate(m)
 ids={r['id'] for r in m['initialResources']}
 assert len(ids)==len(m['initialResources']),file
 for c in m['reference']:
  if c['op']=='create':ids.add(c['id'])
  elif c['op']=='image':ids.add(c['args']['image'])
 for o in m['objectives']+m['constraints']:
  assert o['resource'] in ids,(file,o)
 for r in m['initialResources']:
  for k,v in r['attrs'].items():
   if k in ['vpc','subnet','sg','ami','instance','role']:assert v in ids,(file,k,v)
catalog=json.loads((root/'app/src/main/assets/catalog.json').read_text(encoding='utf-8'))
assert len(catalog['missions'])==60
assert len({m['id'] for m in catalog['missions']})==60
assert sum(m['playable'] for m in catalog['missions'])==len(files)==12
print('PASS: 12 playable mission schemas/references; 60 catalog entries, 48 explicitly planned.')
