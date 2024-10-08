package com.idle.kb_i_dle_backend.domain.member.service;

import com.idle.kb_i_dle_backend.domain.member.dto.*;
import com.idle.kb_i_dle_backend.domain.member.entity.Member;
import com.idle.kb_i_dle_backend.domain.member.entity.MemberAPI;
import com.idle.kb_i_dle_backend.domain.member.exception.MemberException;
import com.idle.kb_i_dle_backend.domain.member.repository.MemberRepository;
import com.idle.kb_i_dle_backend.domain.member.util.JwtProcessor;
import com.idle.kb_i_dle_backend.global.codes.ErrorCode;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.security.SecureRandom;
import java.math.BigInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuthenticationManager authenticationManager;
    private final JwtProcessor jwtProcessor;
    private final MemberInfoService memberInfoService;
    private final MemberApiService memberApiService;

    @Value("${naver.client.id}")
    private String clientId;

    @Value("${naver.client.secret}")
    private String clientSecret;

    @Value("${naver.redirect.uri}")
    private String redirectUri;

    private Map<String, String> verificationCodes = new HashMap<>();

    @Override
    public Map<String, Object> login(LoginDTO loginDTO) {
        try {
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(loginDTO.getId(), loginDTO.getPassword());
            Authentication authentication = authenticationManager.authenticate(authenticationToken);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            CustomUser customUser = (CustomUser) authentication.getPrincipal();
            MemberDTO member = customUser.getMember();
            MemberInfoDTO userInfo = new MemberInfoDTO(member.getUid(), member.getId(),
                    member.getEmail(), member.getNickname(), member.getAuth().toString());

            String jwtToken = jwtProcessor.generateToken(userInfo.getId(), userInfo.getUid(), userInfo.getNickname(),
                    userInfo.getEmail());

            Map<String, Object> result = new HashMap<>();
            result.put("token", jwtToken);
            result.put("userInfo", userInfo);
            return result;
        } catch (Exception e) {
            log.error("Authentication failed: ", e);
            throw new MemberException(ErrorCode.USER_ALREADY_EXISTS);
        }
    }

    public String generateState() {
        SecureRandom random = new SecureRandom();
        return new BigInteger(130, random).toString(32);
    }

    @Override
    public Map<String, Object> initiateNaverLogin(HttpServletRequest request) throws Exception {
        String state = generateState();
        HttpSession session = request.getSession();
        session.setAttribute("naverState", state);

        String naverAuthUrl = "https://nid.naver.com/oauth2.0/authorize?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                + "&state=" + state;

        Map<String, Object> result = new HashMap<>();
        result.put("redirectUrl", naverAuthUrl);
        return result;
    }

    @Override
    public Map<String, Object> processNaverCallback(String code, String state, HttpServletRequest request) throws Exception {
        String accessToken = getNaverAccessToken(code, state);
        JSONObject userProfile = getUserProfile(accessToken);
        log.error("userProfile check"+userProfile);

        JSONObject responseObj = userProfile.getJSONObject("response");
        String email = responseObj.getString("email");
        String nickname = responseObj.getString("nickname");

        Member member = memberRepository.findByEmail(email);
        if (member == null) {
            member = createNaverMember(email, nickname);
        }

        String jwt = jwtProcessor.generateToken(member.getId(), member.getUid(), member.getNickname(), member.getEmail());

        Map<String, Object> result = new HashMap<>();
        result.put("token", jwt);
        result.put("redirectUrl", "http://localhost:5173");
        return result;
    }

    private String getHttpResponse(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        return response.toString();
    }

    private String getNaverAccessToken(String code, String state) throws Exception {
        String tokenUrl = "https://nid.naver.com/oauth2.0/token";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("grant_type", "authorization_code");
        map.add("client_id", clientId);
        map.add("client_secret", clientSecret);
        map.add("code", code);
        map.add("state", state);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

        JSONObject jsonObject = new JSONObject(response.getBody());
        return jsonObject.getString("access_token");
    }

    private JSONObject getUserProfile(String accessToken) throws Exception {
        String profileUrl = "https://openapi.naver.com/v1/nid/me";
        URL url = new URL(profileUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Authorization", "Bearer " + accessToken);

        int responseCode = con.getResponseCode();
        BufferedReader br;
        if (responseCode == 200) {
            br = new BufferedReader(new InputStreamReader(con.getInputStream()));
        } else {
            br = new BufferedReader(new InputStreamReader(con.getErrorStream()));
        }
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = br.readLine()) != null) {
            response.append(inputLine);
        }
        br.close();

        return new JSONObject(response.toString());
    }
    private Member createNaverMember(String email, String nickname) {
        MemberJoinDTO memberJoinDTO = MemberJoinDTO.builder()
                .id(email.split("@")[0])
                .password("1234*") // 임의의 비밀번호 생성
                .email(email)
                .nickname(nickname)
                .gender('M') // 기본값, 실제로는 네이버 API에서 제공하는 정보를 사용해야 합니다.
                .birth_year(2022) // 기본값, 실제로는 네이버 API에서 제공하는 정보를 사용해야 합니다.
                .auth("ROLE_MEMBER")
                .agreementInfo(false)
                .agreementFinance(false)
                .build();

        Member member = Member.from(memberJoinDTO);
        member.setPassword(passwordEncoder.encode(member.getPassword())); // 비밀번호 암호화

        return memberRepository.save(member);
    }

    @Override
    public String registerMember(MemberJoinDTO signupDTO) {
        try {
            MemberJoin(signupDTO);
            return "User registered successfully";
        } catch (IllegalStateException e) {
            throw new MemberException(ErrorCode.USER_ALREADY_EXISTS);
        } catch (Exception e) {
            throw new MemberException(ErrorCode.REGISTRATION_FAILED);
        }
    }

    @Override
    public boolean checkDupl(String id) {
        return memberRepository.existsById(id);
    }

    @Override
    public Member findMemberByUid(int id) {
        try {
            Member member = memberRepository.findByUid(id);
            if (member == null) {
                throw new MemberException(ErrorCode.INVALID_MEMEBER);
            }
            return member;
        } catch (Exception e) {
            throw new MemberException(ErrorCode.INVALID_MEMEBER, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void MemberJoin(MemberJoinDTO memberjoindto) {
        try {
            log.debug("Starting MemberJoin process for ID: {}", memberjoindto.getId());

            if (memberjoindto.isAgreementInfo()) {
                memberjoindto.setAgreementInfo(true);
            } else {
                memberjoindto.setAgreementInfo(false);
            }
            if (memberjoindto.isAgreementFinance()) {
                memberjoindto.setAgreementFinance(true);
            } else {
                memberjoindto.setAgreementFinance(false);
            }
            if (memberjoindto.getAuth() == null || memberjoindto.getAuth().isEmpty()) {
                memberjoindto.setAuth("ROLE_MEMBER");
            }

            log.debug("Checking if user exists");
            if (memberRepository.existsById(memberjoindto.getId())) {
                throw new IllegalStateException("User already exists");
            }

            log.debug("Validating nickname");
            if (memberjoindto.getNickname() == null || memberjoindto.getNickname().length() > 50) {
                throw new IllegalArgumentException("Nickname must not be null and should not exceed 50 characters");
            }

            log.debug("Validating ID");
            if (memberjoindto.getId() == null || memberjoindto.getId().isEmpty()) {
                throw new IllegalStateException("User ID is required");
            }

            log.debug("Encoding password");
            String encodePassword = passwordEncoder.encode(memberjoindto.getPassword());

            log.debug("Building User entity");
            Member newMember = Member.builder()
                    .id(memberjoindto.getId())
                    .password(encodePassword)
                    .nickname(memberjoindto.getNickname())
                    .gender(String.valueOf(memberjoindto.getGender()))
                    .email(memberjoindto.getEmail())
                    .birth_year(memberjoindto.getBirth_year())
                    .auth(memberjoindto.getAuth())

                    .agreementInfo(memberjoindto.isAgreementInfo())
                    .agreementFinance(memberjoindto.isAgreementFinance())
                    .build();

            log.debug("Saving new user: {}", newMember);
            memberRepository.save(newMember);
            log.debug("User saved successfully");
        } catch (Exception e) {
            log.error("Error in MemberJoin: ", e);
            throw e;
        }
    }

    @Override
    public boolean checkAgree(boolean info, boolean finance, String id) {
        return true;
    }

    @Override
    public boolean updateUserAgreement(String id, Map<String, Boolean> agreementData) {
        boolean info = agreementData.get("info");
        boolean finance = agreementData.get("finance");
        boolean result = checkAgree(info, finance, id);
        return result;
    }

    private String generateRandomCode() {
        // 6자리 랜덤 숫자 생성 로직
        return String.format("%06d", new Random().nextInt(1000000));
    }

    @Override
    public String generateAndSaveVerificationCode(String email) {
        String verificationCode = generateRandomCode();
        verificationCodes.put(email, verificationCode);

        // 이메일 전송
        String subject = "Richable 인증 코드";
        String text = "귀하의 인증 코드는 " + verificationCode + " 입니다.";
        emailService.sendSimpleMessage(email, subject, text);

        return verificationCode;
    }

    @Override
    public Map<String, String> findIdByEmail(String email) {
        Map<String, String> result = new HashMap<>();
        try {
            Member user = memberRepository.findByEmail(email);
            if (user != null) {
                generateAndSaveVerificationCode(email);
                result.put("message", "인증 코드가 이메일로 전송되었습니다.");
            } else {
                result.put("error", "해당 이메일로 등록된 사용자가 없습니다.");
            }
        } catch (Exception e) {
            log.error("Error in findIdByEmail: ", e);
            result.put("error", "서버 오류가 발생했습니다.");
        }
        return result;
    }

    @Override
    public Map<String, Object> verifyCode(String email, String code) {
        Map<String, Object> result = new HashMap<>();
        try {
            String savedCode = verificationCodes.get(email);
            if (savedCode != null && savedCode.equals(code)) {
                Member member = memberRepository.findByEmail(email);
                if (member != null) {
                    result.put("verified", true);
                    result.put("id", member.getId());
                    result.put("message", "인증이 성공적으로 완료되었습니다.");
                } else {
                    result.put("verified", false);
                    result.put("message", "해당 이메일로 등록된 사용자를 찾을 수 없습니다.");
                }
            } else {
                result.put("verified", false);
                result.put("message", "인증 코드가 일치하지 않습니다.");
            }
        } catch (Exception e) {
            log.error("Error in verifyCode: ", e);
            result.put("verified", false);
            result.put("message", "서버 오류가 발생했습니다.");
        }
        return result;
    }

    @Override
    public String findPwByEmail(String email) {
        String id = memberRepository.findByEmail(email).getId();
        if (id != null) {
            generateAndSaveVerificationCode(email);
            String result = "인증 코드가 이메일로 전송되었습니다.";
            return result;
        } else {
            throw new MemberException(ErrorCode.EMAIL_NOT_FOUND);
        }
    }

    @Override
    public boolean resetPassword(String id, String newPassword) {
        Optional<Member> userOptional = memberRepository.findById(id);
        // Optional에서 실제 Member 객체를 추출
        Member member = userOptional.orElseThrow(() -> new EntityNotFoundException("Member not found with id: " + id));

        // 비밀번호 암호화 후 업데이트
        String encodedPassword = passwordEncoder.encode(newPassword);
        member.setPassword(encodedPassword);

        // 업데이트된 객체를 저장
        memberRepository.save(member);
        return true;
    }

    @Override
    public Map<String, Object> getMemberInfoByToken(String token) {
        log.info("Fetching member info by token");
        try {
            String nickname = jwtProcessor.getNickname(token);
            Integer uid = jwtProcessor.getUid(token);
            log.info("Fetching info for nickname: {} and uid: {}", nickname, uid);

            MemberInfoDTO memberInfoDTO = memberInfoService.getUserInfoByNickname(nickname);
            if (memberInfoDTO == null) {
                throw new MemberException(ErrorCode.MEMBER_NOT_FOUND, "User information not found");
            }

            MemberAPI memberAPI = memberApiService.getMemberApiByUid(uid);
            if (memberAPI == null) {
                throw new MemberException(ErrorCode.MEMBER_NOT_FOUND, "API data not found");
            }

            Map<String, Object> stockInfo = new HashMap<>();
            stockInfo.put("base", memberAPI.getStock());
            stockInfo.put("token", memberAPI.getStockToken());
            stockInfo.put("secret", memberAPI.getStockSecret());
            stockInfo.put("app", memberAPI.getStockApp());

            Map<String, Object> coinInfo = new HashMap<>();
            coinInfo.put("base", memberAPI.getCoin());
            coinInfo.put("secret", memberAPI.getCoinSecret());
            coinInfo.put("app", memberAPI.getCoinApp());

            Map<String, Object> apiInfo = new HashMap<>();
            apiInfo.put("bank", memberAPI.getBank());
            apiInfo.put("stock", stockInfo);
            apiInfo.put("coin", coinInfo);

            Map<String, Object> data = new HashMap<>();
            data.put("nickname", memberInfoDTO.getNickname());
            data.put("email", memberInfoDTO.getEmail());
            data.put("img", memberInfoDTO.getImg());
            data.put("birthYear", memberInfoDTO.getBirthYear());
            data.put("gender", memberInfoDTO.getGender());
            data.put("certification", memberInfoDTO.isCertification());
            data.put("api", apiInfo);

            Map<String, Object> response = new HashMap<>();
            response.put("data", data);

            log.info("Successfully fetched member info for nickname: {}", nickname);
            return response;
        } catch (MemberException e) {
            log.error("Failed to fetch member info: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error occurred while fetching member info: {}", e.getMessage());
            throw new MemberException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to fetch member info");
        }
    }

    @Override
    public Map<String, Object> updateMemberInfo(Map<String, Object> updatedInfo, String token) {
        log.info("Updating member info");
        try {
            String tokenNickname = jwtProcessor.getNickname(token);

            // Verify that the token nickname matches the nickname in updatedInfo
            String updatedNickname = (String) updatedInfo.get("nickname");
            if (!tokenNickname.equals(updatedNickname)) {
                throw new MemberException(ErrorCode.INVALID_UNAUTHOR, "You can only update your own information");
            }

            // Find the existing member
            Member member = memberRepository.findByNickname(tokenNickname);

            // Update member information
            if (updatedInfo.containsKey("email")) {
                member.setEmail((String) updatedInfo.get("email"));
            }
            if (updatedInfo.containsKey("birthYear")) {
                member.setBirth_year((Integer) updatedInfo.get("birthYear"));
            }
            if (updatedInfo.containsKey("gender")) {
                member.setGender((String) updatedInfo.get("gender"));
            }
            if (updatedInfo.containsKey("img")) {
                member.setProfile((String) updatedInfo.get("img"));
            }

            // Save updated member
            Member updatedMember = memberRepository.save(member);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("nickname", updatedMember.getNickname());
            response.put("email", updatedMember.getEmail());
            response.put("img", updatedMember.getProfile());
            response.put("birthYear", updatedMember.getBirth_year());
            response.put("gender", updatedMember.getGender());

            log.info("Successfully updated member info for nickname: {}", tokenNickname);
            return response;
        } catch (MemberException e) {
            log.error("Failed to update member info: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating member info: {}", e.getMessage());
            throw new MemberException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to update member info");
        }
    }

    @Override
    public boolean deleteMemberById(String nickname) {
        log.info("Attempting to delete member with nickname: {}", nickname);
        try {
            Member memberOptional = memberRepository.findByNickname(nickname);

            memberRepository.delete(memberOptional);
            log.info("Successfully deleted member with nickname: {}", nickname);

            return true;
        } catch (MemberException e) {
            log.error("Failed to delete member with nickname: {}. Error: {}", nickname, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error occurred while deleting member with nickname: {}. Error: {}", nickname,
                    e.getMessage());
            throw new MemberException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete member");
        }
    }

    @Override
    public MemberDTO findByEmail(String email) {
        log.info("Attempting to find member by email: {}", email);
        try {
            Member member = memberRepository.findByEmail(email);
            if (member == null) {
                log.info("No member found with email: {}", email);
                return null;
            }

            MemberDTO memberDTO = MemberDTO.builder()
                    .uid(member.getUid())
                    .id(member.getId())
                    .email(member.getEmail())
                    .nickname(member.getNickname())
                    .gender(member.getGender().charAt(0))
                    .birth_year(String.valueOf(member.getBirth_year()))
                    .profile(member.getProfile())
                    .agreement_info(member.getAgreementInfo())
                    .agreement_finance(member.getAgreementFinance())
                    .is_mentor(member.getIsMentor())
                    .is_certification(member.getIsCertification())
                    .auth(member.getAuth())
                    .build();

            log.info("Successfully found and mapped member with email: {}", email);
            return memberDTO;
        } catch (Exception e) {
            log.error("Unexpected error occurred while finding member by email: {}. Error: {}", email, e.getMessage());
            throw new MemberException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to find member by email");
        }
    }
}