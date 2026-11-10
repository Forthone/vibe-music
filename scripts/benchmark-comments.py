"""Run with pymysql and VIBE_TEST_MYSQL_PASSWORD; uses a disposable MySQL database."""
import concurrent.futures
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
import pymysql

ROOT=Path(__file__).resolve().parents[1]
BASE='b848f733942d44a97827cbdd46158e2e0452e1a8'
SCHEMA='vibe_comment_bench_'+str(int(time.time()))
WORKERS, REQUESTS, ROUNDS=8,10,3
def connect(db=None):
    return pymysql.connect(host='127.0.0.1',port=23306,user='root',password=os.environ['VIBE_TEST_MYSQL_PASSWORD'],
        database=db,charset='utf8mb4',autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def query(c,sql,args=()):
    with c.cursor() as cursor:
        cursor.execute(sql,args)
        return cursor.fetchall()
def select(xml,name):
    node=ET.fromstring(xml).find("select[@id='"+name+"']")
    return re.sub(r'#\{\w+\}','%s',''.join(node.itertext())).strip()
relative='vibe-music-backend/vibe-music-server/src/main/resources/mapper/'
old_xml=subprocess.check_output(['git','-C',str(ROOT),'show',BASE+':'+relative+'SongMapper.xml']).decode('utf-8')
OLD=select(old_xml,'getSongDetailById')
DETAIL=select((ROOT/relative/'SongMapper.xml').read_text(encoding='utf-8'),'getSongDetailById')
COMMENTS=select((ROOT/relative/'CommentMapper.xml').read_text(encoding='utf-8'),'getSongComments')
COUNT='SELECT COUNT(*) AS total FROM tb_comment WHERE song_id=%s AND type=0'
def page(c,song=1,offset=0):
    total=query(c,COUNT,(song,))[0]['total']
    return total,query(c,COMMENTS+' LIMIT 20 OFFSET %s',(song,offset))
def comment(row):
    return {k:(str(row[k])[:10] if k=='createTime' else row[k]) for k in
        ('commentId','username','userAvatar','content','createTime','likeCount')}
def response(data): return {'code':0,'message':'操作成功','data':data}
def chain(c,optimized):
    if optimized:
        detail=query(c,DETAIL,(1,))[0]
        total,rows=page(c)
        comments=[comment(r) for r in rows]
    else:
        rows=query(c,OLD,(1,))
        detail={k:rows[0][k] for k in ('songId','songName','artistName','album','lyric','duration','songCoverUrl','audioUrl','releaseTime')}
        total=len(rows)
        comments=[comment(r) for r in rows]
    detail['coverUrl']=detail.pop('songCoverUrl')
    detail['likeStatus']=None
    detail['comments']=[] if optimized else comments
    payload=[response(detail),response({'total':total,'items':comments})] if optimized else [response(detail)]
    size=sum(len(json.dumps(p,ensure_ascii=False,default=str,separators=(',',':')).encode('utf-8')) for p in payload)
    assert total==2000 and len(comments)==(20 if optimized else 2000)
    return size
def measure(optimized):
    samples=[];rounds=[];sizes=[]
    for _ in range(ROUNDS):
        began=[]
        barrier=threading.Barrier(WORKERS,action=lambda:began.append(time.perf_counter()))
        def worker(_):
            with connect(SCHEMA) as c:
                chain(c,optimized)
                barrier.wait(timeout=60)
                local=[]
                for _ in range(REQUESTS):
                    start=time.perf_counter();size=chain(c,optimized)
                    local.append(((time.perf_counter()-start)*1000,size))
                return local
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            results=list(pool.map(worker,range(WORKERS)))
        elapsed=time.perf_counter()-began[0]
        latencies=[s[0] for group in results for s in group]
        sizes.extend(s[1] for group in results for s in group)
        samples.extend(latencies)
        rounds.append({'mean_ms':statistics.mean(latencies),'p95_ms':sorted(latencies)[int(len(latencies)*.95)-1],
                       'requests_per_second':len(latencies)/elapsed})
    result={'mode':'detail_and_first_comment_page' if optimized else 'detail_with_all_comments',
        'requests':len(samples),'mean_ms':statistics.mean(samples),'p95_ms':sorted(samples)[int(len(samples)*.95)-1],
        'median_round_rps':statistics.median(r['requests_per_second'] for r in rounds),
        'json_bytes':sizes[0],'rounds':rounds}
    print(json.dumps(result),flush=True)
    return result

admin=connect();created=False
try:
    assert re.fullmatch(r'vibe_comment_bench_\d+',SCHEMA)
    query(admin,'CREATE DATABASE '+SCHEMA);created=True
    with connect(SCHEMA) as c:
        for sql in [
            'CREATE TABLE tb_artist(id BIGINT PRIMARY KEY,name VARCHAR(50))',
            '''CREATE TABLE tb_song(id BIGINT PRIMARY KEY,name VARCHAR(50),album VARCHAR(50),lyric TEXT,duration VARCHAR(10),
            cover_url VARCHAR(100),audio_url VARCHAR(100),release_time DATE,artist_id BIGINT)''',
            'CREATE TABLE tb_user(id BIGINT PRIMARY KEY,username VARCHAR(50),user_avatar VARCHAR(100))',
            '''CREATE TABLE tb_comment(id BIGINT PRIMARY KEY,song_id BIGINT,type INT,user_id BIGINT,content VARCHAR(255),
            create_time DATETIME,like_count BIGINT,KEY(song_id),KEY(user_id))''',
            "INSERT INTO tb_artist VALUES(1,'测试歌手')",
            "INSERT INTO tb_song VALUES(1,'测试歌曲','测试专辑','测试歌词', '180','cover','audio','2026-01-01',1)",
            "INSERT INTO tb_user VALUES(1,'测试用户','avatar')"]:
            query(c,sql)
        with c.cursor() as cursor:
            cursor.executemany('INSERT INTO tb_comment VALUES(%s,1,0,1,%s,%s,0)',
                [(i,f'测试评论{i}，这首歌很好听。','2026-01-01 12:00:00') for i in range(1,2001)])
        query(c,'ANALYZE TABLE tb_comment,tb_song,tb_user,tb_artist')
        ids=[]
        for offset in range(0,2000,20):
            total,rows=page(c,offset=offset)
            assert total==2000 and len(rows)==20
            ids.extend(r['commentId'] for r in rows)
        assert ids==list(range(2000,0,-1))
        assert page(c,offset=2000)==(2000,())
        assert page(c,song=999)==(0,())
        query(c,'UPDATE tb_comment SET type=1 WHERE id=1')
        assert page(c)[0]==1999
        query(c,'UPDATE tb_comment SET type=0,song_id=2 WHERE id=1')
        assert page(c)[0]==1999
        query(c,'UPDATE tb_comment SET song_id=1 WHERE id=1')
        report={'measured_at_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
            'mysql':query(c,'SELECT VERSION() AS v')[0]['v'],'comments':2000,'concurrency':WORKERS,
            'page_size':20,'rounds':ROUNDS,'requests_per_worker_per_round':REQUESTS,
            'scope':'PyMySQL SQL + response assembly/JSON serialization; no HTTP, Redis, browser rendering or connection setup',
            'fixture':'Synthetic comments on one song; existing single-column indexes; warmed connections and data',
            'baseline_commit':BASE,'checks':'100 pages, stable order, total, no duplicate/missing IDs, empty page, song/type isolation',
            'results':[measure(False),measure(True)]}
        out=ROOT/'docs/benchmarks/comment-pagination-results.json'
        out.parent.mkdir(parents=True,exist_ok=True)
        out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print('REPORT '+str(out),flush=True)
finally:
    if created:
        assert re.fullmatch(r'vibe_comment_bench_\d+',SCHEMA)
        query(admin,'DROP DATABASE '+SCHEMA)
    admin.close()
