# OBS 前置资源：桶初始化、最小权限策略、连通性自检

SkillHub 的 OBS 存储适配器（`provider=obs`）使用 **OBS 原生协议**，不是 S3 兼容接口：

* 签名：OBS 自己的 **HMAC-SHA1**（不是 AWS SigV4）；临时凭证还要在 URL 上带 `x-obs-security-token`
* 寻址：虚拟主机式 `<bucket>.obs.<region>.myhuaweicloud.com`
* 配置：`endpoint=https://obs.<region>.myhuaweicloud.com`、`region=<region>`、预签名有效期默认 `PT10M`
* 应用**不建桶**（`autoCreateBucket=false`），桶必须预先创建；应用只做对象级读写

所以桶的创建与桶级配置由管理员用本目录的脚本完成。

| 文件 | 作用 |
| --- | --- |
| `setup-bucket.sh` | 幂等创建**私有**桶；可选版本控制、CORS、诊断前缀过期；配置"清理未完成分段上传"生命周期 |
| `verify-obs.sh` | 小对象往返自检（写 4KB → 读回比对 → 列前缀 → 删 → 确认已删）；`--check-policy` 验证匿名读被拒、越权桶被拒 |
| `iam-policy-skillhub-app.json` | 给**应用** AK 的最小权限 IAM 自定义策略（仅限一个桶） |
| `obsutil.conf.example` | obsutil 配置模板（占位值，勿提交真实密钥） |

两个脚本都要求 `bash`（`set -euo pipefail`），中文 `--help`，默认**不写** `~/.obsutilconfig`。

---

## 1. 两种凭证模式怎么选

CCE 上给应用取 OBS 凭证有两条路，本目录脚本对两者都适用（脚本用的是**管理员**凭证做资源准备）：

| | ① IAM 委托 + 元数据服务临时凭证 | ② 静态 AK/SK 放 Secret |
| --- | --- | --- |
| 怎么拿 | Pod 内经 `http://169.254.169.254/openstack/latest/securitykey` 取 `{"credential":{"access","secret","securitytoken"}}` | Secret 里的固定 AK/SK |
| 过期 | 自动轮换（分钟级），需处理刷新 | 不过期，直到手动轮换 |
| 泄露风险 | 低（凭证短命、绑定 Pod 身份） | 高（Secret 可被读、易进镜像/日志） |
| 推荐度 | **推荐** | 只在无法用委托时使用 |

两种模式下**权限策略是同一份** `iam-policy-skillhub-app.json`：委托（agency）绑它，IAM 用户也绑它。

脚本侧对应：
* 临时凭证：给脚本传 `OBS_SESSION_TOKEN`（就是 `securitytoken`），脚本会写进配置的 `token` 字段
* 静态凭证：只传 `OBS_AK` / `OBS_SK`

> 注意：临时凭证有有效期。管理员用临时凭证跑完初始化后，**跑自检时最好用应用真正使用的那套凭证**，这样验证的才是应用实际拥有的权限。

---

## 2. 桶名与区域约束

* **桶名全局唯一**（整个 OBS 服务，不只是你的账号），且**创建后不能改名**。建议带上环境与用途，如 `skillhub-prod-obs`、`skillhub-test-obs`
* 桶名规则：3~63 字符，只能小写字母/数字/连字符/点，首尾必须是字母或数字，不能是 IP 形式，不能有连续 `..`（脚本会先校验）
* **区域创建后不可改**；`endpoint` 必须与桶区域一致，否则访问会 301/重定向（脚本会把这类失败单独判出来）
* 同一账号不同环境请用**不同桶**，不要靠前缀区分：IAM 策略与生命周期都按桶配置，混用会让权限收敛变难

---

## 3. 安装 obsutil

脚本用华为云官方 `obsutil`（与适配器同协议），不自己实现签名。

```bash
# Linux x86_64（CCE 跳板机/运维机常见）
curl -L -o obsutil.tar.gz \
  https://obs-community.obs.cn-north-1.myhuaweicloud.com/obsutil/current/obsutil_linux_amd64.tar.gz
tar -xzf obsutil.tar.gz
./obsutil version
```

其它平台（arm64 / macOS / Windows）到 OBS 工具页下载对应包。装好后可放进 PATH，或用 `OBSUTIL_BIN=/path/to/obsutil` 指定。

校验：`obsutil version` 能打印版本即可；`obsutil help` 能看到 `lifecycle`、`cors`（本脚本用到的子命令）。

---

## 4. 命令示例

```bash
export OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
export OBS_REGION=cn-north-4
export OBS_BUCKET=skillhub-prod-obs
export OBS_AK=<管理员 AK>  OBS_SK=<管理员 SK>

# 1) 先看要执行什么（本机没装 obsutil 也能跑，不写任何文件）
./deploy/cce/obs/setup-bucket.sh --dry-run

# 2) 真正执行：建私有桶 + 清理未完成分段上传的生命周期
./deploy/cce/obs/setup-bucket.sh

# 3) 需要时再加：版本控制、诊断前缀 7 天过期、浏览器直连的 CORS
./deploy/cce/obs/setup-bucket.sh --versioning --expire-diagnostics 7
OBS_CORS_ORIGINS=https://skillhub.example.com ./deploy/cce/obs/setup-bucket.sh --cors

# 4) 连通性 + 最小权限自检
OBS_FORBIDDEN_BUCKET=<另一个桶> ./deploy/cce/obs/verify-obs.sh --check-policy
```

`setup-bucket.sh` 的幂等行为（可反复执行）：

* 桶已存在 → 只核对可访问性与区域，**不重建、不清空、不轮换密钥**
* 版本控制已开启 → 跳过；生命周期已有"清理未完成分段上传" → 跳过
* 桶上已有**别人配的**生命周期规则 → 默认**不覆盖**（只告警），要整份替换才加 `--force-lifecycle`
* 任何一步都不删对象、不删桶、不改 ACL、不写桶策略

凭证落地方式（默认最安全的一项）：

* 默认 `--creds=isolated`：写进一次运行的临时 `HOME`（`0600`），退出即删，**不碰** `~/.obsutilconfig`
* `--creds=cli`：用 `-i/-k/-t` 传参，**不落地任何文件**（密钥会短暂出现在进程列表，适合受控机器）
* `--write-config`：显式写 `~/.obsutilconfig`（影响该用户后续所有 obsutil 调用，会先备份）
* `--config-file <路径>`：复用已有配置（复制进临时 HOME，原文件不改）

---

## 5. IAM 策略：动作名与**需核对清单**

`iam-policy-skillhub-app.json` 给应用的最小权限，只允许一个桶：

* 对象面：`GetObject` / `PutObject` / `DeleteObject` / `ListMultipartUploadParts` / `AbortMultipartUpload`
* 桶面：只有 `ListBucket` + `GetBucketLocation`
* 另加一条 **Deny**，显式挡掉桶管理类高危动作（删桶、改 ACL、改桶策略、改生命周期、改版本控制、加密、网站、列出账号全部桶）

使用前把 `REPLACE-WITH-OBS-BUCKET` 全部替换成真实桶名（共 4 处：对象 ARN、桶 ARN，以及 Deny 里的这两条），再在 IAM 控制台"策略 → 创建自定义策略"粘贴，然后授权给应用的 IAM 用户/委托。

**以下无法从公开文档确证，落地前请在 IAM 控制台"权限模拟"或用 `--check-policy` 实测，不要当成已确认结论：**

1. **资源 URN 形态** `obs:*:*:object:<bucket>/*` / `obs:*:*:bucket:<bucket>`：区域、账号段写 `*` 是否被接受需核对
2. **动作名拼写**：`obs:object:*` / `obs:bucket:*` 前缀与 PASCAL 大小写（如 `ListMultipartUploadParts`、`GetBucketLocation`）需与目标区域 IAM 实际动作表对齐
3. **多段上传是否还需单独动作**：`InitiateMultipartUpload` / `UploadPart` / `CompleteMultipartUpload` 在部分 IAM 视图里可能未被 `PutObject` 覆盖
4. **`ListAllMyBuckets` 是否真能 Deny**：该动作多为账号级，Deny 生效范围需核对
5. **`Version` 字段**取值 `1.1` 是否适用于你的 IAM 版本
6. **版本控制开启后**：删除对象只产生 delete marker，如需清理历史版本还要 `obs:object:DeleteObjectVersion`（本策略**未**包含，按需评估）

`verify-obs.sh --check-policy` 是这套策略的**行为级**验证：匿名读必须 403，越权桶必须被拒。

---

## 6. 排障要点

### 预签名 URL 过期（`PT10M`）

症状：下载链接一点击就 403，或 `Request has expired`。

* 预签名默认有效期 **10 分钟**，从**生成时刻**算；不是从点击时刻算。慢网络/大文件/图片懒加载都可能超过
* 生成预签名 URL 的机器时间必须准（NTP）。时钟漂移会让签名一开始就"已过期"或"尚未生效"
* 预签名 URL 里带 `x-obs-security-token`：**临时凭证轮换后旧 URL 立即失效**，即使还没到 10 分钟
* URL 不要二次编码/改写：签名覆盖了具体路径与参数，任何转义变化都会 403
* 排查顺序：①本机时间 ②凭证是否刚轮换 ③是否超过 10 分钟 ④URL 是否被改写

### 临时凭证过期

症状：突然大面积 403（不是个别对象），日志里出现凭证/令牌相关错误。

* 元数据服务取到的临时凭证有有效期（通常十几分钟到一小时），需在过期前刷新
* 取凭证的地址：`http://169.254.169.254/openstack/latest/securitykey`，JSON 为 `{"credential":{"access","secret","securitytoken"}}`
* 三条容易踩的坑：
  1. **只在启动时取一次** → 跑一阵后全部 403；应按过期时间提前刷新
  2. **只换了 access/secret，没换 token** → 签名值对不上（token 也参与签名）
  3. **预签名 URL 用旧凭证生成** → 用户点击时必然 403
* 快速判定：用同一份凭证跑 `verify-obs.sh`。若自检也 403，是凭证/权限问题；自检通过但应用 403，问题在应用侧（取凭证逻辑、时钟、预签名参数）

### 其他常见失败

* `301 / PermanentRedirect`：`endpoint` 与桶所在区域不一致，用桶的真实区域
* `403` 且 `--check-policy` 通过：IAM 策略限定的桶名与实际桶名不一致
* `404 NoSuchBucket`：桶名拼错，或桶在另一个账号（桶名全局唯一，可能已被别人占用）
* 匿名读返回 200：桶/对象可公开读，**严重问题**，需把 ACL/桶策略改回私有

---

## 7. 验证状态（如实说明）

* 已做：`bash -n` 两个脚本全部通过；`--dry-run` 在**没有 obsutil** 的机器上可独立跑通并打印完整命令
* 已做：用本地假 `obsutil`/假 `curl` 覆盖了分支逻辑——桶已存在（跳过创建）、生命周期已含规则（跳过）、桶上已有他人规则（不改写）、`--force-lifecycle`（覆盖）、obsutil 不支持某子命令（明确告警并给手工步骤）、往返一致（6 项 PASS）、下载被篡改（FAIL 且非零退出）、匿名 403/200/404/网络不可达四种响应、越权桶可访问/不可访问
* **未做：没有对真实 OBS 跑过任何往返**（本机无 obsutil、无网络、无凭证）。首次在真实环境执行时请先 `--dry-run`，再 `setup-bucket.sh`，最后 `verify-obs.sh --check-policy`
* 未覆盖：预签名 URL 的生成与过期行为（属应用侧 OBS 原生签名，脚本不复制该签名逻辑）
