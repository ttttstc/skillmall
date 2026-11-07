import { withBasePath } from '@/shared/lib/base-path'
import { cn } from '@/shared/lib/utils'

interface BrandMarkProps {
  className?: string
  imageClassName?: string
  alt?: string
}

/**
 * 统一展示市场头像，复用 public/favicon.svg 避免首页、页脚重复绘制字母占位图标。
 *
 * 图标本身是包裹盒几何图形、不含字标，因此品牌从 SkillHub 统一为 SkillMall 时不需要新视觉资源；
 * 无障碍名称跟随市场品牌。
 */
export function BrandMark({ className, imageClassName, alt = 'SkillMall' }: BrandMarkProps) {
  return (
    <span className={cn('inline-flex items-center justify-center overflow-hidden rounded-xl', className)}>
      <img
        src={withBasePath('/favicon.svg')}
        alt={alt}
        className={cn('h-full w-full object-contain', imageClassName)}
      />
    </span>
  )
}
