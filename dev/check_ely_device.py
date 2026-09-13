#!/usr/bin/env python3
"""Public-client preflight, without login/password or logging authorization codes."""
import json
import urllib.parse
import urllib.request
import urllib.error

BASE = 'https://account.ely.by/api/oauth2/v1/'


def request(endpoint, fields, post=True):
    form = urllib.parse.urlencode(fields).encode()
    url = BASE + endpoint + ('' if post else '?' + form.decode())
    req = urllib.request.Request(url, data=form if post else None,
                                 headers={'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return response.status, json.loads(response.read(1048576))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read(65536))


def main():
    status, device = request('devicecode', {'client_id': 'deobso', 'scope': 'account_info minecraft_server_session offline_access'})
    if status != 200:
        raise RuntimeError(f'Device initialization returned HTTP {status}')
    uri = urllib.parse.urlsplit(device['verification_uri'])
    print(f'::notice title=Ely device metadata::scheme={uri.scheme}; host={uri.hostname}; path={uri.path}; interval={device.get("interval")}; expires_in={device.get("expires_in")}')
    assert device['verification_uri'] == 'https://account.ely.by/code', 'verification URI mismatch'
    assert device['interval'] > 0 and device['expires_in'] > 0
    status, validated = request('validate', {'user_code': device['user_code']}, False)
    assert status == 200, f'Browser validation returned HTTP {status}'
    print('::notice title=Ely browser validation::HTTP ' + str(status) + '; client fields: ' + ','.join(validated.get('client', {}).keys()))
    assert validated['client']['id'] == 'deobso', 'client ID mismatch'
    # No consent is granted and no access/refresh tokens are requested here.
    print('Public Ely.by deobso device initialization and browser validation: PASS')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Only the exception class is printed: arbitrary response bodies/codes stay private.
        detail = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print('::error title=Ely.by preflight::Device authorization preflight failed: ' + detail)
        raise SystemExit(1)
