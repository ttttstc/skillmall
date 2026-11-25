import { describe, expect, test } from 'bun:test'
import { resolveRegistry, resolveToken } from '../../../src/services/registry-service'

describe('registry-service', () => {
  test('resolves registry by cli arg, env, config, default order', () => {
    expect(resolveRegistry({ registry: 'https://arg.example.com' }, {}, { registry: 'https://config.example.com' }))
      .toBe('https://arg.example.com')
    expect(resolveRegistry({}, { SKILLHUB_REGISTRY: 'https://env.example.com' }, { registry: 'https://config.example.com' }))
      .toBe('https://env.example.com')
    expect(resolveRegistry({}, {}, { registry: 'https://config.example.com' })).toBe('https://config.example.com')
    expect(resolveRegistry({}, {}, {})).toBe('https://skill.xfyun.cn')
  })

  test('resolves token by cli arg, env, credentials order', () => {
    expect(resolveToken({ token: 'arg' }, {}, 'stored')).toBe('arg')
    expect(resolveToken({}, { SKILLHUB_TOKEN: 'env' }, 'stored')).toBe('env')
    expect(resolveToken({}, {}, 'stored')).toBe('stored')
  })

  // A GitCode deployment takes a GitCode personal access token instead of a
  // SkillHub API token; GITCODE_TOKEN is an additional env source for the same
  // bearer credential, so it slots in after an explicit/SKILLHUB credential and
  // before the stored one.
  test('resolves a GitCode personal access token from GITCODE_TOKEN', () => {
    expect(resolveToken({}, { GITCODE_TOKEN: 'pat' }, 'stored')).toBe('pat')
    expect(resolveToken({}, { GITCODE_TOKEN: 'pat' }, undefined)).toBe('pat')
    expect(resolveToken({ token: 'arg' }, { GITCODE_TOKEN: 'pat' }, 'stored')).toBe('arg')
    expect(resolveToken({}, { SKILLHUB_TOKEN: 'env', GITCODE_TOKEN: 'pat' }, 'stored')).toBe('env')
    expect(resolveToken({}, {}, 'stored')).toBe('stored')
  })

  test('ignores blank and whitespace-only token sources', () => {
    expect(resolveToken({}, { GITCODE_TOKEN: '   ' }, 'stored')).toBe('stored')
    expect(resolveToken({}, { GITCODE_TOKEN: '' }, 'stored')).toBe('stored')
    expect(resolveToken({ token: '' }, { GITCODE_TOKEN: 'pat' }, undefined)).toBe('pat')
    expect(resolveToken({}, {}, undefined)).toBeUndefined()
  })

  // Tokens pasted from a web page or a CI secret often carry a trailing newline;
  // credentials must not follow a secret and a whitespace proxy into the header.
  test('trims surrounding whitespace from a resolved token', () => {
    expect(resolveToken({ token: '  arg\n' }, {}, 'stored')).toBe('arg')
    expect(resolveToken({}, { GITCODE_TOKEN: ' pat\n' }, 'stored')).toBe('pat')
    expect(resolveToken({}, {}, ' stored \n')).toBe('stored')
  })
})
