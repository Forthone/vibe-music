"""Create the local bucket and two deployment-test objects using Python stdlib."""
import datetime
import hashlib
import hmac
import io
import json
import math
import os
import struct
import urllib.error
import urllib.request
import wave

ENDPOINT = 'http://127.0.0.1:9000'
BUCKET = 'vibe-music-data'
ACCESS = os.environ.get('MINIO_ROOT_USER', 'minioadmin')
SECRET = os.environ.get('MINIO_ROOT_PASSWORD', 'vibe_minio_local_2026')


def request(path, body=b'', query='', content_type='application/octet-stream'):
    now = datetime.datetime.now(datetime.timezone.utc)
    stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    digest = hashlib.sha256(body).hexdigest()
    headers = {'host': '127.0.0.1:9000', 'x-amz-content-sha256': digest, 'x-amz-date': stamp}
    names = ';'.join(sorted(headers))
    canonical = '\n'.join(['PUT', path, query, ''.join(k+':'+headers[k]+'\n' for k in sorted(headers)), names, digest])
    scope = day+'/us-east-1/s3/aws4_request'
    sign_text = '\n'.join(['AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()])
    key = ('AWS4'+SECRET).encode()
    for part in [day, 'us-east-1', 's3', 'aws4_request']:
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, sign_text.encode(), hashlib.sha256).hexdigest()
    headers['Authorization'] = f'AWS4-HMAC-SHA256 Credential={ACCESS}/{scope}, SignedHeaders={names}, Signature={signature}'
    headers['Content-Type'] = content_type
    req = urllib.request.Request(ENDPOINT+path+('?' + query if query else ''), data=body, headers=headers, method='PUT')
    try:
        with urllib.request.urlopen(req, timeout=20) as result:
            print(path, result.status)
    except urllib.error.HTTPError as error:
        if error.code != 409:
            raise RuntimeError(error.read().decode()) from error
        print(path, 'already exists')


if __name__ == '__main__':
    request('/'+BUCKET)
    policy = {'Version': '2012-10-17', 'Statement': [{'Effect': 'Allow', 'Principal': {'AWS': ['*']}, 'Action': ['s3:GetObject'], 'Resource': ['arn:aws:s3:::'+BUCKET+'/*']}]}
    request('/'+BUCKET, json.dumps(policy).encode(), query='policy=', content_type='application/json')
    svg = '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600"><rect width="600" height="600" fill="#202b41"/><circle cx="300" cy="250" r="110" fill="#c0fa70"/><text x="300" y="440" text-anchor="middle" font-family="sans-serif" font-size="40" fill="white">VIBE LOCAL TEST</text></svg>'
    request('/'+BUCKET+'/local-test/cover.svg', svg.encode(), content_type='image/svg+xml')
    output = io.BytesIO()
    with wave.open(output, 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(22050)
        frames = bytearray()
        for i in range(22050*5):
            envelope = min(i/1000, (22050*5-i)/1000, 1)
            frames.extend(struct.pack('<h', int(1800*envelope*math.sin(2*math.pi*440*i/22050))))
        audio.writeframes(frames)
    request('/'+BUCKET+'/local-test/playback-test.wav', output.getvalue(), content_type='audio/wav')
