#!/usr/bin/env python3
"""Optional Homebrew MySQL 8 isolated demo instance. Never touches the existing server."""
import os,secrets,shutil,subprocess,time,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
runtime=root/'.runtime';runtime.mkdir(exist_ok=True)
data=runtime/'mysql-data';data.mkdir(exist_ok=True)
mysql=shutil.which('mysql');mysqld=shutil.which('mysqld');admin=shutil.which('mysqladmin')
if not mysql or not mysqld:sys.exit('请先安装 MySQL 8：brew install mysql@8.4，并将其 bin 加入 PATH')
sock=runtime/'mysql.sock';config=runtime/'mysql-root.cnf'
if '--stop' in sys.argv:
 if not config.exists():sys.exit('未找到本项目的 MySQL 凭据')
 subprocess.run([admin,f'--defaults-extra-file={config}','shutdown'],check=True);sys.exit(0)
if not (data/'mysql').exists():
 subprocess.run([mysqld,'--no-defaults','--initialize-insecure',f'--datadir={data}'],check=True,stdout=(runtime/'mysql-init.log').open('w'),stderr=subprocess.STDOUT)
if not sock.exists():
 log=(runtime/'mysql-start.log').open('a')
 subprocess.Popen([mysqld,'--no-defaults',f'--datadir={data}','--port=3307','--bind-address=127.0.0.1',f'--socket={sock}',f'--pid-file={runtime/"mysql.pid"}','--mysqlx=OFF',f'--log-error={runtime/"mysql.log"}'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
for _ in range(60):
 if sock.exists():break
 time.sleep(.25)
else:sys.exit('MySQL 未能启动，请检查 .runtime/mysql.log 和3307端口')
if not config.exists():
 if (root/'.env').exists():sys.exit('已有 .env，未覆盖；请先确认其中配置或使用 Docker Compose。')
 rootpw=secrets.token_hex(24);apppw=secrets.token_hex(24)
 sql=f"CREATE DATABASE IF NOT EXISTS campus_shuttle CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'shuttle'@'127.0.0.1' IDENTIFIED BY '{apppw}'; GRANT ALL ON campus_shuttle.* TO 'shuttle'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '{rootpw}';"
 subprocess.run([mysql,'--no-defaults','--protocol=SOCKET',f'--socket={sock}','-u','root'],input=sql,text=True,check=True)
 config.write_text(f'[client]\nuser=root\npassword={rootpw}\nsocket={sock}\n');config.chmod(0o600)
 env=root/'.env';env.write_text(f'MYSQL_ROOT_PASSWORD={rootpw}\nDB_PASSWORD={apppw}\nDB_USERNAME=shuttle\nDB_PORT=3307\nDB_URL="jdbc:mysql://127.0.0.1:3307/campus_shuttle?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"\nSPRING_PROFILES_ACTIVE=demo\n');env.chmod(0o600)
subprocess.run([mysql,f'--defaults-extra-file={config}','-N','-e','SELECT VERSION()'],check=True)
print('项目专用 MySQL 已就绪：127.0.0.1:3307 / campus_shuttle；凭据仅存 .env 和 .runtime（均已忽略）。')
