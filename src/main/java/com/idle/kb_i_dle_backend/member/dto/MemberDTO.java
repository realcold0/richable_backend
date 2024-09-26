package com.idle.kb_i_dle_backend.member.dto;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberDTO {
    Integer uid;
    String id;
    String nickname;
    char gender;
    String email;
    String password;
    String birth_year;
    String profile;
    boolean agreement_info;
    boolean agreement_finance;
    boolean is_mentor;
    boolean is_certification;
    String auth;

    public MemberDTO(String id, String password, String nickname, char gender, String email, String birth_year) {
        this.id = id;
        this.password = password;
        this.nickname = nickname;
        this.gender = gender;
        this.email = email;
        this.birth_year = birth_year;
    }

    public List<SimpleGrantedAuthority> getAuth() {
        return Arrays.stream(this.auth.split(","))
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}