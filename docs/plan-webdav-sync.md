# WebDAV 多端同步方案（砺行）

定稿日期：2026-09-03
状态：**① 迁移、② 实体改造、③ 同步内核、④ WebDAV 客户端、⑤ 接线+UI、⑥ 全量搬运 已完成**。实现顺序见文末「实施阶段」。

---

## 一、背景与动机

原方案靠「手动生成 `.lixingbackup` → 上传百度网盘 → 另一台下载 → 确认恢复」做多端同步，痛点：

1. **慢**：实测一个备份包 23.42 MB，其中 `photos/` 占 23.87 MB（**99.3%**），`data.json` 仅 **0.16 MB（0.7%）**。每次同步都重传全部照片。
2. **繁**：四步手动操作，还要在两台设备上各点一遍。

结论：**瓶颈不是 APK 体积、不是数据库，而是"全量快照 + 照片参与同步"这个策略**。

---

## 二、已敲定的决策

| 项 | 决策 |
|---|---|
| 百度网盘 | **保留不动**，作为兜底通道 |
| 新增通道 | **通用 WebDAV 客户端**（不写死坚果云，服务器地址用户自填） |
| 推荐服务商 | 坚果云（免费版；服务器地址 `https://dav.jianguoyun.com/dav/lixing/`） |
| 主键方案 | **③ 全面换 UUID**（14 张表），最干净、不丢数据 |
| 同步范围 | 全部业务表 + **AI 对话记录** |
| 同步方式 | **增量**（变更日志 + 基线快照），非全量快照 |
| 删除 | **同步**（墓碑机制） |
| 冲突策略 | **A：Lamport 逻辑时钟大者胜**（相等时按设备 ID 打破平局） |
| 触发方式 | **手动按钮 + 自动同步**（启动时 + 停止操作 30 秒后） |
| 网络限制 | 自动同步**仅 WiFi**；手动按钮不限 |
| 照片 | 日常增量**不同步照片**；照片路径列不参与同步，对端留空（不回填空值） |
| 全量搬运 | 手动触发，复用现有 `.lixingbackup`，**走百度通道**（2TB，不占坚果云额度） |
| 删远端副本 | **对端恢复成功并校验 SHA-256 后**才允许删除 |
| 恢复点 | 同步用独立 `before_sync` 槽位，保留最近 3 份，**不占用用户手动创建的版本** |

---

## 三、为什么 UUID 是必须的：ID 撞车

两台设备离线时各自自增主键，必然产生相同 id 指向不同记录：

- `daily_task`、`focus_session`、`meal_record`、`english_entry`、`assistant_message` 等都是 `@PrimaryKey(autoGenerate = true)`
- 例：两台手机早上各自物化今日任务，A 生成 id=1,2,3，B 也生成 id=1,2,3 → 按 id 合并会互相覆盖，或撞 `(date, template_id)` 唯一索引

已确认的**自然键**（不撞车，无需改 UUID）：

- `day_record` 主键是 `date`
- `daily_task` 有 `(date, template_id)` 唯一索引
- `check_in_streak` / `user_profile` 是单例表（`id: Int = SINGLETON_ID`）

---

## 四、UUID 迁移与现有数据的兼容性

### 结论

**兼容，数据 100% 保留。** 但这是一次**重建表**的迁移：SQLite 不能直接改主键列类型，必须走「建新表 → 搬数据 → 删旧表 → 改名」。数据在搬，所以对用户是非破坏性的。

### 表清单（当前数据库 VERSION = 9，迁移后 → 10）

**需要改 UUID 的 14 张表**（`id: Long`）：

`achievement`、`assistant_conversation`、`assistant_message`、`commitment`、`daily_task`、`english_entry`、`focus_session`、`meal_record`、`phase`、`point_ledger`、`study_plan`、`subject`、`task_template`、`time_slot`

**不需要改的 3 张表**：

- `check_in_streak`、`user_profile`：单例表（全库一行），不存在撞车
- `day_record`：主键是 `date` 自然键

### 真正的难点：外键翻译

改主键本身不难，难的是**所有引用它的外键列也要从 INTEGER 变 TEXT，且值要从旧整数 id 翻译成新 UUID**：

- `daily_task` → `template_id` / `subject_id` / `time_slot_id` / 计划 id
- `task_template` → `subject_id` / `time_slot_id` / `plan_id`
- `time_slot` / `phase` → `plan_id`
- `focus_session` → `daily_task_id` / `subject_id`
- `assistant_message` → `conversation_id`
- `point_ledger` / `commitment` → 相关引用

所以迁移必须**先建"旧整数 id → 新 UUID"映射，再回填所有引用列**。

### 迁移步骤（单个事务，全成功或全回滚）

```
1. 给每张表加 id_text TEXT 列
2. UPDATE 每行 SET id_text = <生成的 UUID>
3. 建 tmp_id_map(old_table, old_id, new_uuid) 映射表
4. 给所有外键列加 xxx_id_text 列，用 tmp_id_map 回填
5. 逐表重建：CREATE TABLE new_t(...) → INSERT SELECT(用 *_text 列) → DROP old → RENAME
6. 重建全部索引与外键
7. 同次迁移里加 modified_at INTEGER（Lamport 时钟）与墓碑相关列，省一个版本号
8. DROP tmp_id_map
```

### 风险与三重保险

| 风险 | 应对 |
|---|---|
| 迁移脚本写错导致数据错乱 | **先在你的 `.lixingbackup` 上离线跑一遍**，校验行数、外键引用数、抽样数据 |
| 迁移不可逆 | 迁移前强制建 **`before_uuid_migration` 恢复点**，且该恢复点是**旧格式**（能被旧版 App 恢复） |
| 新旧版本 App 混用写坏数据 | 数据库版本号 9 → 10，旧版 App 装上去会因 schema 版本过高拒绝降级（Room 默认行为） |
| 迁移耗时长 | 数据量仅 160 KB（几千行），毫秒级完成，无感知 |

> ⚠️ 按项目 README 的「真机测试数据保护」规则：真机跑迁移前，**必须先生成并导出一份 `.lixingbackup` 并确认文件存在**。

---

## 五、同步内核设计

### 时钟：Lamport 逻辑时钟（不用墙钟）

两台设备时钟即使差几分钟，墙钟 LWW 也可能让旧改动覆盖新改动。改用：

- 本地每次改动 `counter++`，写入记录 `modified_at`
- 同步时 `counter = max(counter, 对端记录值)`
- 比较：大者胜；相等时按**设备 ID 字典序**打破平局（确定性）

### `modified_at` 与墓碑用 SQLite trigger 维护

13~14 张表、几十个 `@Update/@Upsert`，手写维护时间戳必漏。改用 `AFTER INSERT/UPDATE/DELETE` 触发器统一处理（SQLite 默认 `recursive_triggers = OFF`，触发器内改同一张表不会递归，安全）。DAO 层一行不改。

### 云端布局：每台设备只写自己、只读别人

```
https://dav.jianguoyun.com/dav/lixing/
├── device-<A>.jsonl        ← A 的变更日志（A 独占写，追加）
├── device-<A>.snapshot     ← A 的基线快照（新设备首次同步用，避免重放几千条日志）
├── device-<B>.jsonl
└── device-<B>.snapshot
```

- **传输层天然零冲突**：A 永远不碰 B 的文件，不需要锁
- 新设备接入：先拉对端 `.snapshot` 建基线，再拉增量 `.jsonl`
- 日志膨胀时压缩：重写 `.snapshot` 并截断 `.jsonl`

### 设备身份

随机 UUID，存 DataStore，**排除在备份之外**（每台设备必须有自己的身份）。

### 凭据存储

沿用 `BaiduCredentialStore` 的做法：Android Keystore AES-GCM 加密，**排除在备份之外**。

---

## 六、顺带处理

- **AI 消息图片路径**：图片不同步，对端显示"图片不可用"（已有兜底）；且同步回来时**不能用对端的空值清空本端照片**
- **schema 版本校验**：两台设备 App 版本可能不同，快照带数据库版本号，不一致**拒绝同步**而非写坏数据
- **同步统计**：本次上传/下载字节数 + 累计用量，方便盯免费额度
- **失败可恢复**：网络断 / 额度超 / 文件占用 → 保留上次成功状态，下次继续，绝不半途写坏本地数据
- **时间戳**：统一 UTC epoch millis

---

## 七、实施阶段

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **① 迁移脚本** | v9→v10：UUID 转换 + 外键翻译 + modified_at + 墓碑列 | **先在 `.lixingbackup` 上离线跑通校验** |
| ② 实体/DAO/Repository 改造 | `id: Long` → `id: String`（14 张表 + 所有外键 + UI 传参） | 编译通过 |
| ③ 同步内核 | Lamport 时钟 + trigger + 墓碑 + 变更日志 + 合并算法 | 纯函数单测（不依赖网络/真机） |
| **④ WebDAV 客户端** | 通用 WebDAV（PUT/GET/PROPFIND/MKCOL）+ 凭据加密 | ✅ 已实现并单测（44 例：配置/客户端/传输层/端到端；全量 281 例绿） |
| ⑤ 接线 + UI | 设置页开关、手动按钮、自动同步、同步统计、`before_sync` 恢复点 | ✅ 已实现并单测（15 例：编排层端到端 + 空闲策略；全量 296 例绿。真机待验） |
| ⑥ 全量搬运 | 复用 `.lixingbackup` + 百度通道 + 恢复校验后删远端副本 | ✅ 已实现（删除入口在恢复成功并校验后才解锁；真机验证） |

③ ④ 可并行，且都能靠单测覆盖。
