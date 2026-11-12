import { describe, expect, it } from 'vitest'
import { DOMAIN_KEYS, DOMAIN_SKILL_SAMPLES, samplesForDomain } from './domain-skills-data'

describe('domain skill samples', () => {
  it('covers every domain tab of the UCD home design', () => {
    expect(DOMAIN_KEYS).toEqual([
      'design',
      'development',
      'testing',
      'engineering',
      'projectManagement',
      'other',
    ])
    for (const domain of DOMAIN_KEYS) {
      expect(samplesForDomain(domain).length).toBeGreaterThan(0)
    }
  })

  it('has unique ids and fully populated rows', () => {
    expect(new Set(DOMAIN_SKILL_SAMPLES.map(sample => sample.id)).size).toBe(DOMAIN_SKILL_SAMPLES.length)
    for (const sample of DOMAIN_SKILL_SAMPLES) {
      expect(sample.name).toBe(sample.id)
      expect(sample.summary.length).toBeGreaterThan(0)
      expect(sample.starCount).toBeGreaterThan(0)
      expect(sample.downloadCount).toBeGreaterThan(0)
    }
  })

  it('returns only the rows of the requested domain', () => {
    for (const sample of DOMAIN_SKILL_SAMPLES) {
      const rows = samplesForDomain(sample.domain)
      expect(rows).toContain(sample)
      expect(rows.every(row => row.domain === sample.domain)).toBe(true)
    }
  })
})
