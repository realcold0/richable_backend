package com.idle.kb_i_dle_backend.income.repository;

import com.idle.kb_i_dle_backend.income.entity.Income;
import com.idle.kb_i_dle_backend.member.entity.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IncomeRepository extends CrudRepository<Income, Integer> {
    // 소득 전체 조회
    List<Income> findByUid(User uid);
    
    // 특정 index값의 소득 조회
    Optional<Income> findByIndex(@Param("index")Integer index);

    // 특정 index값의 소득 삭제
    void deleteByIndex(@Param("index")Integer index);
}
