---
name: nuwax-computer-temp-link
description: 在用户个人电脑上把本地端口代理到公网，生成外部可访问的临时链接。当用户说「创建临时链接」「让本地服务能外网访问」「把我电脑上这个端口暴露出去」「给别人一个能访问我本机服务的地址」「测试外部回调/webhook」「查看我有哪些代理链接」「删掉某个代理链接」时使用。运行环境需已注入 PLATFORM_BASE_URL、SANDBOX_ID、SANDBOX_ACCESS_KEY；云端沙箱场景改用 sandbox-temp-link。
tags: [temp-link, proxy, tunnel, nuwax, computer, local-port, webhook]
version: "1.3.0"
---

# Nuwax 个人电脑临时链接

## Overview

把**用户自己电脑上**监听的服务（如 `localhost:8080`）通过平台隧道，暴露成公网可访问的临时链接。

- 用在**用户个人电脑（智能体电脑）**上 —— 被代理的服务跑在**用户那台电脑**里，不在平台侧。
- 云端沙箱场景用 `sandbox-temp-link`；两者端点与脚本一致，只有「被代理的服务在哪台机器上」不同。
- 以下命令均在技能根目录下运行。三个能力收在一个入口脚本里，对应三个子命令：

```bash
python3 scripts/temp_link.py create --port 8080    # 创建
python3 scripts/temp_link.py list                  # 查询
python3 scripts/temp_link.py delete 1              # 删除
```

## Environment Variables

| 变量名 | 说明 |
|--------|------|
| `PLATFORM_BASE_URL` | 平台接口根地址 |
| `SANDBOX_ID` | 当前环境的沙箱配置 ID，脚本原样传给平台 |
| `SANDBOX_ACCESS_KEY` | 与平台通信的密钥，脚本自动拼 `Authorization: Bearer` |

★`SANDBOX_ID` 在个人电脑环境里**同样有值**（就是这台电脑在平台上的沙箱配置 ID）。
名字叫 SANDBOX_ID 不代表只有沙箱才有 —— 直接读环境变量，**不要自己拼。**

缺少任一变量时脚本以退出码 `1` 打印 `Missing required environment variables`，**不发请求**。

## ★先记住这五条

1. **`--host` 填的是「用户电脑本机上」的监听地址**（默认 `localhost`）。这个地址由**用户电脑侧的客户端**去连接 —— 服务必须跑在**那台电脑**上，跑在别处连不上。
2. **创建成功 ≠ 链接能打开**：本地服务得起着、客户端得在线，链接才通。两件事都要先确认。
3. **访问链接报 `502 Claw is offline`** = 用户电脑的客户端当前**没连到平台**。链接不用重建，客户端连回来即恢复。
4. **访问链接报 `404 Invalid domain`** = 这个 proxyKey 在服务端查不到（链接被改错 / 已被删除）。`create` 会校验 `sandboxId`：**不存在或不属于当前用户时直接失败**（`code="4000"`，message「配置不存在」），不会再默默建出错链 —— 但依旧只用 `$SANDBOX_ID`，别手填。
5. **删除不可逆**，而且服务端有约 **10 秒**缓存：删除后链接可能短暂仍能解析，之后彻底失效。

## Workflow

### 1. 创建代理链接

```bash
python3 scripts/temp_link.py create --port <端口号> [--host <主机地址>]
```

| 参数 | 必填 | 说明 |
|------|:----:|------|
| `--port` | ✅ | 用户电脑上监听的端口号 |
| `--host` | | 用户电脑上监听的地址，默认 `localhost` |

**示例：**
- 代理本机 8080：`python3 scripts/temp_link.py create --port 8080`
- 指定监听地址：`python3 scripts/temp_link.py create --host 127.0.0.1 --port 8080`

成功输出：
```
✅ 临时代理链接创建成功!
🔗 代理URL: 5fe712a7393f4928a7679cba2937db34.sandbox-inner.nuwao.com
🆔 链接ID: 51
📋 跟踪ID: 5443501790453110870
```

★`代理URL` 的形态由部署决定（见「链接的两种形态」），**域名与路径原样照抄、不要改写**，
只在交付时按实测补上协议头（本环境补 `http://`）。
`链接ID` 留着删除时用。

★**创建前先确认服务在监听**（如 `curl -sS http://127.0.0.1:<端口>/`），否则建出来的链接是死的。

### 2. 查询代理列表

```bash
python3 scripts/temp_link.py list [--json]
```

列出**当前这台电脑**（由 `SANDBOX_ID` 决定）的全部代理链接。成功输出：
```
✅ 找到 2 个代理链接

[1] ID: 51
    链接: 5fe712a7393f4928a7679cba2937db34.sandbox-inner.nuwao.com

[2] ID: 49
    链接: ef946ccd0dcd4aceb70d1dac6674c910.sandbox-inner.nuwao.com
```

★**列表不含别的电脑、别的用户的链接** —— 查不到不是「没建过」，先确认 `SANDBOX_ID` 是不是当前环境。

### 3. 删除代理链接

```bash
python3 scripts/temp_link.py delete <代理ID>
```

`代理ID` 取自创建输出或查询列表。成功输出：
```
✅ 代理链接删除成功!
🆔 代理ID: 1
📋 跟踪ID: trace-id-789
```

★删除按「**代理ID + 当前用户**」匹配，**不校验这台电脑归属** —— 别把别的环境下看到的 ID 拿来删。

## 链接的两种形态

平台按部署配置生成，**照原样用、不要自己改写**：

| 形态 | 出现时机 | 可用性 |
|---|---|---|
| 子域名形态 | 平台配了 `sandbox.temp-link.base-domain` | `<proxyKey>.<base-domain>` —— ★**裸域名，不带协议头** |
| 路径形态 | 平台**未配**专用域名 | `<站点地址>/api/proxy/sandbox/<proxyKey>` |

（路径里的 `sandbox` 是平台既有路径，与「个人电脑还是云沙箱」无关。）

★服务端按请求的 **Host 第一段**反查 proxyKey，所以子域名形态天然查得到；
路径形态的 Host 是站点域名、里面没有 proxyKey，它成立的前提是**网关把路径段回写成 Host**
（网关不回写就以 `404 Invalid domain` 打不开，而 `create` 照样回成功）。

★**拿到链接先自己 `curl` 一遍再交付**，两件事都要确认：

1. **补哪个协议头**：子域名形态返回的是裸域名，直接丢给用户点不开。实测本环境（2026-09-27，
   `base-domain=sandbox-inner.nuwao.com`）**HTTP 通、HTTPS 证书不匹配** —— 证书是 `CN=*.nuwao.com`，
   只覆盖一级，而链接域名是两级 `<proxyKey>.sandbox-inner.nuwao.com`。所以交付时补 `http://`。
   **换环境重新试**：`curl` 先不带 `-k` 试 `https`、再试 `http`，哪个通给哪个。
2. **代理链路通没通**：见下。

★**判「链路通没通」用同一个不存在的路径做对比**：本机直连 `curl http://127.0.0.1:<端口>/__probe__`
与经链接 `curl <链接>/__probe__`，两边**返回同形**（连 `path` 字段都原样回声同一个探针路径）
⇒ 链路已通，请求确实落到了本机服务上。返回 `{"error":{"message":"..."}}` 这种**另一种形状**，
才是卡在代理层（`Invalid domain` / `Claw is offline`）。

## Common Issues

| 现象 | 原因 | 处置 |
|---|---|---|
| `Missing required environment variables` | 环境变量缺失 | 补齐后重试；脚本未发请求 |
| 创建/查询/删除回失败，但 HTTP 是 200 | 业务失败也走 HTTP 200 | 判据看响应体的 `code`，`"0000"` 才算成功 |
| 创建报 `4000`「配置不存在」 | `SANDBOX_ID` 无效、未启用或不属于当前用户 | 只用环境注入的 `$SANDBOX_ID`；仍报错就核对环境变量是不是当前环境的 |
| 访问链接 502 `Claw is offline` | 用户电脑客户端离线 | 让客户端连上平台；**不用重建链接** |
| 访问链接 404 `Invalid domain` | proxyKey 查不到：链接错 / 已删除 / **路径形态下网关没把路径段回写成 Host** | 先核对前两种；都不是就别反复重建（见「链接的两种形态」） |
| `curl: (60) ... no alternative certificate subject name matches` | HTTPS 证书不覆盖该链接域名（本环境证书只到 `*.nuwao.com` 一级） | 改用 `http://` 访问；**不是链接坏了、不用重建** |
| `curl: (52) Empty reply from server`（HTTP 000） | 目标端口上**没有任何服务在监听** | 先把服务起在 `--port` 指定的端口；链接**不用重建**，服务起来即可用 |
| 链接通但返回 502 / 连接被拒 | 本地服务没起、或没监听在指定 `--host`/`--port` | 同上：在那台电脑上确认服务在监听 |
| `401` / `403` | `SANDBOX_ACCESS_KEY` 无效、过期或无权 | 换一把当前环境注入的 AK |
| 删除后链接还打得开几秒 | 服务端约 10 秒缓存 | 正常，稍等即失效；不是删除失败 |
| 查询列表为空 | 当前环境没有代理链接 | 确认 `SANDBOX_ID` 是否当前环境 |

## Anti-patterns

- ❌ 手填 `sandboxId` / 明文写 ID → ✅ 一律读 `$SANDBOX_ID`（写错会直接报「配置不存在」）
- ❌ 创建完就告诉用户「链接可用」 → ✅ 先确认本地服务在监听、客户端在线，再给链接
- ❌ 访问不通就删了重建 → ✅ 先看错误原文：`Claw is offline` 是客户端离线，等它连回来即可
- ❌ 把 `--host` 当成平台侧地址 / 填公网域名 → ✅ 它是**用户电脑本机**的监听地址
- ❌ 自己拼接或改写返回的链接（补斜杠、换域名） → ✅ 原样使用
- ❌ 拿别的环境（别的 `SANDBOX_ID`）里看到的 ID 来删 → ✅ 只删当前环境创建输出的 ID
- ❌ 用本技能代理云端沙箱里的服务 → ✅ 那种场景用 `sandbox-temp-link`

## References

完整字段表、响应信封、错误码、明确不存在的能力：[references/api_reference.md](references/api_reference.md)
