# AWS Cloud Ops Adventure Android · 完整通关秘籍（当前 12 关）

> 更新时间：2026-10-17。适用于 [AWS_Cloud_Ops_Adventure_Android](https://github.com/seki999/AWS_Cloud_Ops_Adventure_Android) 的 0.1.0 教学版。
>
> **范围说明**：当前仓库仅含 `aws-001` 至 `aws-012` 共 12 关可玩 JSON。总目录的另外 48 关是计划项，**尚无可执行关卡**。文中解法来自各关 `reference` 操作序列及 `MissionValidator` 目标判定，不代表所有操作都已在 Android 真机逐步人工复测。
>
> **离线游戏**：不会登录 AWS、运行系统 Shell 或产生真实云费用。游戏内部的 `sim` 是教学命令，不属于真实 AWS CLI。

## 如何使用本秘籍

1. 首页点 **开始 / 继续游戏**，选择已解锁关卡，查看「任务/目标」的未满足项。
2. 可以在游戏 **Console** 通过操作类型、资源 ID、每行一个 `key=value` 参数进行操作，也可以打开本地 **CLI/终端** 输入下文 `sim` 命令。
3. **注意策略类操作**：`SimulationCommand.policy` 的策略数组不是普通 `sim key=value` 参数；第 2、3、4、6 关需使用 **Console 的 Policy 编辑器/策略输入界面**（或游戏允许的 `aws iam put-user-policy` 命令）提交策略。下面列出准确策略 JSON。
4. 每个关卡单独加载初始资源；前一关创建的资源不一定被后一关继承。别因为之前创建过相同 ID 就跳过本关操作。
5. 观察全部目标满足后，点击 **检查 / 领取奖励**。若按钮灰色，逐一检查目标、当前身份和命令返回消息。
6. 想拿三星，尽量避免失败操作与使用提示，同时保持最小权限。每次任务奖励不可通过重复领取刷取。

## 可复用的最小权限策略

第 2、3、6 关用于用户 `operator`；第 4 关用于 Role `web-role`，都是以下同一策略内容：

```json
[
  {
    "effect": "Allow",
    "actions": [
      "s3:GetObject"
    ],
    "resources": [
      "arn:aws:s3:::rescue/*"
    ]
  }
]
```

**Console**：操作 `policy`，选择目标 ID，使用结构化的策略输入区提交上述策略（参考项目当前 UI 支持的字段）；**不要**直接在终端输入 `sim policy operator` 并期望自动生成策略。

如果使用内置 AWS CLI 的 `aws iam put-user-policy`，可按如下形式输入**单行**命令：

```text
aws iam put-user-policy --user-name operator --policy-document '{"Statement":[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::rescue/*"]}]}'
```

该命令只适用于用户 `operator`，不能用来给 `web-role` 配置角色策略；Role 的策略必须通过 Console 的 `policy` 操作完成。

## 第 1 章：云基地与 IAM（第 01–06 关）

### 第 01 关 · 第一座云端基地

**过关操作顺序：**

```text
sim delete base-a
sim region unused value=ap-northeast-1
sim create base-a type=base az=ap-northeast-1a
sim create base-c type=base az=ap-northeast-1c
```

**成功条件：**Region 为 ap-northeast-1，base-a 的 AZ 为 ap-northeast-1a，base-c 的 AZ 为 ap-northeast-1c。

**关键提醒：**初始 base-a 的 AZ 是错误的 us-east-1a。当前模拟器通过删除后重建修复，不能把 `sim region` 误解为设置 AWS 真实账号 Region。

### 第 02 关 · 创建云端工作人员

**过关操作顺序：**

1. 创建 `operator` 用户。
2. 在 Console 中选中 `operator`，使用 `policy` 操作应用上文的完整最小权限策略。

**相关终端操作：**

```text
sim create operator type=user
```

**成功条件：**operator 用户存在，且能够对 arn:aws:s3:::rescue/map 执行 s3:GetObject。

**关键提醒：**第二步必须通过 Console 的 policy 操作填入完整 JSON 对应的 Allow / s3:GetObject / arn:aws:s3:::rescue/*；普通 `sim policy operator` 无 policies 参数不能完成此步。

### 第 03 关 · 过大的权限

**过关操作顺序：**

1. 选中现有 `operator`。
2. 用 Console 的 `policy` 操作**替换**其全部策略为上文 JSON。

**相关终端操作：**

```text

```

**成功条件：**operator 的唯一授权精确是 Allow + s3:GetObject + arn:aws:s3:::rescue/*。

**关键提醒：**在 Console 的 Policy 编辑器**替换**原来过宽的策略，不能只追加一条允许规则；`leastPrivilege` 检查政策 actions/resources 的精确数组。

### 第 04 关 · 服务器身份

**过关操作顺序：**

1. 创建 `web-role`（type=role）。
2. 设置其信任主体 `ec2.amazonaws.com`。
3. 在 Console 为该 Role 应用上文最小权限策略。
4. 给已有 `i-web` 执行 attachRole，关联 `web-role`。

**相关终端操作：**

```text
sim create web-role type=role
sim trust web-role service=ec2.amazonaws.com
sim attachRole i-web role=web-role
```

**成功条件：**i-web 关联 web-role，信任 EC2 并可对 rescue/map 执行 s3:GetObject。

**关键提醒：**策略配置通过 Console 的 policies 输入区提交；命令示意中的 policy 步骤不是可直接复制成功的完整命令。

### 第 05 关 · 身份危机

**过关操作顺序：**

```text
sim mfa admin-nova
```

**成功条件：**admin-nova 的 mfa=true 且 loginSecure=true。

**关键提醒：**当前教学模型一次 mfa 操作会设置相应安全属性；不是现实 AWS MFA 注册和验证流程。

### 第 06 关 · 最小权限决战

**过关操作顺序：**

1. 使用 Console 的 `policy` 操作，将 `operator` 的策略设置为上文唯一的 GetObject Allow 规则。
2. 确认 `s3:GetObject` 允许，`s3:DeleteObject` 拒绝。

**相关终端操作：**

```text

```

**成功条件：**operator 仅有 GetObject 授权；rescue/map 读取允许、DeleteObject 拒绝。

**关键提醒：**在 Console 填入精确策略，确认没有附加删除权限或其他广泛授权。

## 第 2 章：EC2 与运维故障（第 07–12 关）

### 第 07 关 · 第一台 EC2

**过关操作顺序：**

```text
sim create i-rescue type=instance instanceType=t3.micro az=ap-northeast-1a ami=ami-base subnet=subnet-a sg=sg-web keyPair=training-key
```

**成功条件：**i-rescue 满足运行态、应用运行和系统健康条件。

**关键提醒：**必须使用关卡预置 AMI、Subnet、SG、AZ；这些是教学资源 ID。

### 第 08 关 · 停机的 Web 服务器

**过关操作顺序：**

```text
sim start i-web
```

**成功条件：**i-web 运行、应用 running 且 system=ok。

**关键提醒：**初始已有 i-web，无需重复创建。

### 第 09 关 · 磁盘空间危机

**过关操作顺序：**

```text
sim resizeVolume vol-data size=20
sim growFilesystem vol-data
```

**成功条件：**vol-data 文件系统可用空间至少 8，且关联 i-web。

**关键提醒：**只扩 EBS 容量不等于扩文件系统，必须做第二步。

### 第 10 关 · 服务器镜像

**过关操作顺序：**

```text
sim image i-web image=ami-rescue
sim create i-clone type=instance instanceType=t3.micro az=ap-northeast-1a ami=ami-rescue subnet=subnet-a sg=sg-web
```

**成功条件：**ami-rescue 的 source=i-web；i-clone 使用 ami-rescue 且健康。

**关键提醒：**先生成 AMI 再创建克隆实例，二者 ID 要一致。

### 第 11 关 · 高成本服务器

**过关操作顺序：**

```text
sim stop i-web
sim resizeInstance i-web instanceType=t3.micro
sim start i-web
```

**成功条件：**模拟小时费用不超过 0.04 单位，同时 i-web 健康。

**关键提醒：**先停止再更改实例规格，最后启动；教学报价不是实际 AWS 价格。

### 第 12 关 · 午夜故障

**过关操作顺序：**

```text
sim service i-web app=running
```

**成功条件：**i-web 的 state=running、app=running、system=ok。

**关键提醒：**本关主要是应用服务停止，并非必须重启 EC2；直接恢复 app。

## 游戏评分与关卡解锁

- **全部目标及安全约束成立**，才是正式通关的基础。
- 根据当前 `MissionValidator`：功能目标 40 分、安全性 20、模拟成本 15、效率 15、实践/约束 10；**每次使用提示扣 10 分，每次失败操作扣效率分**。
- 分数达到 90 得 3 星，75–89 得 2 星，60–74 得 1 星；未通关或不足 60 分为 0 星。
- IAM 最小权限关卡不要保留多余的 `Allow *` 策略；`commander` 默认的教学管理员权限不算作异常宽权限资源。
- 一次只能保存当前活动世界。切换关卡会重置活动工作区，但完成记录和进度保存。

## 卡关排查速查

| 问题 | 检查及修复 |
|---|---|
| `UnsupportedOperation` | 内置 AWS CLI 是白名单子集；输入 `aws help`，或改用 Console 的 `sim` 操作。 |
| 命令权限拒绝 `AccessDenied` | 用 `sim identity commander` 返回模拟管理员；不要把模拟身份机制当成实际安全边界。 |
| 删除 base-a 后仍不能过关 | 必须重新建 `base-a` 到东京 AZ-a，并建 `base-c` 到 AZ-c。 |
| IAM 读权限不对 | 核对对象策略的 effect/action/resource 数组；最小权限目标是精确匹配，而不是只要求覆盖权限。 |
| Role 访问无效 | 核对 trust=`ec2.amazonaws.com`、Role 策略和 `i-web.role` 关联。 |
| EC2 创建失败 | 确认所引用的 `ami-base`、`subnet-a`、`sg-web` 在关卡初始资源中存在，AZ 匹配。 |
| EC2 不健康 | `healthy` 必须同时满足 state=running、app=running、system=ok。 |
| EBS 扩容仍报空间不足 | 需要执行 `sim growFilesystem vol-data`，并确保挂载到 `i-web`。 |
| 新克隆实例不健康 | 应先创建 `ami-rescue`，再用该 AMI 创建 `i-clone`。 |
| 缩容后不能操作 | 第 11 关必须 STOP → resizeInstance → START。 |
| 第 12 关错误地重启服务器 | 此关重点是恢复 `app=running`，使用 `sim service i-web app=running`。 |
| 操作后无法结算 | 核实目标都满足，并点击 **检查 / 领取奖励**；只输入查询命令不会自动结算。 |

## 现有项目边界与代码来源

- [关卡 JSON](../app/src/main/assets/missions)
- [关卡概要](MISSIONS.md)
- [终端支持范围](CLI_REFERENCE.md)
- [模拟规则](SIMULATION_RULES.md)
- [路线图：48 个尚未实现的关卡](ROADMAP.md)
- [验证记录](VALIDATION.md)
- [核心 CLI 实现](../core/src/main/kotlin/com/cda/engine/Cli.kt)
- [关卡状态断言](../core/src/main/kotlin/com/cda/engine/Model.kt)

维护时请使用 `python tools/validate_content.py` 和 `./gradlew :core:test` 校验关卡、引用和引擎行为；本秘籍提交本身不会运行 Android 构建或完成设备回归验收。
