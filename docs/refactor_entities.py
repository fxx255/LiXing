"""Phase ② - 实体主键 Long -> String(UUID) 批量改造。"""
import re
import sys

BASE = "F:/APP/app/src/main/java/com/example/lixing/data/local/entity/"
FILES = [
    "StudyPlanEntity.kt", "PhaseEntity.kt", "SubjectEntity.kt", "TimeSlotEntity.kt",
    "TaskTemplateEntity.kt", "DailyTaskEntity.kt", "FocusSessionEntity.kt",
    "PointLedgerEntity.kt", "AchievementEntity.kt", "CommitmentEntity.kt",
    "MealRecordEntity.kt", "EnglishEntryEntity.kt", "AssistantChatEntity.kt",
]

PK_OLD = "@PrimaryKey(autoGenerate = true)"
PK_NEW = "@PrimaryKey"

changed = {}
for name in FILES:
    p = BASE + name
    src = open(p, encoding="utf-8").read()
    orig = src

    # 1) 主键：autoGenerate Long -> String UUID（default 交给构造器生成）
    src = src.replace(
        f"{PK_OLD}\n    val id: Long = 0,",
        f"{PK_NEW}\n    val id: String = UUID.randomUUID().toString(),",
    )

    # 2) 外键列 Long -> String（含可空）
    for field in ("planId", "subjectId", "timeSlotId", "phaseId", "templateId",
                  "dailyTaskId", "conversationId"):
        src = re.sub(
            rf"(val {field}: )Long(\??)",
            r"\1String\2",
            src,
        )

    # 3) import java.util.UUID
    if "import java.util.UUID" not in src:
        m = re.search(r"^import [^\n]+\n(?!import)", src, re.M)
        if m:
            src = src[: m.start()] + "import java.util.UUID\n" + src[m.start():]
        else:
            src = src.replace("package com.example.lixing.data.local.entity\n",
                              "package com.example.lixing.data.local.entity\n\nimport java.util.UUID")

    if src != orig:
        open(p, "w", encoding="utf-8", newline="\n").write(src)
        changed[name] = (
            src.count("val id: String = UUID.randomUUID().toString()"),
            len(re.findall(r"val [a-zA-Z]+Id: String", src)),
        )

print("已修改文件（UUID 主键数, String 外键数）:")
for k, (pk, fk) in changed.items():
    print(f"  {k:24s} PK={pk}  FK={fk}")
missing = [f for f in FILES if f not in changed]
print("未修改:", missing if missing else "无")
