package com.iflytek.skillhub.domain.category;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One node of the marketplace taxonomy.
 *
 * <p>The catalogue is deliberately data, not an enum: second-level categories are created and
 * retired by category administrators at runtime, so they cannot live in code without forcing a
 * translation, an index and a filter chip to ship with every new value.
 *
 * <p>{@code level} is {@code short} because the column is {@code SMALLINT} and Hibernate runs with
 * {@code ddl-auto: validate}; a wider Java type would make the application refuse to start.
 * The "only two levels, and a level-1 row has no parent" rule is enforced by database constraints,
 * not by this class.
 */
@Entity
@Table(name = "skill_category")
public class Category {

    public static final short LEVEL_PRIMARY = 1;
    public static final short LEVEL_SECONDARY = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "level", nullable = false)
    private short level;

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    /** Retired {@code SkillDomain} wire value; only ever set on level-1 rows. */
    @Column(name = "legacy_domain", length = 32)
    private String legacyDomain;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Eager on purpose: the directory endpoint always needs the translated names, the table is
     * small (a handful of rows per level), and lazy loading here would turn one query into N.
     */
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<CategoryTranslation> translations = new ArrayList<>();

    protected Category() {
    }

    public Category(Long parentId, short level, String slug, int sortOrder, String createdBy) {
        this.parentId = parentId;
        this.level = level;
        this.slug = slug;
        this.sortOrder = sortOrder;
        this.createdBy = createdBy;
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        Instant now = Instant.now(Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now(Clock.systemUTC());
    }

    public boolean isPrimary() {
        return level == LEVEL_PRIMARY;
    }

    /**
     * Resolves the display name for the requested locale.
     *
     * <p>Fallback order is request locale → {@code zh} → {@code en} → {@code slug}. Returning the
     * slug is intentional: a raw i18n key or an empty string in the UI is worse than an
     * untranslated identifier the user can still read and search for.
     */
    public String displayName(String locale) {
        return find(locale)
                .or(() -> find("zh"))
                .or(() -> find("en"))
                .orElse(slug);
    }

    private Optional<String> find(String locale) {
        if (locale == null || translations == null) {
            return Optional.empty();
        }
        String normalized = locale.length() > 2 ? locale.substring(0, 2) : locale;
        return translations.stream()
                .filter(translation -> translation.matches(normalized))
                .map(CategoryTranslation::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst();
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public short getLevel() {
        return level;
    }

    public String getSlug() {
        return slug;
    }

    public String getLegacyDomain() {
        return legacyDomain;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<CategoryTranslation> getTranslations() {
        return translations;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
