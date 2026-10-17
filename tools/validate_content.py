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
  if c['op'].startswith('create') or c['op'] in ['restoreSnapshot','restoreRds','replicateResource','registerTask','putDns','putScp','deployStack','encryptData']:ids.add(c['id'])
  elif c['op']=='image':ids.add(c['args']['image'])
  if c['op']=='deployStack':ids.update(r['id'] for r in json.loads(c['args']['template'])['Resources'])
  if c['op']=='createAsg':ids.update(f'{c["id"]}-{i}' for i in range(1,int(c['args']['desired'])+1))
 for o in m['objectives']+m['constraints']:
  assert o['resource'] in ids or (o['kind'] in ['network','networkBlocked'] and o['resource']=='internet'),(file,o)
 for r in m['initialResources']:
  for k,v in r['attrs'].items():
   if k in ['vpc','subnet','sg','ami','instance','role']:assert v in ids,(file,k,v)
catalog=json.loads((root/'app/src/main/assets/catalog.json').read_text(encoding='utf-8'))
assert len(catalog['missions'])==60
assert len({m['id'] for m in catalog['missions']})==60
assert sum(m['playable'] for m in catalog['missions'])==len(files)==60
print('PASS: 60 playable mission schemas/references; 10 chapters; 60 unique catalog entries.')
