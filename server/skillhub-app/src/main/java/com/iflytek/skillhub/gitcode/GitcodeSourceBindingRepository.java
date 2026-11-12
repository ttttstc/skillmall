package com.iflytek.skillhub.gitcode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Persistence boundary for GitCode import bindings.
 *
 * <p>Lookups go through the full binding coordinate: the same namespace may import several
 * directories of one repository, so repository alone is not an identity.</p>
 */
public interface GitcodeSourceBindingRepository extends JpaRepository<GitcodeSourceBinding, Long> {

    Optional<GitcodeSourceBinding> findByNamespaceIdAndOwnerLoginAndRepositoryNameAndSkillPath(
            Long namespaceId, String ownerLogin, String repositoryName, String skillPath);

    /**
     * Page of bindings for one namespace, newest import first. The id tie-break keeps paging stable
     * when two imports share a timestamp.
     */
    Page<GitcodeSourceBinding> findByNamespaceIdOrderByLastImportedAtDescIdDesc(
            Long namespaceId, Pageable pageable);
}
