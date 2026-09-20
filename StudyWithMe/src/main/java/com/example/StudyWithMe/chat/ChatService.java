package com.example.StudyWithMe.chat;

import com.example.StudyWithMe.study.StudyGroup;
import com.example.StudyWithMe.study.StudyGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final StudyGroupRepository studyGroupRepository;
    private final com.example.StudyWithMe.member.MemberRepository memberRepository;

    // 메시지 저장
    @Transactional
    public ChatMessage saveMessage(Long studyId, String sender, String content, Long currentMemberId) {
        log.info("DB 저장 시도: studyId={}, sender={}, content={}, userId={}", studyId, sender, content, currentMemberId);

        try {
            StudyGroup study = studyGroupRepository.findById(studyId)
                    .orElseThrow(() -> new IllegalArgumentException("스터디를 찾을 수 없습니다."));

            if (!studyGroupRepository.existsMemberInStudy(studyId, currentMemberId)) {
                throw new AccessDeniedException("해당 스터디의 멤버가 아닙니다.");
            }

            ChatMessage chatMessage = ChatMessage.builder()
                    .studyId(studyId)
                    .sender(memberRepository.findById(currentMemberId)
                            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다.")).getName())
                    .message(content)
                    .build();

            ChatMessage saved = chatMessageRepository.save(chatMessage);
            log.info("DB 저장 완료: ID={}", saved.getId()); // 저장된 ID까지 찍어보기
            return saved;

        } catch (Exception e) {
            log.error("서비스 계층 저장 실패: {}", e.getMessage(), e); // 에러 원인 상세 출력
            throw e; // 다시 던져서 컨트롤러가 잡게 함
        }
    }

    // 이전 채팅 내역 가져오기
    @Transactional(readOnly = true)
    public List<ChatResponseDTO> getChatHistory(Long studyId, Long currentMemberId) {
        // 해당 스터디의 멤버인지 확인
        if (!studyGroupRepository.existsMemberInStudy(studyId, currentMemberId)) {
            throw new AccessDeniedException("해당 스터디의 멤버가 아닙니다.");
        }

        // 시간순으로 전체 내역 조회 (필요시 페이징 처리)
        return chatMessageRepository.findByStudyIdOrderByTimestampAsc(studyId)
                .stream()
                .map(ChatResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }
}