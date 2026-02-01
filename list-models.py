import json
import urllib.request

props = {}
with open('local.properties','r') as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, v = line.split('=', 1)
        props[k] = v

key = props.get('OPENAI_API_KEY')
if not key:
    raise SystemExit('OPENAI_API_KEY missing in local.properties')

req = urllib.request.Request(
    'https://api.openai.com/v1/models',
    headers={'Authorization': 'Bearer {}'.format(key)}
)
with urllib.request.urlopen(req) as resp:
    data = json.load(resp)

models = sorted(m.get('id','') for m in data.get('data', []) if m.get('id'))
print("\n".join(models))
