package com.example.StudyWithMe.assignment;

import com.example.StudyWithMe.member.Member;
import com.example.StudyWithMe.member.MemberRepository;
import com.example.StudyWithMe.study.StudyGroup;
import com.example.StudyWithMe.study.StudyGroupRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssignmentService {
    private final StudyGroupRepository studyGroupRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public Long createAssignment(Long studyId, AssignmentRequestDTO dto, Long currentMemberId) {
        // 1. 스터디 존재 확인
        StudyGroup study = studyGroupRepository.findById(studyId)
                .orElseThrow(() -> new IllegalArgumentException("스터디를 찾을 수 없습니다."));

        // 2. 방장 권한 체크 (ID 비교)
        if (!study.getCreator().getId().equals(currentMemberId)) {
            throw new IllegalStateException("스터디 방장만 과제를 출제할 수 있습니다.");
        }

        if (dto.getDueDate() != null && dto.getDueDate().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("마감 기한은 현재 시간보다 이후여야 합니다.");
        }

        // 3. 과제 생성 및 저장
        Assignment assignment = new Assignment(
                dto.getTitle(),
                dto.getContent(),
                dto.getModelAnswer(),
                dto.getDueDate(),
                study
        );

        return assignmentRepository.save(assignment).getId();
    }

    @Transactional
    public Long submitAssignment(Long studyId, Long assignmentId, Long memberId, String content) {
        // 1. 과제 존재 여부 및 정보 조회
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 과제입니다."));

        // 2. 해당 과제가 요청받은 studyId에 속하는지 검증
        if (!assignment.getStudyGroup().getId().equals(studyId)) {
            throw new IllegalArgumentException("스터디 정보와 과제 정보가 일치하지 않습니다.");
        }

        // 3. ★ 핵심: 해당 유저가 스터디 멤버인지 확인
        // studyMemberRepository를 사용하여 가입 여부 체크
        boolean isMember = studyGroupRepository.existsMemberInStudy(studyId, memberId);
        if (!isMember) {
            throw new IllegalStateException("해당 스터디의 멤버가 아닙니다.");
        }

        // 4. 과제 기한 체크
        if (assignment.getDueDate() != null && LocalDateTime.now().isAfter(assignment.getDueDate())) {
            throw new IllegalStateException("과제 제출 기한이 마감되었습니다.");
        }

        // 5. 중복 제출 체크
        if (submissionRepository.existsByAssignmentIdAndMemberId(assignmentId, memberId)) {
            throw new IllegalStateException("이미 제출한 과제입니다.");
        }

        // Member 엔티티를 조회해서 전달
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        AssignmentSubmission submission = new AssignmentSubmission(
                assignment, // 엔티티 전달
                member,   // 사용자 ID
                content     // 서술형 답안
        );

        return submissionRepository.save(submission).getId();
    }

    @Transactional
    public List<AssignmentResponseDTO> getMyAssignments(Long memberId) {
        // 1. 내가 참여 중인 스터디의 과제들 조회
        List<Assignment> assignments = assignmentRepository.findAllByMemberId(memberId);

        // 2. 각 과제별로 나의 제출 여부를 확인하여 DTO로 변환
        return assignments.stream()
                .map(assignment -> {
                    // 1. 제출 정보(Submission)를 DB에서 조회
                    AssignmentSubmission submission = submissionRepository
                            .findByAssignmentIdAndMemberId(assignment.getId(), memberId)
                            .orElse(null); // 제출하지 않았으면 null 반환

                    // 2. 조회된 submission 객체를 DTO 생성자로 전달
                    return AssignmentResponseDTO.of(assignment, submission);
                })
                .toList();
    }

    @Transactional
    public List<SubmissionResponseDTO> getSubmissionsByLeader(Long studyId, Long assignmentId, Long leaderId) {
        // 1. 과제 존재 확인 및 스터디 정보 가져오기
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 과제입니다."));

        // 2. 권한 체크: 요청자가 해당 스터디의 방장인지 확인
        if (!assignment.getStudyGroup().getCreator().getId().equals(leaderId)) {
            throw new IllegalStateException("스터디 방장만 답변을 조회할 수 있습니다.");
        }

        // 3. 해당 과제에 제출된 모든 답안 조회
        List<AssignmentSubmission> submissions = submissionRepository.findByAssignmentId(assignmentId);

        // 4. DTO 변환 (제출자 ID만 있으므로 이름을 찾아 매핑)
        return submissions.stream()
                .map(s -> {
                    String name = s.getMember().getName(); // 추가 쿼리 없이 바로 접근 가능
                    return SubmissionResponseDTO.from(s, name);
                })
                .toList();
    }

    @Transactional
    public List<StudyLeaderGroupResponseDTO> getAssignmentsByLeader(Long leaderId) {
        return assignmentRepository.findAllByLeaderId(leaderId).stream()
                .collect(Collectors.groupingBy(Assignment::getStudyGroup, java.util.LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> new StudyLeaderGroupResponseDTO(
                        entry.getKey().getId(), entry.getKey().getTitle(),
                        entry.getValue().stream().map(assignment -> new AssignmentGroupResponseDTO(
                                AssignmentResponseDTO.of(assignment, !assignment.getSubmissions().isEmpty()),
                                assignment.getSubmissions().stream()
                                        .map(submission -> SubmissionResponseDTO.from(submission, submission.getMember().getName()))
                                        .toList())).toList()))
                .toList();
    }

    @Transactional
    public void gradeSubmission(Long submissionId, GradeRequestDTO dto, Long leaderId) {
        // 1. 제출물 존재 확인 (성능을 위해 fetch join으로 스터디 정보까지 가져옴)
        AssignmentSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("제출된 과제를 찾을 수 없습니다."));

        // 2. 권한 체크: 과제가 속한 스터디의 방장 ID와 요청자 ID 비교
        StudyGroup studyGroup = submission.getAssignment().getStudyGroup();
        if (!studyGroup.getCreator().getId().equals(leaderId)) {
            throw new IllegalStateException("스터디 방장만 채점할 수 있습니다.");
        }

        // 3. 채점 업데이트
        submission.updateGrade(dto.score(), dto.feedback());
    }

}
