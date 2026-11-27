"""Checks upgrade preservation using the isolated CI MySQL service only."""
import os
import subprocess
from pathlib import Path

if os.environ.get('SWS_INTEGRATION_TEST') != '1':
    raise SystemExit('Requires an isolated integration database')
root = Path(__file__).resolve().parents[1]

def run(sql):
    result = subprocess.run(['mysql', '--default-character-set=utf8mb4', '-h', '127.0.0.1', '-u', 'root', '--batch', '--raw', '--skip-column-names'], input=sql, text=True, capture_output=True, check=True)
    return result.stdout.strip()

db = 'sws_migration_check'
run(f'CREATE DATABASE {db} CHARACTER SET utf8mb4;')
legacy = (root/'src/test/resources/legacy-schema.sql').read_text(encoding='utf-8').replace('USE studyroom;', f'USE {db};')
run(legacy)
run(f'''USE {db};
UPDATE `user` SET credit_score=90 WHERE username='room_admin';
INSERT INTO credit_ledger(user_id,delta_score,reason,create_time,event_no)
SELECT user_id,-10,'legacy','2026-11-20 12:34:56','legacy-event' FROM `user` WHERE username='room_admin';
''')
migration = (root/'db/migrate-unified-fields.sql').read_text(encoding='utf-8').replace('USE studyroom;', f'USE {db};')
run(migration)
run(migration)
assert run(f"USE {db}; SELECT credit_score FROM `user` WHERE username='room_admin';") == '90', 'historical credit changed'
assert run(f"USE {db}; SELECT event_id,delta_score,created_at,requested_delta,score_after FROM credit_ledger WHERE event_id='legacy-event';") == 'legacy-event\t-10\t2026-11-20 12:34:56\tNULL\tNULL', 'historical ledger changed'
assert run(f"SELECT column_default FROM information_schema.columns WHERE table_schema='{db}' AND table_name='user' AND column_name='credit_score';") == '300', 'new-user default incorrect'
print('PASS legacy column rename, preserved credit/ledger/time, nullable historical snapshots, new default and repeatable migration')
