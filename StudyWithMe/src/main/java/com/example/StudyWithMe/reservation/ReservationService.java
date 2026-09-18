package com.example.StudyWithMe.reservation;

import com.example.StudyWithMe.assignment.Assignment;
import com.example.StudyWithMe.assignment.AssignmentRequestDTO;
import com.example.StudyWithMe.member.Member;
import com.example.StudyWithMe.member.MemberRepository;
import com.example.StudyWithMe.study.StudyGroup;
import com.example.StudyWithMe.study.StudyGroupRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSubmissionRepository submissionRepository;
    private final StudyGroupRepository studyGroupRepository;
    private final MemberRepository memberRepository;

    // Redis 연동을 위한 의존성 추가
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_KEY_PREFIX = "reservation:";

    /**
     * 실시간 예약 과제 생성 (방장 전용) + Redis Cache Warm-up 추가
     */
    @Transactional
    public Long createReservationTask(Long studyId, ReservationRequestDTO dto, Long currentMemberId) {
        StudyGroup study = studyGroupRepository.findById(studyId)
                .orElseThrow(() -> new IllegalArgumentException("스터디를 찾을 수 없습니다."));

        if (!study.getCreator().getId().equals(currentMemberId)) {
            throw new IllegalStateException("스터디 방장만 과제를 출제할 수 있습니다.");
        }

        if (dto.getOpenAt() == null || dto.getCloseAt() == null) {
            throw new IllegalArgumentException("예약 시작 시간과 제한 시간은 필수입니다.");
        }

        if (dto.getOpenAt().isAfter(dto.getCloseAt())) {
            throw new IllegalArgumentException("제한 시간은 시작 시간보다 이후여야 합니다.");
        }

        Reservation reservation = new Reservation(
                dto.getTitle(),
                dto.getContent(),
                dto.getModelAnswer(),
                dto.getOpenAt(),
                dto.getCloseAt(),
                study
        );

        Reservation savedReservation = reservationRepository.save(reservation);

        // [Cache Warm-up] 저장 즉시 Redis에 캐싱하여 대량 조회 트래픽 대비
        try {
            String cacheKey = CACHE_KEY_PREFIX + savedReservation.getId();
            ReservationCacheDTO cacheDto = ReservationCacheDTO.from(savedReservation);

            // 과제 마감 시간 이후까지 캐시가 유지되도록 TTL 설정 (예: 마감 후 1시간 뒤 만료)
            long ttlSeconds = java.time.Duration.between(LocalDateTime.now(), savedReservation.getCloseAt()).getSeconds() + 3600;
            redisTemplate.opsForValue().set(cacheKey, cacheDto, Math.max(ttlSeconds, 3600), java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // 캐시 저장이 실패해도 DB 저장은 성공했으므로 롤백하지 않고 로그만 남김 (Fail-safe)
            System.err.println("Redis 캐시 워밍업 실패: " + e.getMessage());
        }

        return savedReservation.getId();
    }

    /**
     * 과제 상세 조회 (시간 검증 포함) - Redis 우선 조회로 아키텍처 개선
     */
    public ReservationResponseDTO getTaskWithValidation(Long taskId, Long userId) {
        requireMember(taskId, userId);
        String cacheKey = CACHE_KEY_PREFIX + taskId;

        // 1. Redis 캐시에서 먼저 조회
        ReservationCacheDTO cachedTask = (ReservationCacheDTO) redisTemplate.opsForValue().get(cacheKey);

        if (cachedTask != null) {
            // 캐시 데이터로 대기실/마감 시간 검증 (DB 호출 0번)
            cachedTask.validateAccessible();
            return new ReservationResponseDTO(cachedTask.getId(), cachedTask.getTitle(), cachedTask.getContent(), cachedTask.getOpenAt(), cachedTask.getCloseAt());
        }

        // 2. 캐시 미스(Cache Miss) 발생 시에만 RDB 조회 (만료되었거나 캐싱 안 된 경우 대비)
        System.out.println("Cache Miss - DB 조회를 수행합니다. taskId: " + taskId);
        Reservation reservation = reservationRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다."));

        reservation.validateAccessible();

        return ReservationResponseDTO.of(reservation);
    }

    /**
     * 정답 제출 (시간 검증 포함)
     */
    @Transactional
    public void submitTask(Long taskId, Long userId, ReservationSubmitRequestDTO request) {
        requireMember(taskId, userId);
        String cacheKey = CACHE_KEY_PREFIX + taskId;

        // 제출 시 시간 검증도 Redis 캐시를 활용해 RDB Read 부하 최소화
        ReservationCacheDTO cachedTask = (ReservationCacheDTO) redisTemplate.opsForValue().get(cacheKey);
        if (cachedTask != null) {
            cachedTask.validateAccessible();
        }

        // 제출 기록 저장을 위해 최소한의 DB 작업 진행
        Reservation reservation = reservationRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다."));

        if (cachedTask == null) { // 캐시가 없다면 RDB 기반으로 한 번 더 검증
            reservation.validateAccessible();
        }

        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        ReservationSubmission submission = new ReservationSubmission(reservation, member, request.getAnswer());
        submissionRepository.save(submission);
    }
    private void requireMember(Long taskId, Long userId) {
        Reservation reservation = reservationRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다."));
        if (userId == null || !studyGroupRepository.existsMemberInStudy(reservation.getStudyGroup().getId(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("해당 스터디의 멤버가 아닙니다.");
        }
    }
}