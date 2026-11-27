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

# The original booking-only fixtures used manual primary keys and had no account credentials.
db2='sws_booking_fixture_check'
run(f'''CREATE DATABASE {db2} CHARACTER SET utf8mb4;
USE {db2};
CREATE TABLE `user`(user_id BIGINT PRIMARY KEY,account_status VARCHAR(24) NOT NULL DEFAULT 'NORMAL',credit_score INT NOT NULL DEFAULT 300,credit_suspended_until DATETIME NULL) ENGINE=InnoDB;
CREATE TABLE room(room_id BIGINT PRIMARY KEY,listing_status VARCHAR(24) NOT NULL,maintenance_status VARCHAR(24) NOT NULL,opening_hours VARCHAR(24) NOT NULL) ENGINE=InnoDB;
INSERT INTO `user`(user_id) VALUES (9001),(9002),(9003);
INSERT INTO room VALUES (9101,'LISTED','NORMAL','08:00-22:00'),(9102,'LISTED','NORMAL','08:00-22:00');
''')
for file in ['prepare-legacy-account-rooms.sql','prepare-legacy-account-rooms.sql','account-rooms.sql','booking-rules.sql','usage-feedback.sql','migrate-unified-fields.sql','migrate-unified-fields.sql']:
    run((root/'db'/file).read_text(encoding='utf-8').replace('USE studyroom;',f'USE {db2};'))
assert run(f"USE {db2}; SELECT credit_score,account_status,username FROM `user` WHERE user_id=9001;") == '300\tACTIVE\tNULL', 'old fixture user not preserved'
assert run(f"USE {db2}; SELECT COUNT(*) FROM room WHERE room_id=9101 AND room_name='研讨间 9101' AND opening_hours='08:00-22:00';") == '1', 'old room not preserved'
assert run(f"USE {db2}; SELECT COUNT(*) FROM `user` WHERE username='room_admin' AND role='ROOM_ADMIN';") == '1', 'administrator cannot be seeded after upgrade'
run(f"USE {db2}; INSERT INTO `user`(username,password_salt,password_hash) VALUES ('new_after_upgrade','0123456789abcdef0123456789abcdef',REPEAT('0',64));")
assert int(run(f"USE {db2}; SELECT user_id FROM `user` WHERE username='new_after_upgrade';")) > 9003, 'auto-generated user IDs not restored'
print('PASS booking-only schema upgrade, preserved fixture IDs/credit/opening hours, safe uncredentialed legacy users and restored generated IDs')
