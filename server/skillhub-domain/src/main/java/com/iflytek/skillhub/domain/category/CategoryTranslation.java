package com.iflytek.skillhub.domain.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;

/**
 * Translated display name for a category.
 *
 * <p>Mirrors the label system's translation table: names are data because administrators create
 * categories at runtime, and a name that only exists in code would require editing four frontend
 * locale bundles for every new category while still rendering a raw key for anything missed.
 */
@Entity
@Table(name = "skill_category_translation")
public class CategoryTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CategoryTranslation() {
    }

    public CategoryTranslation(Category category, String locale, String displayName) {
        this.category = category;
        this.locale = locale;
        this.displayName = displayName;
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

    /**
     * Matches a two-letter language tag. The column stores plain tags such as {@code zh} or
     * {@code ko}; requests may arrive as {@code zh-CN}, so only the primary subtag is compared.
     */
    boolean matches(String normalizedLocale) {
        if (locale == null || normalizedLocale == null) {
            return false;
        }
        String stored = locale.length() > 2 ? locale.substring(0, 2) : locale;
        return stored.equalsIgnoreCase(normalizedLocale);
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getLocale() {
        return locale;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
