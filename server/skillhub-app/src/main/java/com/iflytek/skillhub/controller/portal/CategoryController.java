package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.service.CategoryDirectoryAppService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous read surface for the marketplace taxonomy.
 *
 * <p>Both endpoints are deliberately public: the category tree is rendered in the same sidebar as
 * the search box, so requiring a session would leave the filter unusable for the visitors who need
 * it most.
 */
@RestController
@RequestMapping({"/api/v1", "/api/web"})
public class CategoryController extends BaseApiController {

    private final CategoryDirectoryAppService service;

    public CategoryController(CategoryDirectoryAppService service, ApiResponseFactory responses) {
        super(responses);
        this.service = service;
    }

    @Operation(operationId = "listCategories",
            summary = "Enabled two-level category tree with publicly distributable counts")
    @GetMapping("/categories")
    public ApiResponse<CategoryDirectoryAppService.CategoryDirectoryResponse> categories() {
        return ok("response.success.read", service.directory());
    }

    @Operation(operationId = "listSkillAuthors",
            summary = "Creators that have at least one publicly distributable skill")
    @GetMapping("/skill-authors")
    public ApiResponse<CategoryDirectoryAppService.AuthorListResponse> authors(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ok("response.success.read", service.authors(q, limit));
    }
}
