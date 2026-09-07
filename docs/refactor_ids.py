"""Phase ② - DAO/Repository/Domain 的 Long id -> String 批量改造。

规则（刻意保守，只碰与行 id 相关的标识符）：
  1. 具名 id 参数: planId/subjectId/timeSlotId/slotId/templateId/phaseId/dailyTaskId/conversationId/taskId: Long -> String
  2. keepTaskIds: List<Long> -> List<String>
  3. dao/ 目录下 `id: Long` 参数 -> `id: String`
  4. dao/ 目录下 @Insert/@Upsert 返回 Long / List<Long> -> Unit（String 主键下 Room 返回的是 rowid，不是业务 id）
"""
import re

BASE = "F:/APP/app/src/main/java/com/example/lixing/"

ID_FIELDS = r"(planId|subjectId|timeSlotId|slotId|templateId|phaseId|dailyTaskId|conversationId|taskId)"
RULES_ID = [
    (re.compile(rf"\b{ID_FIELDS}\s*:\s*Long\b"), r"\1: String"),
    (re.compile(r"\bkeepTaskIds\s*:\s*List<Long>"), "keepTaskIds: List<String>"),
    (re.compile(r"\bid\s*:\s*Long\b"), "id: String"),
]
RULES_INSERT = [
    (re.compile(r"(suspend fun (?:insert|upsert|insertIgnore)[A-Za-z]*\([^)]*\))\s*:\s*Long\b"), r"\1"),
    (re.compile(r"(suspend fun (?:insert|upsert|insertIgnore)[A-Za-z]*\([^)]*\))\s*:\s*List<Long>"), r"\1"),
]

DAO_FILES = [
    "data/local/dao/PlanDao.kt", "data/local/dao/DailyTaskDao.kt",
    "data/local/dao/TaskTemplateDao.kt", "data/local/dao/FocusSessionDao.kt",
    "data/local/dao/AssistantChatDao.kt",
]
OTHER_FILES = [
    "data/repository/PlanRepository.kt", "data/repository/TaskRepository.kt",
    "data/repository/FocusRepository.kt", "data/repository/GamificationRepository.kt",
    "data/repository/AssistantChatRepository.kt", "data/exporter/ExportRepository.kt",
    "data/assistant/AssistantContextBuilder.kt", "data/assistant/AssistantDtos.kt",
    "data/assistant/AssistantModelClient.kt",
    "domain/assistant/AssistantModels.kt", "domain/assistant/PlanChangeApplier.kt",
    "domain/materialize/DailyTaskReconciler.kt", "domain/materialize/TaskMaterializer.kt",
    "domain/materialize/TemplateEligibility.kt", "domain/settle/DayStats.kt",
    "domain/usecase/CheckInUseCase.kt", "domain/usecase/SettleDayUseCase.kt",
]

changed = []
for rel in DAO_FILES:
    p = BASE + rel
    src = open(p, encoding="utf-8").read()
    orig = src
    for rx, rep in RULES_ID:
        src = rx.sub(rep, src)
    for rx, rep in RULES_INSERT:
        src = rx.sub(rep, src)
    if src != orig:
        open(p, "w", encoding="utf-8", newline="\n").write(src)
        changed.append(rel)

for rel in OTHER_FILES:
    p = BASE + rel
    try:
        src = open(p, encoding="utf-8").read()
    except FileNotFoundError:
        print("缺文件:", rel)
        continue
    orig = src
    for rx, rep in RULES_ID:
        src = rx.sub(rep, src)
    if src != orig:
        open(p, "w", encoding="utf-8", newline="\n").write(src)
        changed.append(rel)

print(f"共修改 {len(changed)} 个文件:")
for c in changed:
    print("  " + c)
