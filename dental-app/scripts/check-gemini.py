"""Check the local Gemini key without printing credentials or using app data."""
import json
import urllib.error
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
values = {}
for line in (root / '.env').read_text(encoding='utf-8-sig').splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        name, value = line.split('=', 1)
        values[name.strip()] = value.strip().strip('\"\'')
key = values.get('GEMINI_API_KEY')
if not key:
    raise SystemExit('GEMINI_API_KEY is missing in the root .env file.')

def request(path, data=None):
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(
        'https://generativelanguage.googleapis.com/v1beta/' + path,
        data=payload,
        headers={'x-goog-api-key': key, 'Content-Type': 'application/json'},
    )
    try:
        with urllib.request.urlopen(req, timeout=40) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            details = json.loads(error.read()).get('error', {})
            message = details.get('message', 'Request failed').replace(key, '[redacted]')
            print(json.dumps({'http_status': error.code, 'status': details.get('status'), 'message': message}))
        except (ValueError, UnicodeError):
            print(json.dumps({'http_status': error.code}))
        raise SystemExit(1)
    except urllib.error.URLError:
        raise SystemExit('Network request failed; check access to the Gemini API.')

models = request('models?pageSize=1000')
available = {m['name'].removeprefix('models/') for m in models.get('models', []) if 'generateContent' in m.get('supportedGenerationMethods', [])}
preferred = [values.get('GEMINI_MODEL'), 'gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-2.5-flash-lite']
selected = next((name for name in preferred if name and name in available), None)
if not selected:
    print(json.dumps({'list_models': 'ok', 'preferred_model_available': False, 'available_text_models': sorted(available)}))
    raise SystemExit(2)
print(json.dumps({'list_models': 'ok', 'selected_model': selected}))
result = request(f'models/{selected}:generateContent', {
    'contents': [{'parts': [{'text': 'Reply only with OK.'}]}],
    'generationConfig': {'maxOutputTokens': 32},
})
parts = [part.get('text', '') for candidate in result.get('candidates', []) for part in candidate.get('content', {}).get('parts', [])]
print(json.dumps({'generate_content': 'ok', 'selected_model': selected, 'response': ''.join(parts), 'usage': result.get('usageMetadata', {})}))
