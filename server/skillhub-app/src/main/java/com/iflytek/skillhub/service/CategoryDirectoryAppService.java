package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.category.Category;
import com.iflytek.skillhub.domain.category.CategoryDirectoryService;
import com.iflytek.skillhub.repository.CategoryDirectoryQueryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP-facing use case for the category directory and the creator picker.
 *
 * <p>Count semantics: a level-1 node reports the number of skills a click on it would return,
 * i.e. its own direct skills <em>plus</em> everything filed under its children. A level-2 node
 * reports only its own. Reporting a bare direct count on the parent would show "0" next to a
 * branch that is obviously full, which is the classic way a category tree loses trust.
 */
@Service
public class CategoryDirectoryAppService {

    private final CategoryDirectoryService directory;
    private final CategoryDirectoryQueryRepository queries;

    public CategoryDirectoryAppService(CategoryDirectoryService directory,
                                       CategoryDirectoryQueryRepository queries) {
        this.directory = directory;
        this.queries = queries;
    }

    public record CategoryNode(Long id, String slug, String displayName, int sortOrder, long count,
                               List<CategoryNode> subCategories) {
    }

    public record CategoryDirectoryResponse(List<CategoryNode> categories) {
    }

    public record AuthorItem(String userId, String displayName, String avatarUrl, long skillCount) {
    }

    public record AuthorListResponse(List<AuthorItem> items) {
    }

    @Transactional(readOnly = true)
    public CategoryDirectoryResponse directory() {
        String locale = LocaleContextHolder.getLocale().getLanguage();

        // One aggregate row per (category, sub-category) bucket, folded into the tree below.
        Map<Long, Long> directByCategory = new HashMap<>();
        Map<Long, Long> bySubCategory = new HashMap<>();
        for (CategoryDirectoryQueryRepository.CategoryCount row : queries.countPublicSkills()) {
            if (row.subCategoryId() == null) {
                directByCategory.merge(row.categoryId(), row.total(), Long::sum);
            } else {
                bySubCategory.merge(row.subCategoryId(), row.total(), Long::sum);
            }
        }

        List<CategoryNode> nodes = new ArrayList<>();
        for (CategoryDirectoryService.CategoryBranch branch : directory.tree()) {
            Category primary = branch.primary();
            List<CategoryNode> children = new ArrayList<>(branch.children().size());
            long rollup = directByCategory.getOrDefault(primary.getId(), 0L);
            for (Category child : branch.children()) {
                long childCount = bySubCategory.getOrDefault(child.getId(), 0L);
                rollup += childCount;
                children.add(new CategoryNode(child.getId(), child.getSlug(),
                        child.displayName(locale), child.getSortOrder(), childCount, List.of()));
            }
            nodes.add(new CategoryNode(primary.getId(), primary.getSlug(),
                    primary.displayName(locale), primary.getSortOrder(), rollup, children));
        }
        return new CategoryDirectoryResponse(nodes);
    }

    /**
     * Creators with at least one publicly distributable skill. Deliberately not a general user
     * search: exposing every account to anonymous callers would leak the org chart.
     */
    @Transactional(readOnly = true)
    public AuthorListResponse authors(String query, int limit) {
        List<AuthorItem> items = queries.findPublicAuthors(query, limit).stream()
                .map(row -> new AuthorItem(row.userId(), row.displayName(), row.avatarUrl(),
                        row.skillCount()))
                .toList();
        return new AuthorListResponse(items);
    }
}
