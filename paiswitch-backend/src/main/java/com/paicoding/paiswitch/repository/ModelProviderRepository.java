package com.paicoding.paiswitch.repository;

import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {

    Optional<ModelProvider> findByCodeAndTargetTool(String code, TargetTool targetTool);

    List<ModelProvider> findByTargetToolAndIsActiveTrueOrderBySortOrderAsc(TargetTool targetTool);

    List<ModelProvider> findByIsActiveTrueOrderBySortOrderAsc();

    List<ModelProvider> findByIsBuiltinTrueOrderBySortOrderAsc();

    boolean existsByCodeAndTargetTool(String code, TargetTool targetTool);
}
