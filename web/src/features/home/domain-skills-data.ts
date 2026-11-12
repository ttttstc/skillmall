/**
 * Placeholder data for the home "领域 Skills" section.
 *
 * The registry does not expose a domain taxonomy yet (see issue #5), so the home section
 * renders this fixed sample set to preview the UCD layout. The categories mirror the UCD
 * design exactly: 设计 / 开发 / 测试 / 工程 / 项目管理 / 其他. Replace this module with a
 * registry-backed query once skill domains exist server-side; do not treat these rows as
 * published skills.
 */

export type DomainKey =
  | 'design'
  | 'development'
  | 'testing'
  | 'engineering'
  | 'projectManagement'
  | 'other'

/** Display order of the domain tabs, matching the UCD home design. */
export const DOMAIN_KEYS: DomainKey[] = [
  'design',
  'development',
  'testing',
  'engineering',
  'projectManagement',
  'other',
]

export interface DomainSkillSample {
  id: string
  name: string
  domain: DomainKey
  summary: string
  starCount: number
  downloadCount: number
}

export const DOMAIN_SKILL_SAMPLES: DomainSkillSample[] = [
  {
    id: 'delta-design-usecase-generator',
    name: 'delta-design-usecase-generator',
    domain: 'design',
    summary: '把第三方库的平台后端适配到原生 OS API，并生成设计用例与影响面清单。',
    starCount: 126,
    downloadCount: 200,
  },
  {
    id: 'sr-design-idea',
    name: 'sr-design-idea',
    domain: 'design',
    summary: '用于生成功能设计中的 System Design，设计阶段输出模块划分与关键取舍。',
    starCount: 186,
    downloadCount: 385,
  },
  {
    id: 'ui-code-generator-security-privacy',
    name: 'ui-code-generator-security-privacy',
    domain: 'design',
    summary: '根据用户提供的设计稿生成前端代码，附带隐私与安全校验建议。',
    starCount: 186,
    downloadCount: 345,
  },
  {
    id: 'codehub-skill',
    name: 'codehub-skill',
    domain: 'development',
    summary: '对接 CodeHub 平台，批量创建、评审并合并 MR。',
    starCount: 106,
    downloadCount: 200,
  },
  {
    id: 'upgrading-open-source',
    name: 'upgrading-open-source',
    domain: 'development',
    summary: '用于升级开源库并说明上下游影响，定位破坏性改动。',
    starCount: 109,
    downloadCount: 541,
  },
  {
    id: 'huawei-arkts-coding-standard',
    name: 'huawei-arkts-coding-standard',
    domain: 'development',
    summary: '生成符合 ArkTS 编码规范的代码，覆盖命名、异常与并发约束。',
    starCount: 186,
    downloadCount: 345,
  },
  {
    id: 'auto-secodefuzz',
    name: 'auto-secodefuzz',
    domain: 'testing',
    summary: '支持 C/C++ 目标的安全模糊测试，自动收集崩溃样本并归类。',
    starCount: 106,
    downloadCount: 200,
  },
  {
    id: 'generating-verification-datasets',
    name: 'generating-verification-datasets',
    domain: 'testing',
    summary: '生成行为评测所需的数据集，附带标注规范与校验脚本。',
    starCount: 136,
    downloadCount: 300,
  },
  {
    id: 'hitest-gaia-mbt-model-generation',
    name: 'hitest-gaia-mbt-model-generation',
    domain: 'testing',
    summary: '从需求文档生成 MBT 测试模型与用例骨架，覆盖正向与异常路径。',
    starCount: 186,
    downloadCount: 945,
  },
  {
    id: 'router-hal-loses-heartbeats-diagnose',
    name: 'router-hal-loses-heartbeats-diagnose',
    domain: 'engineering',
    summary: '定位 Router HAL 心跳丢失问题，输出调用链与根因证据。',
    starCount: 186,
    downloadCount: 200,
  },
  {
    id: 'osu-rtos-package-file-analyze',
    name: 'osu-rtos-package-file-analyze',
    domain: 'engineering',
    summary: '分析 rtos 发布件中 so/bin/ko 的打包层级与 rpm 依赖关系。',
    starCount: 206,
    downloadCount: 200,
  },
  {
    id: 'udf-export-fma-log-experience',
    name: 'udf-export-fma-log-experience',
    domain: 'engineering',
    summary: '分析 UDF 导出链路的 fma_log 经验，定位异常日志与性能瓶颈。',
    starCount: 109,
    downloadCount: 200,
  },
  {
    id: 'repo-intake-and-plan',
    name: 'repo-intake-and-plan',
    domain: 'projectManagement',
    summary: '新仓库接入评估，产出范围、里程碑与风险清单。',
    starCount: 92,
    downloadCount: 168,
  },
  {
    id: 'agents-md-merger',
    name: 'agents-md-merger',
    domain: 'projectManagement',
    summary: '合并多份 AGENTS.md 与 CLAUDE.md 约定，输出统一的工程规范。',
    starCount: 174,
    downloadCount: 620,
  },
  {
    id: 'release-note-composer',
    name: 'release-note-composer',
    domain: 'projectManagement',
    summary: '按迭代整理发布说明，汇总需求、缺陷与上线检查项。',
    starCount: 88,
    downloadCount: 143,
  },
  {
    id: 'dc-local-rag',
    name: 'dc-local-rag',
    domain: 'other',
    summary: '本地搭建检索增强知识库，支持离线问答与文档同步。',
    starCount: 109,
    downloadCount: 541,
  },
  {
    id: 'skill-packager',
    name: 'skill-packager',
    domain: 'other',
    summary: '把零散的提示词与脚本打包成可发布的 SKILL.md 技能包。',
    starCount: 132,
    downloadCount: 274,
  },
  {
    id: 'weekly-digest',
    name: 'weekly-digest',
    domain: 'other',
    summary: '汇总一周内的仓库动态与讨论，生成可转发的周报。',
    starCount: 76,
    downloadCount: 118,
  },
]

/** Rows for one tab. Order is the sample order, so the tab always renders deterministically. */
export function samplesForDomain(domain: DomainKey): DomainSkillSample[] {
  return DOMAIN_SKILL_SAMPLES.filter(sample => sample.domain === domain)
}
