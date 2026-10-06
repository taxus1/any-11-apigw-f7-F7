# 路由配置热生效与多实例一致切换设计

## 1. 目标与结论

目标不是“每台机器过一会儿自己刷”，而是：

1. 后台一次提交，新增、修改、删除、停用，以及路由下的全部条件、动作都作为一次配置变更处理。
2. 三台网关不会各自抢跑：先把同一份配置拉到、校验完，再统一切换。
3. 任何一台拉取或校验失败，所有实例继续跑旧配置，不放半套新配置，也不因刷新失败让现有转发不可用。
4. 切换瞬间正在涌入的请求，只能看到整套旧配置或整套新配置；这里的“整套”是全量路由集合，每条路由又包含主记录、条件、动作等完整子树。
5. 实例重启、网络恢复后，可按全局版本追齐；并提供手动刷新、版本查询和强制切换排障入口。

本项目当前路由的“库”就是 Redis Hash：

```text
key   = apigw:routes
field = routeNo
value = 路由主信息 + conditions + actions + grayGroups 的完整 JSON
```

因此“库表还是原来那套”在本项目中解释为：**不改 `apigw:routes` 的 Hash/field/JSON 结构，不新增关系表；只增加版本快照、实例状态和协调用的 Redis key。**

最终方案选择：

- **配置源：Redis 是权威存储，网关内存保存不可变快照；运行时绝不逐请求查 Redis。**
- **感知方式：Redis Pub/Sub 推送唤醒 + 定时拉取兜底。**
- **一致性方式：全局 revision + 不可变全量快照 + 两阶段集群切换。**
- **故障策略：准备失败、校验失败、协调失败、Redis 短时故障，都保留上一版内存快照继续服务。**
- **建议默认延迟：推送成功通常 100ms 内开始准备；最坏按 1s 轮询兜底。集群切换增加一次短栅栏，目标总延迟不超过约 1s。**

不能只给每台实例加定时刷新。那样会出现：实例 A 已新、B/C 仍旧；全量读取过程中还可能读到两个 revision 的混合状态；无法满足“三台拿到同一份再一起切”的要求。

---

## 2. 现在代码的差距

现有实现已经具备两个基础点：

- 每条路由及其子项放在一个 Redis Hash field 中，单条路由整树 JSON 是原子的。
- `RouteCatalog` 用内存快照转发，Redis 故障时已有旧快照可继续服务。

但还缺三件关键能力：

1. **没有全局 revision 与不可变全量快照**
   - 管理端连续改两条路由时，某实例 `HVALS apigw:routes` 可能读到第一笔提交后的状态和第二笔提交后的状态的混合集合。
   - 单条路由 JSON 不残缺，不代表全量路由集合同一版本。

2. **没有跨实例两阶段切换**
   - 本实例进程内事件只对本 JVM 生效。
   - 其他实例靠 10s 轮询，谁先刷到谁先按新配置走，无法避免三台行为不一致。

3. **没有切换瞬间的流量栅栏**
   - 即使三台先后更新，刷新瞬间的并发请求仍可能有的拿到旧快照、有的拿到新快照。

---

## 3. Redis 数据结构

### 3.1 保留原有配置结构

继续使用：

```text
apigw:routes
  field: routeNo
  value: 完整路由 JSON
```

单条路由的新增、修改、删除仍只改这个 field：

- 新增：`HSETNX`
- 修改：版本检查通过后 `HSET`
- 删除：`HDEL`
- 停用：修改 enabled=0，不删除编号

这样可以继续保证一条路由不会出现“主记录改了、条件或动作还挂旧值”。

### 3.2 新增全局 revision

```text
apigw:route:revision        string，单调递增的全局配置版本
apigw:route:snapshot:{rev} hash，该 revision 对应的完整 apigw:routes 副本
apigw:route:snapshot:index zset，member=revision，score=revision 创建时间
apigw:route:active         hash，当前已激活版本及校验信息
apigw:route:changes        stream/list，可选审计与追赶用，记录变更类型和路由号
apigw:route:instances      hash，实例心跳与运行状态
apigw:route:barrier:{rev}  hash，某 revision 的两阶段切换状态
apigw:route:channel        pub/sub channel
```

不新增 SQL 表；这些是协调状态，不应放进业务配置表。

### 3.3 提交必须做成 Lua 原子操作

一次路由提交在一个 Lua 脚本中完成：

1. 对新增做 `HEXISTS apigw:routes routeNo` 检查，或用 `HSETNX`。
2. 对修改/删除检查路由内 `version`，版本不符返回明确冲突码。
3. 修改当前路由 field 或删除 field。
4. `INCR apigw:route:revision` 得到新的全局 revision。
5. `DUMP/RESTORE` 或等效方式复制当前 `apigw:routes` 到 `apigw:route:snapshot:{rev}`；Redis 7 可优先使用 `COPY ... REPLACE`。
6. 写 snapshot index 和 TTL。
7. 写 active 之外的提交元数据。
8. `PUBLISH apigw:route:channel committed:{rev}`。

关键点：第 3～8 步在 Redis 单线程脚本内原子执行。网关读到 revision N 时，`snapshot:N` 必然是提交 N 完成后的完整 Hash，不会读到跨提交混合状态。

建议只保留最近 20 个快照，每个快照 TTL 7 天，避免异常情况下无限增长。正常实例落后超过保留窗口时，直接重新注册并拉取当前 active revision；不能拿旧 revision 上线接流量。

> 路由规模很大时，不应每个 revision 全量复制大 Hash。可演进为“revision manifest + 每路由内容版本化 field”，本次三台网关、配置量有限，全量快照最简单可靠。

### 3.4 激活指针

`apigw:route:active` 示例：

```json
{
  "revision": 42,
  "checksum": "sha256:...",
  "activatedAt": "2026-10-06T10:00:00Z",
  "activatedBy": "route-coordinator"
}
```

- `snapshot:{rev}` 表示“已经完整提交、可供准备的版本”。
- `active` 表示“集群允许接流量使用的版本”。
- 新版本可能已经 committed，但因某台没准备好而长时间不是 active；此时流量仍按 active 的旧版本走。

---

## 4. 路由内存模型

新增 `RouteSnapshot`：

```java
record RouteSnapshot(
    long revision,
    String checksum,
    List<GatewayRoute> activeRoutes,
    Instant committedAt,
    Instant loadedAt
) {}
```

要求：

- `activeRoutes` 是构造完成后不可变的 `List<GatewayRoute>`。
- `GatewayRoute` 及其 conditions/actions/grayGroups 在快照中也视为不可变对象。
- 新快照在临时对象中完成反序列化、校验、过滤、排序和 checksum 校验。
- 校验全部成功前，不替换当前 `volatile RouteSnapshot current`。
- 替换只执行一次引用赋值。
- 每个请求在开始匹配时取得同一个 `RouteSnapshot` 引用，之后即使后台发生切换，这笔请求的匹配、动作、灰度目标也继续使用同一对象。

过滤口径沿用现有逻辑：

- enabled=1；
- 至少有一条条件；
- 条件与动作按 sortNo 排序；
- 聚合校验失败则整个 revision 判为无效。

这样可以保证：

- 请求不会读到构造一半的 List。
- 请求不会匹配新路由条件后又执行旧动作。
- 后台线程替换快照不需要在转发路径上加全局大锁。

---

## 5. 单台实例热更新流程

### 5.1 正常提交流程

```text
后台调用管理接口
  -> 聚合校验路由字段、条件、动作、灰度组
  -> RouteStore 原子 Lua 提交
      - 修改 apigw:routes
      - 生成 revision=N 和 snapshot:N
      - publish committed:N
  -> 管理接口返回成功，返回 revision=N
```

管理接口成功意味着数据库/Redis 中已经有完整的新配置，不代表三台已经切换。响应中应同时返回：

```json
{
  "code": 0,
  "data": {
    "routeNo": "order",
    "routeVersion": 3,
    "revision": 42,
    "activationStatus": "PREPARING"
  }
}
```

如果运营希望同步等待切换完成，可提供 `?waitActivation=true&timeoutMs=3000`，但默认不建议长时间占用管理请求。

### 5.2 本机感知

三台实例都执行同一套协调逻辑，不因为管理请求落在某一台上就特殊放行。

感知来源：

1. **主：Redis Pub/Sub**
   - channel：`apigw:route:channel`
   - 消息：`committed:{rev}`、`activate:{rev}`、`abort:{rev}` 等。
   - 只作为唤醒信号，消息内容不作为配置真相。

2. **兜底：定时拉取**
   - 默认 1s。
   - 只读 revision/active/barrier/instance 元数据，不每秒拉全量路由。
   - Pub/Sub 丢消息时，下一次定时任务可恢复。

建议配置：

```yaml
apigw:
  proxy:
    route:
      coordination-enabled: true
      expected-instances: 3
      poll-interval: 1s
      prepare-timeout: 3s
      fence-timeout: 800ms
      switch-max-wait: 1s
      instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:}}
```

### 5.3 准备阶段

实例收到 committed=N：

1. 检查本机当前 revision。
2. 读 `snapshot:N` 全量 Hash。
3. 对每条 JSON 做反序列化和领域校验。
4. 构造完整不可变 `RouteSnapshot`。
5. 计算全量 checksum，与协调状态中的 checksum 比较。
6. 放入本地 `stagedSnapshots`，但不接流量。
7. 在 `barrier:N.ready`中写入：

```json
{
  "instanceId": "gw-2",
  "revision": 42,
  "checksum": "sha256:...",
  "state": "READY",
  "stagedAt": "..."
}
```

任何一步失败：

- 写 `FAILED` 和错误原因；
- 释放 staged；
- 当前请求继续用旧快照；
- barrier 不允许进入激活；
- 定时任务稍后自动重试。

Redis 拉取失败、JSON 损坏、checksum 不一致，都不能替换当前快照。

---

## 6. 三台一致切换：两阶段栅栏

### 6.1 成员与心跳

每个实例启动后生成稳定 ID：

- 优先 `GATEWAY_INSTANCE_ID`；
- Kubernetes StatefulSet 下可用 hostname，如 `apigw-gateway-0/1/2`；
- 不建议用随机 UUID，否则重启后无法判断“同一个 Pod 重启”还是“新实例”。

心跳 key/field：

```text
apigw:route:instances
  field = instanceId
  value = {
    "address": "...",
    "state": "RUNNING|STAGING|READY|FENCED|ACTIVE|FAILED",
    "activeRevision": 41,
    "targetRevision": 42,
    "lastRevision": 41,
    "lastLoadAt": "...",
    "heartbeatAt": "..."
  }
```

每 1s 或 2s 刷新 heartbeat，读取时以 5s 未心跳判定失联。

默认要求 `expected-instances=3` 且三台心跳健康才自动切换。某台进程停了、网络断了，不把它从集群里悄悄踢掉后继续切，而是暂停新 revision 激活，全部活着的实例继续旧版本。

为什么不自动剔除：题目明确要求三台一起跑、宁可旧配置顶着也不能一台新两台旧。自动剔除只有在明确做扩缩容时才允许。

### 6.2 Phase 1：Prepare

```text
revision=N committed
  -> 三台拉 snapshot:N
  -> 三台本地校验并暂存
  -> 三台 READY
  -> coordinator 发现 readyQuorum == expectedInstances
```

协调者可以由第一个发现 N 的实例通过 Lua `SETNX barrier:N.leader` 选出，不需要独立服务。

只要有一台失败或超时：

- barrier 标记 `ABORTED`；
- publish `abort:N`；
- 所有实例丢弃本次 staged 或保留但不启用；
- active 仍指向旧 revision；
- 失败原因进入状态接口；
- 定时任务继续重试，下一次成功会重新推进。

### 6.3 Phase 2：Fence and Switch

仅 READY 全齐后，协调者把 barrier 改成 `ACTIVATING`，发布 `fence:N`。

每个实例收到后执行：

1. 在本机设置 `ActivationGate`，让新进入的转发请求在路由读取前短暂等待。
2. 已经拿到旧快照引用的在途请求继续用旧配置完成，不强行中断。
3. 上报 `FENCED`。
4. 等三台都 FENCED。

协调者看到三台 FENCED 后，用 Lua 一次性：

1. 校验三台 ready/fenced 记录仍属于 revision=N；
2. 校验 checksum 一致；
3. 更新 `apigw:route:active = N`；
4. barrier 改成 `COMMITTED_ACTIVE`；
5. publish `activate:N`。

每个实例收到 activate：

1. 从 staged 取出 revision=N；
2. `current = staged`，一次 volatile 引用赋值；
3. 打开 gate；
4. 状态改成 ACTIVE，更新 activeRevision=N。

被 gate 挡住的请求释放后全部拿到 N，在切换前已经进入匹配的请求仍完整使用旧对象。因此切换边界明确：

- **进入路由匹配前**：等待，按 N。
- **已经取得旧快照并开始匹配**：继续旧配置到底。

不会出现同一请求条件用新版、动作用旧版，也不会让请求看到半构造 List。

### 6.4 为什么需要短暂挡流量

如果只靠“收到消息后各自替换引用”，三台替换有毫秒级先后，而且每台内部并发请求也可能在赋值前后穿插，刷新瞬间必然可能出现一部分旧、一部分新。

短暂 fence 的取舍：

- 代价：新请求最多等待约 0.5～1s；极端超时下返回 503 `CONFIG_SWITCH_TIMEOUT`。
- 收益：集群和单机都不会半套切换。
- 超时策略：fence 失败则 abort，gate 打开，所有等待请求继续按旧配置执行，下一轮自动重试。

队列必须有界，建议每台最多 10000 个等待请求；超过后返回 503，不能无限堆积把堆撑爆。WebFlux 中使用 `Mono.delay`/异步回调等待，不能阻塞 Netty event loop。

### 6.5 切换延迟

可承诺的延迟：

- 正常 Pub/Sub 到达：100ms 内开始 prepare。
- prepare 与三实例 fence：通常 200～500ms。
- Pub/Sub 丢消息：1s 轮询发现。
- 最坏常规延迟：约 1～2s。
- 某台拉取失败或网络不通：不切换，旧配置持续服务；不存在部分新配置的“延迟后部分上线”。

10s 轮询过于粗糙，建议默认改为 1s；1s 只是读取一个版本号/协调状态，开销很小。

---

## 7. 失败与恢复场景

| 场景 | 行为 |
| --- | --- |
| 某台拉 snapshot 失败 | 该台 FAILED，其他两台不切换，三台继续旧版本；后台自动重试 |
| 某台 JSON 校验失败 | 整个 revision 不激活，旧版本继续；状态接口显示具体实例与错误 |
| 某台心跳消失 | barrier 不满足 expected instances，暂停激活；恢复后自动追当前 active 和最新 committed |
| prepare 阶段 Redis 短暂断开 | 没有改 active，所有实例继续旧快照；重连后按 revision 重新准备 |
| fence 后、commit active 前协调者宕机 | 其他实例通过 Lua 锁接管 barrier；状态不满足则 abort，全部继续旧版 |
| active 已更新、某台没收到 activate 消息 | 1s 轮询发现 active=N，若本地已 staged 则本地切换；未 staged 则先进入 prepare/fence 或由协调器补做新一轮检查 |
| 某台进程重启 | 启动后读 active revision，加载 snapshot:active，校验通过才可接流量；不是直接用本地不确定状态接流量 |
| 网络分区，少数实例看不到 Redis | 少数实例保留最后旧快照；从集群视角它是 STALE，readiness 失败，负载均衡应摘流；不会主动采用新版本 |
| Redis 完全故障但进程未重启 | 全部实例保留最后 active 快照继续转发；管理写接口失败，读接口可返回 Redis 不可用，转发不挂 |
| Redis 故障同时实例冷启动 | 默认 fail-closed：没有可信快照不接转发流量，readiness 失败；等 Redis 恢复后加载 active |

### 重启后的追齐规则

启动顺序：

1. 注册实例状态为 `STARTING`，先不报 ready。
2. 读 `apigw:route:active`。
3. 拉 `snapshot:{activeRevision}`。
4. 校验 checksum、路由完整性和领域规则。
5. 构造不可变快照并设置 current。
6. 心跳状态改为 `RUNNING/ACTIVE`。
7. 若发现 latest committed revision 更高，再参与下一次 prepare。

如果 active revision 的 snapshot 已过保留窗口：

- 不允许直接从更旧版本接流量；
- 触发一次从 `apigw:routes` 当前完整状态重建 snapshot 的协调流程；
- 重建期间该实例不 ready。

本地磁盘缓存可以作为增强：最近 active snapshot 成功校验后落本地文件，Redis 短时不可用时可启动，但默认只进入 STALE/READINESS_DOWN，不直接重新加入集群自动切换。是否允许这种节点继续转发，应由部署摘流策略决定，避免它和其他机器长期版本不一致。

---

## 8. 手动排障与强制入口

管理接口应走独立管理端口或加管理员鉴权，不能裸奔在公网转发端口。

### 8.1 查询三台版本

```http
GET /api/gateway/route-coordination
```

返回：

```json
{
  "code": 0,
  "data": {
    "activeRevision": 41,
    "latestRevision": 42,
    "barrierState": "WAITING_READY",
    "targetRevision": 42,
    "checksum": "sha256:...",
    "expectedInstances": 3,
    "healthyInstances": 2,
    "instances": [
      {
        "instanceId": "gw-0",
        "state": "READY",
        "activeRevision": 41,
        "targetRevision": 42,
        "lastLoadAt": "2026-10-06T10:00:01Z",
        "heartbeatAt": "2026-10-06T10:00:03Z",
        "error": null
      },
      {
        "instanceId": "gw-1",
        "state": "READY",
        "activeRevision": 41,
        "targetRevision": 42,
        "lastLoadAt": "2026-10-06T10:00:01Z",
        "heartbeatAt": "2026-10-06T10:00:03Z",
        "error": null
      },
      {
        "instanceId": "gw-2",
        "state": "FAILED",
        "activeRevision": 41,
        "targetRevision": 42,
        "lastLoadAt": "2026-10-06T09:58:00Z",
        "heartbeatAt": "2026-10-06T10:00:03Z",
        "error": "checksum mismatch"
      }
    ]
  }
}
```

至少能看出：每台当前 active revision、目标 revision、上次拉取时间、心跳时间、失败原因。

### 8.2 手动让三台重新拉取并协调

```http
POST /api/gateway/route-coordination/refresh
```

行为：

- 清理失败状态；
- 通知三台重新读 latest/active；
- 若存在未激活的 latest revision，重新走完整 prepare/fence/switch；
- 不绕过任何校验和成员检查。

### 8.3 强制激活（应急）

```http
POST /api/gateway/route-coordination/force-activate
{
  "revision": 42,
  "acknowledge": "I_UNDERSTAND_SPLIT_BRAIN_RISK",
  "reason": "gw-2 已确认被摘流，工单 xxx"
}
```

该接口只用于某实例已确认下线/被负载均衡摘除的应急场景。它必须：

- 记录操作者、原因、时间；
- 明确返回哪些实例不在线、不会升级；
- 默认仍要求所有心跳健康实例 checksum 一致；
- 不能在三台都接流量时静默强制。

正常故障处理不应依赖这个按钮；题目要求“宁可旧配置顶着”，自动流程必须遵守，强制只是人工破窗。

### 8.4 维护成员

扩缩容或换机器时提供：

```http
POST /api/gateway/route-coordination/members/gw-2/retire
```

将 expected members 显式调整或临时标记 retired。不能仅凭一次心跳超时永久删除成员，避免网络抖动后集群拓扑乱跳。

---

## 9. 健康检查与部署口径

### Readiness

实例只有在满足以下条件时才 ready：

- 已加载当前 active revision；
- checksum 校验通过；
- 没有处于 FAILED 的目标 revision 且本机被要求参与切换；
- Redis 心跳正常，或明确处于允许的 STALE 模式。

Kubernetes 示例口径：

- `/actuator/health/liveness`：进程活着即可。
- `/actuator/health/readiness`：路由 active 快照可用且版本状态合格。

Redis 故障且已有旧快照时：

- liveness 保持 UP；
- readiness 可根据部署策略设为 OUT_OF_SERVICE，让运维决定是否摘流；
- 已经打到该机器的请求仍可用旧快照正常转发。

这样可以区分“进程没死”和“是否还能参与集群一致切换”。

---

## 10. 需要改动的代码模块

建议按下面顺序落地。

### 10.1 Store 层

新增/改造：

- `RouteRevisionStore`
  - `commitCreate(...)`
  - `commitUpdate(...)`
  - `commitDelete(...)`
  - `getActiveRevision()`
  - `loadSnapshot(revision)`
  - Lua scripts：`commit_route.lua`、`barrier_prepare.lua`、`barrier_fence.lua`、`barrier_activate.lua`、`barrier_abort.lua`
- 保留 `RouteStore.serialize/deserialize`。
- 原 `create/update/delete` 改为通过 revision commit Lua 执行，避免出现“改 Hash 成功但没有 revision”的旁路写法。

路由自身 version 仍用于并发编辑；revision 是集群配置版本，两者不互相替代。

### 10.2 协调层

新增：

- `RouteSnapshot`
- `RouteSnapshotHolder`：保存 current、staged、activation gate。
- `RouteCoordinationService`：prepare、fence、activate、abort、心跳、定时协调。
- `RouteInstanceRegistry`：实例注册和心跳。
- `RouteActivationGate`：切换瞬间短栅栏。
- `RouteCoordinationProperties`：expected instances、超时、轮询周期等。

### 10.3 转发层

修改：

- `RouteCatalog.routes()` 返回 `Mono<RouteSnapshot>` 或至少内部持有 revision。
- `GatewayProxyWebFilter` 在匹配前：

```java
return routeCatalog.snapshot()
    .flatMap(snapshot -> {
        GatewayRoute route = routeMatcher.match(snapshot.routes(), request);
        ...
    });
```

- 已取到的 `GatewayRoute` 贯穿请求和响应动作，不重新查 catalog。
- gate 等待只发生在取快照前。

### 10.4 管理接口

新增 controller：

- `GET /api/gateway/route-coordination`
- `POST /api/gateway/route-coordination/refresh`
- `POST /api/gateway/route-coordination/force-activate`
- 可选 `POST /members/{id}/retire`

现有路由新增/修改/删除接口返回 revision。

### 10.5 Pub/Sub

新增 listener：

- committed：启动 prepare；
- fence：本地进入栅栏；
- activate：原子替换 current 并打开 gate；
- abort：取消 staged/fence，继续旧版。

所有消息只负责唤醒，真正决策以 Redis barrier Lua 和定时读取结果为准，避免重复消息、乱序消息或丢消息造成错误切换。

---

## 11. 测试验收清单

### 单元/集成测试

1. 新增路由：revision 增加，snapshot 包含新路由。
2. 修改路由主信息：snapshot 中整条路由为新主信息。
3. 修改条件：旧条件全部消失，新条件按 sortNo 生效，无残留。
4. 修改动作：请求与响应动作整套替换。
5. 删除路由：snapshot 中该 field 不存在。
6. 停用路由：snapshot 原始数据存在，但运行时 activeRoutes 不包含。
7. 乐观锁冲突：不推进 revision，不生成 snapshot。
8. 并发提交两次：revision 严格递增，不丢成功提交，冲突请求返回 409。
9. 全量读取一致性：提交线程持续更新时，加载 revision=N 永远得到同一份 checksum。
10. 三台中两台 READY、一台 FAILED：active 不变。
11. 三台全部 READY 后 fence：active 才变化。
12. fence 后 activate 前协调者宕机：新协调者可继续或安全 abort，不出现部分激活。
13. 切换瞬间 1000 并发：每个请求使用的路由对象内部 revision 一致；不出现一条请求混用新旧条件和动作。
14. Redis 断开：已有快照继续转发；恢复后自动追 active。
15. 实例冷启动：没有 active 快照时不 ready；加载 active 后才接流量。
16. Pub/Sub 丢消息：1s 轮询能恢复。
17. 手动 refresh：失败实例恢复后可重新推进。
18. 强制激活：有在线成员未就绪且未确认摘流时拒绝；带 ack 和摘流确认后才执行，并写审计。

### 压测观察点

- 正常配置切换 P99 增加不超过 fence 等待预算。
- gate 队列峰值和拒绝数有指标。
- 每台 activeRevision、targetRevision、checksum 可导出 metrics。
- 日志字段固定包含 revision、instanceId、barrierId、state、reason。

---

## 12. 方案取舍说明

### 为什么不是直接每台定时查库

直接轮询 `apigw:routes` 只能做到最终一致，不能保证同版本，也不能保证三台同步切换。它解决不了题目里最难的两个要求：半套集合和一旧两新。

### 为什么不逐请求查 Redis

逐请求查库会把配置存储放进转发热路径，带来网络抖动和性能抖动；即使 Redis 原子读单条路由，也不天然提供全量路由集合的同一版本。网关应在内存中读不可变快照，配置系统负责安全换页。

### 为什么推和拉都要

只用推，消息丢失或订阅断线时会漏更新；只用拉，10s 太慢、1s 也比推送慢。推送负责快，拉取负责可靠，状态以 Redis 中的 revision/barrier 为准。

### 为什么故障时坚持旧版本

新配置未被所有接流量实例确认前，它不是一个可服务的集群配置。旧版本至少是已经运行过、行为已知的完整版本；部分新版本反而会造成同一请求在不同机器结果不同，是更难排查的故障。

### 为什么强制激活仍保留

真实运维中会有机器已坏、磁盘卡死、Pod 已被摘流但心跳没清理干净的场景。自动算法不能猜，人工可以带原因决策。因此提供强制入口，但默认安全条件不放松，并留下审计。

---

## 13. 最终对外承诺

按上述方案落地后，可以明确承诺：

1. 后台一次提交的是路由完整子树；提交失败不留半条配置。
2. 网关运行时只使用不可变内存快照，转发不逐请求查库。
3. 新版本先在三台分别完整拉取和校验，任何一台失败都不激活。
4. 激活通过统一 revision 和两阶段栅栏完成，不允许一台新两台旧。
5. 切换时每个请求从头到尾使用同一个快照版本；已经在途的请求按旧版本走完，新请求栅栏后统一按新版本走。
6. 拉新失败、网络中断、Redis 短抖不会清掉旧快照，现有服务继续运行。
7. 重启后先加载 active revision 并校验，通过后才 ready；恢复后可自动追齐。
8. 手动接口可以强制刷新、查看三台当前版本和上次拉取时间，并在确认摘流后应急强制激活。
