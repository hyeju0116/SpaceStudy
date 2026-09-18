package com.example.StudyWithMe.reservation;

import com.example.StudyWithMe.ai.AiAssignmentResponseDTO;
import com.example.StudyWithMe.ai.AiParameterDTO;
import com.example.StudyWithMe.ai.GeminiService;
import com.example.StudyWithMe.config.SessionUtil;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
@RestController
@RequestMapping("/api/reservation-tasks")
@RequiredArgsConstructor
public class ReservationController {

    private final GeminiService geminiService;
    private final ReservationService reservationService; // 서비스명 통일

    // 스터디장 전용: 스터디 컨텍스트 내에서 AI 기능을 다루는 컨트롤러
    @PostMapping("/generate-ai")
    public ResponseEntity<?> getAiSuggestion(
            @RequestBody AiParameterDTO request,
            HttpSession session // 💡 변경: HttpSession 주입
    ) {
        Long userId = SessionUtil.getLoginUserId(session); // 💡 변경: 세션 유틸 적용

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "code", "LOGIN_REQUIRED",
                            "message", "로그인이 필요합니다."
                    ));
        }

        try {
            AiAssignmentResponseDTO suggestion = geminiService.generateAssignment(request);
            return ResponseEntity.ok(suggestion);

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            System.err.println("HTTP 상태 코드: " + e.getStatusCode());
            System.err.println("에러 본문: " + e.getResponseBodyAsString());
            throw new RuntimeException("AI API 호출 실패: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("알 수 없는 오류 발생: " + e.getMessage());
        }
    }

    @PostMapping("/{studyId}/confirm-ai")
    public ResponseEntity<?> confirmAiAssignment(
            @PathVariable Long studyId,
            @RequestBody ReservationRequestDTO dto,
            HttpSession session // 💡 변경: HttpSession 주입
    ) {
        System.out.println("세션 ID: " + session.getId());
        System.out.println("세션 내 유저 ID: " + session.getAttribute("YOUR_SESSION_KEY"));

        Long userId = SessionUtil.getLoginUserId(session);

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "로그인이 필요합니다."));
        }

        try {
            Long assignmentId = reservationService.createReservationTask(
                    studyId,
                    dto,
                    userId // 💡 변경: 추출한 userId 전달
            );

            return ResponseEntity.ok(Map.of(
                    "message", "AI 제안 과제가 성공적으로 출제되었습니다.",
                    "assignmentId", assignmentId
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "code", "NOT_LEADER",
                            "message", e.getMessage()
                    ));
        } catch (IllegalArgumentException e) {
            String errorCode = "INVALID_INPUT";
            if (e.getMessage().contains("마감 기한")) errorCode = "INVALID_DUE_DATE";
            if (e.getMessage().contains("스터디")) errorCode = "STUDY_NOT_FOUND";

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "code", errorCode,
                            "message", e.getMessage()
                    ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "code", "SERVER_ERROR",
                            "message", "서버 오류가 발생했습니다."
                    ));
        }
    }

    // 스터디원 전용
    @GetMapping("/{taskId}")
    public ResponseEntity<ReservationResponseDTO> getLiveTask(@PathVariable Long taskId, HttpSession session) {
        Long userId = SessionUtil.getLoginUserId(session);
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ReservationResponseDTO response = reservationService.getTaskWithValidation(taskId, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{taskId}/submissions")
    public ResponseEntity<Void> submitLiveTask(
            @PathVariable Long taskId,
            HttpSession session, // 💡 변경: 억까 방지를 위해 임시 @RequestParam 대신 정석 세션 구조로 통일
            @RequestBody ReservationSubmitRequestDTO request) {

        Long userId = SessionUtil.getLoginUserId(session); // 💡 변경: 세션 유틸 적용

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        reservationService.submitTask(taskId, userId, request);
        return ResponseEntity.ok().build();
    }

}