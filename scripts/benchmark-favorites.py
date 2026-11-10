"""Favorite query benchmark. Requires pymysql; uses a disposable database."""
import concurrent.futures
import json
import os
from pathlib import Path
import re
import statistics
import threading
import time
import xml.etree.ElementTree as ET

import pymysql

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = 'vibe_favorite_bench_' + str(int(time.time()))
PASSWORD = os.environ['VIBE_TEST_MYSQL_PASSWORD']
WORKERS, REQUESTS, ROUNDS = 8, 20, 3
SONGS, USERS, FAVORITES_PER_USER = 2000, 20, 100
OUT = ROOT / '.local/benchmarks'
OUT.mkdir(parents=True, exist_ok=True)

def connect(database=None):
    return pymysql.connect(host='127.0.0.1', port=23306, user='root', password=PASSWORD,
                           database=database, charset='utf8mb4', autocommit=True)

xml = ET.parse(ROOT / 'vibe-music-backend/vibe-music-server/src/main/resources/mapper/SongMapper.xml')
node = xml.find("select[@id='getUserFavoriteSongs']")
assert node is not None

def render(values):
    sql = node.text or ''
    for child in node:
        field = child.attrib.get('test', '').split()[0]
        if values.get(field) is not None and str(values[field]).strip():
            sql += ''.join(child.itertext())
        sql += child.tail or ''
    params = []
    def replace(match):
        params.append(values[match[1]])
        return '%s'
    return re.sub(r'#\{(\w+)\}', replace, sql.replace('%', '%%')).strip(), params

def query(conn, sql, params=()):
    with conn.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall()

def chain(conn, user, optimized, offset=0, filters=None):
    values = dict(userId=user, **(filters or {}))
    sql, params = render(values)
    if not optimized:
        ids = [row[0] for row in query(conn, 'SELECT song_id FROM tb_user_favorite WHERE user_id=%s AND type=0 ORDER BY create_time DESC', (user,))]
        if not ids:
            return 0, ()
        # Correct the original user scoping bug for an equivalent-results baseline.
        sql = sql.replace('WHERE f.user_id = %s AND f.type = 0',
                          'WHERE f.user_id = %s AND f.type = 0 AND s.id IN (' + ','.join(['%s']*len(ids)) + ')')
        params = [params[0], *ids, *params[1:]]
    count_sql = re.sub(r'ORDER BY.*$', '', sql, flags=re.S)
    total = query(conn, 'SELECT COUNT(*) FROM (' + count_sql + ') counted', params)[0][0]
    rows = query(conn, sql + ' LIMIT %s OFFSET %s', [*params, 20, offset]) if total > offset else ()
    return total, rows

def seed(conn):
    statements = [
        'CREATE TABLE tb_artist(id BIGINT PRIMARY KEY, name VARCHAR(100))',
        '''CREATE TABLE tb_song(id BIGINT PRIMARY KEY, artist_id BIGINT, name VARCHAR(100), album VARCHAR(100),
           duration VARCHAR(20), cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE, KEY(artist_id))''',
        '''CREATE TABLE tb_user_favorite(id BIGINT PRIMARY KEY, user_id BIGINT, type INT, song_id BIGINT,
           playlist_id BIGINT NULL, create_time DATETIME, KEY(user_id), KEY(song_id), KEY(playlist_id))'''
    ]
    for sql in statements:
        query(conn, sql)
    with conn.cursor() as c:
        c.executemany('INSERT INTO tb_artist VALUES(%s,%s)', [(i, f'Artist{i}') for i in range(1,101)])
        for start in range(1,SONGS+1,1000):
            c.executemany('INSERT INTO tb_song VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',
                [(i, (i%100)+1, f'Song{i}', f'Album{i%30}', '180', 'cover', 'audio', '2026-01-01') for i in range(start,start+1000)])
        for user in range(1,USERS+1):
            c.executemany('INSERT INTO tb_user_favorite VALUES(%s,%s,0,%s,NULL,%s)',
                [((user-1)*FAVORITES_PER_USER+j+1, user, ((user*17+j)%SONGS)+1, f'2026-01-01 00:00:{j%60:02d}') for j in range(FAVORITES_PER_USER)])
    query(conn, 'ANALYZE TABLE tb_song, tb_user_favorite, tb_artist')

def correctness(conn):
    for user in [1,2,USERS,999]:
        for offset in [0,20,FAVORITES_PER_USER-20,FAVORITES_PER_USER+20]:
            assert chain(conn,user,False,offset) == chain(conn,user,True,offset)
    for filters in [{'songName':'Song1'}, {'artistName':'Artist1'}, {'album':'Album2'},
                    {'songName':'Song','artistName':'Artist1','album':'Album2'}, {'songName':'not-present'}]:
        assert chain(conn,1,False,filters=filters) == chain(conn,1,True,filters=filters)
    total, rows = chain(conn,1,True)
    assert total == FAVORITES_PER_USER and len(set(r[0] for r in rows)) == len(rows) == 20
    # Reproduce original unscoped join: other users' favorites multiply rows.
    legacy = query(conn, '''SELECT COUNT(*) FROM tb_song s LEFT JOIN tb_user_favorite u ON s.id=u.song_id
        WHERE s.id IN (SELECT song_id FROM tb_user_favorite WHERE user_id=1 AND type=0)''')[0][0]
    assert legacy > total
    # Another user must not affect user 1's ordering, which is checked against user 1's own rows.
    expected = query(conn, 'SELECT song_id FROM tb_user_favorite WHERE user_id=1 AND type=0 ORDER BY create_time DESC,id DESC LIMIT 20')
    assert tuple((r[0],) for r in rows) == expected
    return {'expected_total':total,'original_unscoped_total':legacy,'checks':'users, filters, empty and out-of-range pages, stable ordering'}

def benchmark(label, optimized):
    latencies, rounds = [], []
    for round_no in range(ROUNDS):
        began=[]
        barrier=threading.Barrier(WORKERS, action=lambda: began.append(time.perf_counter()))
        def worker(index):
            conn=connect(SCHEMA)
            try:
                for n in range(3): chain(conn, (index*31+n)%USERS+1, optimized)
                barrier.wait(timeout=60)
                samples=[]
                for n in range(REQUESTS):
                    start=time.perf_counter()
                    total, rows=chain(conn, (index*31+n+round_no*13)%USERS+1, optimized)
                    assert total == FAVORITES_PER_USER and len(rows) == 20
                    samples.append((time.perf_counter()-start)*1000)
                return samples
            finally: conn.close()
        # Throughput uses the synchronized request window, excluding connection setup/warmup.
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as executor:
            results=list(executor.map(worker,range(WORKERS)))
        elapsed=time.perf_counter()-began[0]
        samples=[x for group in results for x in group]
        latencies.extend(samples)
        rounds.append({'mean_ms':statistics.mean(samples),'p95_ms':sorted(samples)[int(len(samples)*0.95)-1],
                       'requests_per_second':len(samples)/elapsed})
    result={'label':label,'requests':len(latencies),'mean_ms':statistics.mean(latencies),
            'p95_ms':sorted(latencies)[int(len(latencies)*0.95)-1],
            'median_round_rps':statistics.median(r['requests_per_second'] for r in rounds),'rounds':rounds}
    print(json.dumps(result),flush=True)
    return result

admin=connect()
created=False
try:
    assert re.fullmatch(r'vibe_favorite_bench_\d+',SCHEMA)
    query(admin,'CREATE DATABASE '+SCHEMA)
    created=True
    with connect(SCHEMA) as conn:
        seed(conn)
        report={'mysql':query(conn,'SELECT VERSION()')[0][0], 'songs':SONGS,'favorites':USERS*FAVORITES_PER_USER,
                'users':USERS,'favorites_per_user':FAVORITES_PER_USER,'concurrency':WORKERS,'page_size':20,
                'rounds':ROUNDS,'requests_per_worker_per_round':REQUESTS,
                'scope':'Python/PyMySQL SQL chain over TCP; no HTTP, Redis or connection setup in latency',
                'baseline':'Original ID-list chain with user scoping corrected for equivalent results',
                'correctness':correctness(conn),'results':[]}
        report['results'].append(benchmark('baseline_original_indexes',False))
        report['results'].append(benchmark('direct_original_indexes',True))
        query(conn,'CREATE INDEX idx_favorite_user_type_time ON tb_user_favorite(user_id,type,create_time,id)')
        query(conn,'ANALYZE TABLE tb_user_favorite')
        report['results'].append(benchmark('direct_composite_index',True))
        report['results'].append(benchmark('baseline_composite_index',False))
        sql,params=render({'userId':1})
        report['explain_analyze']=[row[0] for row in query(conn,'EXPLAIN ANALYZE '+sql+' LIMIT 20',params)]
        path=OUT/'favorite-query-results.json'
        path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print('REPORT '+str(path),flush=True)
finally:
    if created:
        assert re.fullmatch(r'vibe_favorite_bench_\d+',SCHEMA)
        query(admin,'DROP DATABASE '+SCHEMA)
    admin.close()
