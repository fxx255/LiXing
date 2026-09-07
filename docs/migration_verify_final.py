"""砺行 v9 -> v10 迁移终版离线验证（与 Kotlin MIGRATION_9_10 语句完全一致）。

与 docs/migration_verify.py 的区别：此处直接以 app/schemas/10.json 的 Room createSql 生成 DDL，
并保留 check_in_streak / user_profile 的 INTEGER 单例行主键（不做 UUID），
再在真实备份（桌面 lixing_*.lixingbackup）上跑迁移，校验数据零丢失与外键引用翻译。
"""
import base64
import json
import os
import sqlite3
import sys
import uuid
import zipfile
import re

SCHEMA9 = "F:/APP/app/schemas/com.example.lixing.data.local.LiXingDatabase/9.json"
SCHEMA10 = "F:/APP/app/schemas/com.example.lixing.data.local.LiXingDatabase/10.json"
BACKUP = "C:/Users/17611/Desktop/lixing_20260903_020837_344.lixingbackup"
WORK_DB = f"verify_final_{os.getpid()}.db"

UUID_TABLES = [
    "study_plan", "phase", "subject", "time_slot", "task_template",
    "daily_task", "focus_session", "point_ledger", "achievement",
    "commitment", "meal_record", "english_entry",
    "assistant_conversation", "assistant_message",
]
FK_MAP = {
    ("phase", "plan_id"): "study_plan",
    ("subject", "plan_id"): "study_plan",
    ("time_slot", "plan_id"): "study_plan",
    ("task_template", "subject_id"): "subject",
    ("task_template", "time_slot_id"): "time_slot",
    ("task_template", "phase_id"): "phase",
    ("daily_task", "template_id"): "task_template",
    ("daily_task", "subject_id"): "subject",
    ("daily_task", "time_slot_id"): "time_slot",
    ("focus_session", "daily_task_id"): "daily_task",
    ("focus_session", "subject_id"): "subject",
    ("commitment", "phase_id"): "phase",
    ("point_ledger", "daily_task_id"): "daily_task",
    ("assistant_message", "conversation_id"): "assistant_conversation",
}
INSERT_ORDER = [
    "study_plan", "phase", "subject", "time_slot", "task_template",
    "daily_task", "point_ledger", "focus_session",
    "assistant_conversation", "assistant_message",
    "achievement", "commitment", "meal_record", "english_entry",
    "day_record", "check_in_streak", "user_profile",
]


def cell_to_py(cell):
    t, v = cell.get("type"), cell.get("value")
    if t == "n" or v is None:
        return None
    if t == "i":
        return int(v)
    if t == "f":
        return float(v)
    if t == "b":
        return base64.b64decode(v)
    return v


def build_v9(db):
    d9 = json.load(open(SCHEMA9, encoding="utf-8"))["database"]
    cur = db.cursor()
    for e in d9["entities"]:
        cur.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for e in d9["entities"]:
        for ix in e.get("indices") or []:
            cur.execute(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    with zipfile.ZipFile(BACKUP) as z:
        data = json.loads(z.read("data.json"))
    counts = {}
    for t in data["tables"]:
        name, cols, rows = t["name"], t["columns"], t["rows"]
        payload = [[cell_to_py(c) for c in r] for r in rows]
        ph = ", ".join("?" * len(cols))
        cur.executemany(
            f"INSERT INTO `{name}` ({', '.join('`'+c+'`' for c in cols)}) VALUES ({ph})",
            payload,
        )
        counts[name] = len(payload)
    db.commit()
    return d9, counts


def gen_sql(d10):
    """生成与 Kotlin MIGRATION_9_10 完全一致的 SQL 语句列表（含 UUID 填充标记）。"""
    ents = {e["tableName"]: e for e in d10["entities"]}
    order = [e["tableName"] for e in d10["entities"]]
    out = []
    out.append("CREATE TABLE IF NOT EXISTS `sync_clock` (`id` INTEGER PRIMARY KEY NOT NULL, `value` INTEGER NOT NULL)")
    out.append("INSERT OR IGNORE INTO `sync_clock` (`id`, `value`) VALUES (1, 1)")
    out.append("CREATE TABLE IF NOT EXISTS `sync_tombstone` (`table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY (`table_name`, `row_id`))")
    out.append("CREATE TABLE IF NOT EXISTS `sync_peer` (`peer_id` TEXT PRIMARY KEY NOT NULL, `cursor` INTEGER NOT NULL)")
    for t in order:
        out.append(f"ALTER TABLE `{t}` RENAME TO `old_{t}`")
    seen = []
    for t in order:
        for ix in ents[t].get("indices") or []:
            if ix["name"] not in seen:
                seen.append(ix["name"])
                out.append(f"DROP INDEX IF EXISTS `{ix['name']}`")
    for t in UUID_TABLES:
        out.append(f"CREATE TABLE `tmp_map_{t}` (`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)")
    out.append("@@FILL@@")
    for t in order:
        out.append(ents[t]["createSql"].replace("${TABLE_NAME}", t))
    for t in order:
        for ix in ents[t].get("indices") or []:
            out.append(ix["createSql"].replace("${TABLE_NAME}", t))
    for t in INSERT_ORDER:
        e = ents[t]
        cols = [f["columnName"] for f in e["fields"]]
        sel = []
        for cn in cols:
            if cn == "sync_modified_at":
                sel.append("0")
            elif t in UUID_TABLES and cn == "id":
                sel.append(f"(SELECT `new_id` FROM `tmp_map_{t}` m WHERE m.`old_id` = o.`id`)")
            elif (t, cn) in FK_MAP:
                p = FK_MAP[(t, cn)]
                sel.append(f"(SELECT `new_id` FROM `tmp_map_{p}` m WHERE m.`old_id` = o.`{cn}`)")
            else:
                sel.append(f"o.`{cn}`")
        out.append(
            f"INSERT INTO `{t}` ({', '.join('`'+c+'`' for c in cols)}) "
            f"SELECT {', '.join(sel)} FROM `old_{t}` o"
        )
    for t in reversed(INSERT_ORDER):
        out.append(f"DROP TABLE `old_{t}`")
    for t in UUID_TABLES:
        out.append(f"DROP TABLE `tmp_map_{t}`")
    return out


def fill(db):
    for t in UUID_TABLES:
        ids = [r[0] for r in db.execute(f"SELECT `id` FROM `old_{t}`").fetchall()]
        db.executemany(
            f"INSERT OR IGNORE INTO `tmp_map_{t}` (`old_id`, `new_id`) VALUES (?, ?)",
            [(i, str(uuid.uuid4())) for i in ids],
        )
    db.commit()


def main():
    if os.path.exists(WORK_DB):
        os.remove(WORK_DB)
    print(f"[临时库] {WORK_DB}")
    db = sqlite3.connect(WORK_DB)
    db.execute("PRAGMA foreign_keys=ON")
    _, counts = build_v9(db)
    print("=== v9 基线（真实备份）===")
    total = sum(counts.values())
    for k in sorted(counts, key=lambda x: -counts[x]):
        print(f"  {k:24s} {counts[k]:6d}")
    print(f"  合计 {total}")

    d10 = json.load(open(SCHEMA10, encoding="utf-8"))["database"]
    stmts = gen_sql(d10)
    print(f"\n=== 执行迁移（{len(stmts)} 条，与 Kotlin 同源）===")
    cur = db.cursor()
    for i, s in enumerate(stmts):
        if s == "@@FILL@@":
            fill(db)
            continue
        try:
            cur.execute(s)
        except Exception as e:
            print(f"!! 第 {i} 条失败: {e}\nSQL: {s[:400]}")
            db.rollback()
            sys.exit(1)
    db.commit()

    ok = True
    print("\n=== 校验 ===")
    print("[1] 行数")
    for t, n in counts.items():
        now = db.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
        good = now == n
        ok = ok and good
        print(f"  {'OK ' if good else 'FAIL'} {t:24s} {n} -> {now}")

    print("[2] UUID 主键（14 张表）")
    for t in UUID_TABLES:
        n = db.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
        nu = db.execute(f"SELECT COUNT(DISTINCT `id`) FROM `{t}`").fetchone()[0]
        bad = db.execute(f"SELECT COUNT(*) FROM `{t}` WHERE `id` IS NULL OR `id`=''").fetchone()[0]
        pat = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        allmatch = all(
            pat.match(r[0]) for r in db.execute(f"SELECT `id` FROM `{t}`").fetchall()
        ) if n else True
        good = (n == nu) and bad == 0 and allmatch
        ok = ok and good
        print(f"  {'OK ' if good else 'FAIL'} {t:24s} 行={n} 唯一={nu} 空={bad} 形如UUID={allmatch}")

    print("[3] 单例行保持 INTEGER 主键 1")
    for t in ("check_in_streak", "user_profile"):
        row = db.execute(
            f"SELECT sql FROM sqlite_master WHERE type='table' AND name='{t}'"
        ).fetchone()[0]
        good = "`id` INTEGER" in row
        cnt = db.execute(f"SELECT COUNT(*) FROM `{t}` WHERE `id` = 1").fetchone()[0]
        good = good and cnt == 1
        ok = ok and good
        print(f"  {'OK ' if good else 'FAIL'} {t}  id INTEGER + 存在 id=1 ({cnt})")

    print("[4] 外键引用零孤儿")
    for (child, col), parent in FK_MAP.items():
        nn = db.execute(f"SELECT COUNT(*) FROM `{child}` WHERE `{col}` IS NOT NULL").fetchone()[0]
        orph = db.execute(
            f"SELECT COUNT(*) FROM `{child}` c WHERE c.`{col}` IS NOT NULL "
            f"AND NOT EXISTS (SELECT 1 FROM `{parent}` p WHERE p.`id` = c.`{col}`)"
        ).fetchone()[0]
        good = orph == 0
        ok = ok and good
        print(f"  {'OK ' if good else 'FAIL'} {child}.{col} -> {parent}  非空={nn} 孤儿={orph}")

    print("[5] 完整性 / 唯一索引")
    fkc = db.execute("PRAGMA foreign_key_check").fetchall()
    ic = db.execute("PRAGMA integrity_check").fetchone()[0]
    print(f"  foreign_key_check: {len(fkc)}  integrity: {ic}")
    ok = ok and not fkc and ic == "ok"
    for e in d10["entities"]:
        for ix in e.get("indices") or []:
            if not ix.get("unique"):
                continue
            cols = ", ".join(f"`{c}`" for c in ix["columnNames"])
            dup = db.execute(
                f"SELECT COUNT(*) FROM (SELECT {cols} FROM `{e['tableName']}` "
                f"GROUP BY {cols} HAVING COUNT(*) > 1)"
            ).fetchone()[0]
            if dup:
                ok = False
            print(f"  {'OK ' if dup == 0 else 'FAIL'} {ix['name']}")

    print("[6] 同步列与元表")
    missing = [
        e["tableName"] for e in d10["entities"]
        if "sync_modified_at" not in [r[1] for r in db.execute(f"PRAGMA table_info(`{e['tableName']}`)").fetchall()]
    ]
    ok = ok and not missing
    print(f"  {'OK ' if not missing else 'FAIL'} sync_modified_at 覆盖 17 表")
    for t in ("sync_clock", "sync_tombstone", "sync_peer"):
        e = db.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE name=? AND type='table'", (t,)
        ).fetchone()[0]
        ok = ok and e == 1
        print(f"  {'OK ' if e == 1 else 'FAIL'} 表 {t}")
    v = db.execute("SELECT value FROM sync_clock WHERE id=1").fetchone()[0]
    print(f"  sync_clock 初值 = {v}")

    print("\n[7] 抽样")
    for row in db.execute("SELECT `id`, `name`, `is_active` FROM study_plan").fetchall():
        print(f"    study_plan: id={row[0]} name={row[1]} active={row[2]}")
    print(f"    daily_task 总行数 = {db.execute('SELECT COUNT(*) FROM daily_task').fetchone()[0]}")
    print(f"    check_in_streak: {db.execute('SELECT current_streak, longest_streak FROM check_in_streak').fetchall()}")

    print("\n" + "=" * 52)
    print("  终版迁移离线验证: " + ("全部通过 ✅" if ok else "存在问题 ❌"))
    print("=" * 52)
    db.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
