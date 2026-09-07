"""砺行 v9 -> v10 (UUID) 迁移离线验证。

1. 用 Room 导出的 9.json 建出 v9 schema
2. 从真实备份的 data.json 灌入真实数据
3. 生成并应用 v9->v10 迁移
4. 校验：行数、UUID 唯一/非空、外键引用零丢失、唯一索引、新增列与表

迁移策略（外键安全）：
  旧表先整体 RENAME 成 old_*（现代 SQLite 会自动把相互引用改写成 old_*），
  再建新表并灌数据，最后按"子表优先"顺序 DROP old_*。
  这样 DROP 父表时已无子表依赖它，不会触发级联删除。
"""
import base64
import json
import os
import sqlite3
import sys
import uuid
import zipfile

SCHEMA = "F:/APP/app/schemas/com.example.lixing.data.local.LiXingDatabase/9.json"
BACKUP = "backup.zip"
WORK_DB = "verify.db"

# ---- 需要把自增 Long 主键换成 UUID 的表 ----
UUID_TABLES = [
    "study_plan", "phase", "subject", "time_slot", "task_template",
    "daily_task", "focus_session", "point_ledger", "achievement",
    "commitment", "meal_record", "english_entry",
    "assistant_conversation", "assistant_message",
]

# (子表, 引用列, 父表) —— 值要从旧整数 id 翻译成父表的新 UUID
FK_TRANSLATIONS = [
    ("phase", "plan_id", "study_plan"),
    ("subject", "plan_id", "study_plan"),
    ("time_slot", "plan_id", "study_plan"),
    ("task_template", "subject_id", "subject"),
    ("task_template", "time_slot_id", "time_slot"),
    ("task_template", "phase_id", "phase"),
    ("daily_task", "template_id", "task_template"),
    ("daily_task", "subject_id", "subject"),
    ("daily_task", "time_slot_id", "time_slot"),
    ("focus_session", "daily_task_id", "daily_task"),
    ("focus_session", "subject_id", "subject"),
    ("commitment", "phase_id", "phase"),
    ("point_ledger", "daily_task_id", "daily_task"),
    ("assistant_message", "conversation_id", "assistant_conversation"),
]

# 插入顺序：父表必须先于子表（外键约束下插入才合法）
INSERT_ORDER = [
    "study_plan", "phase", "subject", "time_slot", "task_template",
    "daily_task", "point_ledger", "focus_session",
    "assistant_conversation", "assistant_message",
    "achievement", "commitment", "meal_record", "english_entry",
    "day_record", "check_in_streak", "user_profile",
]

SYNC_COL = "sync_modified_at"
FILL = "@@FILL_UUID_MAPS@@"


def load_schema():
    with open(SCHEMA, encoding="utf-8") as f:
        return json.load(f)["database"]


def cell_to_py(cell):
    """备份里 DbCell 的编解码（与 VersionedBackupRepository 一致）。"""
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
    schema = load_schema()
    cur = db.cursor()
    for e in schema["entities"]:
        cur.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for e in schema["entities"]:
        for idx in e.get("indices") or []:
            cols = ", ".join(f"`{c}`" for c in idx["columnNames"])
            uniq = "UNIQUE " if idx.get("unique") else ""
            cur.execute(
                f"CREATE {uniq}INDEX IF NOT EXISTS `{idx['name']}` "
                f"ON `{e['tableName']}` ({cols})"
            )
    with zipfile.ZipFile(BACKUP) as z:
        data = json.loads(z.read("data.json"))
    counts = {}
    for t in data["tables"]:
        name, cols, rows = t["name"], t["columns"], t["rows"]
        ph = ", ".join("?" * len(cols))
        payload = [[cell_to_py(c) for c in r] for r in rows]
        cur.executemany(
            f"INSERT INTO `{name}` ({', '.join(f'`{c}`' for c in cols)}) VALUES ({ph})",
            payload,
        )
        counts[name] = len(payload)
    db.commit()
    return schema, counts


def q(db, sql, args=()):
    return db.execute(sql, args).fetchall()


def col_def(f, translated):
    cn = f["columnName"]
    if cn == "id":
        return "`id` TEXT NOT NULL"
    aff = "TEXT" if cn in translated else f["affinity"]
    return f"`{cn}` {aff}" + (" NOT NULL" if f.get("notNull") else "")


def generate_migration(schema):
    """生成 v9->v10 的 SQL 语句列表（便于移植到 Kotlin 的 Migrations.kt）。"""
    stmts = []
    ents = {e["tableName"]: e for e in schema["entities"]}
    all_tables = [e["tableName"] for e in schema["entities"]]

    # 1) 同步元表
    stmts.append(
        "CREATE TABLE IF NOT EXISTS `sync_clock` ("
        "`id` INTEGER PRIMARY KEY NOT NULL, `value` INTEGER NOT NULL)"
    )
    stmts.append("INSERT INTO `sync_clock` (`id`, `value`) VALUES (1, 1)")
    stmts.append(
        "CREATE TABLE IF NOT EXISTS `sync_tombstone` ("
        "`table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL, "
        "`deleted_at` INTEGER NOT NULL, PRIMARY KEY (`table_name`, `row_id`))"
    )
    stmts.append(
        "CREATE TABLE IF NOT EXISTS `sync_peer` ("
        "`peer_id` TEXT PRIMARY KEY NOT NULL, `cursor` INTEGER NOT NULL)"
    )

    # 2) 旧表全部改名（相互引用会被自动改写成 old_*）
    for t in all_tables:
        stmts.append(f"ALTER TABLE `{t}` RENAME TO `old_{t}`")

    # 3) 建 UUID 映射表，然后由 Kotlin 逐行生成 UUID 填充
    for t in UUID_TABLES:
        stmts.append(
            f"CREATE TABLE `tmp_map_{t}` ("
            "`old_id` INTEGER PRIMARY KEY NOT NULL, `new_id` TEXT NOT NULL)"
        )
    stmts.append(FILL)

    # 4) 建新表（含 UUID 主键、翻译后的 TEXT 外键列、同步时钟列、外键约束）
    for t in all_tables:
        e = ents[t]
        translated = {c for (ch, c, p) in FK_TRANSLATIONS if ch == t}
        cols = [col_def(f, translated) for f in e["fields"]]
        cols.append(f"`{SYNC_COL}` INTEGER NOT NULL DEFAULT 0")
        fks = []
        for fk in e.get("foreignKeys") or []:
            rc = ", ".join(f"`{c}`" for c in fk["referencedColumns"])
            lc = ", ".join(f"`{c}`" for c in fk["columns"])
            fks.append(
                f"FOREIGN KEY ({lc}) REFERENCES `{fk['table']}` ({rc}) "
                f"ON UPDATE {fk.get('onUpdate', 'NO ACTION')} "
                f"ON DELETE {fk.get('onDelete', 'NO ACTION')}"
            )
        pk_cols = e["primaryKey"]["columnNames"]
        pk = f"PRIMARY KEY ({', '.join(f'`{c}`' for c in pk_cols)})"
        body = ",\n  ".join(cols + fks + [pk])
        stmts.append(f"CREATE TABLE `{t}` (\n  {body}\n)")

    # 5) 索引
    for t in all_tables:
        for idx in ents[t].get("indices") or []:
            icols = ", ".join(f"`{c}`" for c in idx["columnNames"])
            uniq = "UNIQUE " if idx.get("unique") else ""
            stmts.append(
                f"CREATE {uniq}INDEX IF NOT EXISTS `{idx['name']}` ON `{t}` ({icols})"
            )

    # 6) 灌数据（父表先于子表）
    for t in INSERT_ORDER:
        e = ents[t]
        translated = {c: p for (ch, c, p) in FK_TRANSLATIONS if ch == t}
        sel = []
        for f in e["fields"]:
            cn = f["columnName"]
            if t in UUID_TABLES and cn == "id":
                sel.append(
                    f"(SELECT `new_id` FROM `tmp_map_{t}` m WHERE m.`old_id` = o.`id`)"
                )
            elif cn in translated:
                sel.append(
                    f"(SELECT `new_id` FROM `tmp_map_{translated[cn]}` m "
                    f"WHERE m.`old_id` = o.`{cn}`)"
                )
            else:
                sel.append(f"o.`{cn}`")
        # 旧表没有同步时钟列，统一从 0 起步（迁移后由 trigger 维护）
        sel.append("0")
        stmts.append(
            f"INSERT INTO `{t}` SELECT {', '.join(sel)} FROM `old_{t}` o"
        )

    # 7) 删旧表（子表优先，避免 DROP 父表时级联误伤）
    for t in reversed(INSERT_ORDER):
        stmts.append(f"DROP TABLE `old_{t}`")

    # 8) 清理映射表
    for t in UUID_TABLES:
        stmts.append(f"DROP TABLE `tmp_map_{t}`")

    return stmts


def fill_uuid_maps(db):
    """对应 Kotlin 迁移：查 id -> java.util.UUID.randomUUID() -> 写入 tmp_map_<t>。"""
    for t in UUID_TABLES:
        ids = [r[0] for r in q(db, f"SELECT `id` FROM `old_{t}`")]
        db.executemany(
            f"INSERT INTO `tmp_map_{t}` (`old_id`, `new_id`) VALUES (?, ?)",
            [(i, str(uuid.uuid4())) for i in ids],
        )
    db.commit()


def main():
    if os.path.exists(WORK_DB):
        os.remove(WORK_DB)
    db = sqlite3.connect(WORK_DB)
    db.execute("PRAGMA foreign_keys=ON")

    schema, v9_counts = build_v9(db)
    print("=== v9 基线（来自你的真实备份） ===")
    for k, v in sorted(v9_counts.items(), key=lambda x: -x[1]):
        tag = " [UUID]" if k in UUID_TABLES else ""
        print(f"  {k:24s} {v:6d}{tag}")
    print(f"  {'合计':24s} {sum(v9_counts.values()):6d}")

    print("\n=== 迁移前外键引用体检 ===")
    pre = {}
    for child, col, parent in FK_TRANSLATIONS:
        n = q(
            db,
            f"SELECT COUNT(*) FROM `{child}` c WHERE c.`{col}` IS NOT NULL "
            f"AND NOT EXISTS (SELECT 1 FROM `{parent}` p WHERE p.`id` = c.`{col}`)",
        )[0][0]
        nn = q(db, f"SELECT COUNT(*) FROM `{child}` WHERE `{col}` IS NOT NULL")[0][0]
        pre[(child, col)] = nn
        print(f"  {child}.{col:16s} -> {parent:22s} 非空={nn:5d} 孤儿={n}")

    stmts = generate_migration(schema)
    cur = db.cursor()
    for i, s in enumerate(stmts):
        if s == FILL:
            fill_uuid_maps(db)
            continue
        try:
            cur.execute(s)
        except Exception as e:
            print(f"\n!! 迁移第 {i} 条失败: {e}\nSQL: {s[:300]}")
            db.rollback()
            sys.exit(1)
    db.commit()
    print(f"\n迁移执行完毕：{len(stmts)} 条语句")

    print("\n=== v10 校验 ===")
    ok = True

    print("\n[1] 行数是否一致")
    for t, n in sorted(v9_counts.items(), key=lambda x: -x[1]):
        now = q(db, f"SELECT COUNT(*) FROM `{t}`")[0][0]
        if now != n:
            ok = False
        print(f"  {'OK ' if now == n else 'FAIL'} {t:24s} {n} -> {now}")

    print("\n[2] UUID 主键")
    for t in UUID_TABLES:
        n = q(db, f"SELECT COUNT(*) FROM `{t}`")[0][0]
        nu = q(db, f"SELECT COUNT(DISTINCT `id`) FROM `{t}`")[0][0]
        bad = q(db, f"SELECT COUNT(*) FROM `{t}` WHERE `id` IS NULL OR `id`=''")[0][0]
        good = (n == nu) and bad == 0
        ok = ok and good
        print(f"  {'OK ' if good else 'FAIL'} {t:24s} 行={n} 唯一={nu} 空={bad}")

    print("\n[3] 外键引用零丢失")
    for child, col, parent in FK_TRANSLATIONS:
        nn = q(db, f"SELECT COUNT(*) FROM `{child}` WHERE `{col}` IS NOT NULL")[0][0]
        orph = q(
            db,
            f"SELECT COUNT(*) FROM `{child}` c WHERE c.`{col}` IS NOT NULL "
            f"AND NOT EXISTS (SELECT 1 FROM `{parent}` p WHERE p.`id` = c.`{col}`)",
        )[0][0]
        lost = (nn != pre[(child, col)]) or orph != 0
        ok = ok and not lost
        print(
            f"  {'OK ' if not lost else 'FAIL'} {child}.{col:16s} -> {parent:20s} "
            f"非空 {pre[(child, col)]} -> {nn}, 孤儿={orph}"
        )

    print("\n[4] 完整性检查")
    fkc = q(db, "PRAGMA foreign_key_check")
    ic = q(db, "PRAGMA integrity_check")
    print(f"  foreign_key_check : {'OK（0 问题）' if not fkc else f'FAIL {len(fkc)} 条'}")
    print(f"  integrity_check   : {ic[0][0]}")
    ok = ok and not fkc and ic[0][0] == "ok"

    print("\n  [唯一索引]")
    for e in schema["entities"]:
        for i in e.get("indices") or []:
            if not i.get("unique"):
                continue
            cols = ", ".join(f"`{c}`" for c in i["columnNames"])
            dup = q(
                db,
                f"SELECT COUNT(*) FROM (SELECT {cols} FROM `{e['tableName']}` "
                f"GROUP BY {cols} HAVING COUNT(*) > 1)",
            )[0][0]
            ok = ok and dup == 0
            print(f"    {'OK ' if dup == 0 else 'FAIL'} {i['name']:44s} 重复组={dup}")

    print("\n[5] 同步相关新增")
    missing = [
        t for t in (e["tableName"] for e in schema["entities"])
        if SYNC_COL not in [r[1] for r in q(db, f"PRAGMA table_info(`{t}`)")]
    ]
    ok = ok and not missing
    print(f"    {'OK ' if not missing else 'FAIL'} {SYNC_COL} 列覆盖 17 张表")
    for t in ("sync_clock", "sync_tombstone", "sync_peer"):
        e = q(db, "SELECT COUNT(*) FROM sqlite_master WHERE name=? AND type='table'", (t,))[0][0]
        ok = ok and e == 1
        print(f"    {'OK ' if e else 'FAIL'} 表 {t}")
    print(f"    sync_clock 初值 = {q(db, 'SELECT value FROM sync_clock WHERE id=1')[0][0]}")

    print("\n[6] 抽样核对真实数据")
    for row in q(db, "SELECT `id`, `name`, `is_active` FROM study_plan"):
        print(f"    study_plan: id={row[0]}  name={row[1]}  active={row[2]}")
    tot = q(db, "SELECT COUNT(*) FROM daily_task")[0][0]
    for st, c in q(db, "SELECT status, COUNT(*) FROM daily_task GROUP BY status"):
        print(f"    daily_task {st}: {c}  (共 {tot})")
    print(f"    point_ledger 流水: {q(db, 'SELECT COUNT(*) FROM point_ledger')[0][0]} 条")
    print(f"    英语积累: {q(db, 'SELECT COUNT(*) FROM english_entry')[0][0]} 条")
    print(f"    已解锁成就: {q(db, 'SELECT COUNT(*) FROM achievement WHERE unlocked_at IS NOT NULL')[0][0]} 个")

    print("\n" + "=" * 52)
    print("  迁移验证结论: " + ("全部通过 ✅" if ok else "存在问题 ❌"))
    print("=" * 52)
    db.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
