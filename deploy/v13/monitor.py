"""Independent health timer. Delivery reuses the installed ShuttleFlow mail sender."""
import importlib.util
import json
from pathlib import Path
import subprocess
import time
from urllib.request import urlopen

STATE = Path("/var/lib/shuttle-v13-monitor.json")
SENDER = Path("/usr/local/lib/shuttleflow-monitor/monitor.py")
MAIL = Path("/etc/shuttleflow-monitor/mail.json")


def active(unit):
    return subprocess.run(["systemctl", "is-active", "--quiet", unit]).returncode == 0


def main():
    faults = []
    if active("shuttle-v13.target"):
        for unit in ("java", "ai", "mysql", "redis", "operation"):
            if not active(f"shuttle-v13-{unit}.service"):
                faults.append(unit)
        for name, url in (("Java", "http://127.0.0.1:18080/api/health/ready"),
                          ("AI", "http://127.0.0.1:18000/health")):
            try:
                with urlopen(url, timeout=3) as response:
                    if response.status != 200:
                        faults.append(name)
            except OSError:
                faults.append(name)
    backups = list(Path("/var/backups/shuttle-v13").glob("shuttle_v13_sim_*.sql.gz"))
    if not backups or time.time() - max(p.stat().st_mtime for p in backups) > 30 * 3600:
        faults.append("数据库备份超期")
    old = json.loads(STATE.read_text()) if STATE.exists() else []
    print(json.dumps({"faults": faults}, ensure_ascii=False))
    if old == faults:
        return
    if not SENDER.is_file() or not MAIL.is_file():
        raise SystemExit("未发现现有 SMTP 运维配置，告警保留在系统日志")
    spec = importlib.util.spec_from_file_location("shuttleflow_monitor", SENDER)
    sender = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sender)
    subject = "ShuttleFlow V1.3 隔离仿真告警" if faults else "ShuttleFlow V1.3 隔离仿真恢复"
    text = "异常项：" + "、".join(faults) if faults else "隔离服务与备份检查恢复"
    sender.send_message(json.loads(MAIL.read_text()), subject, text, sender.html_body(subject, text))
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(json.dumps(faults))
    temporary.chmod(0o600)
    temporary.replace(STATE)


if __name__ == "__main__":
    main()
