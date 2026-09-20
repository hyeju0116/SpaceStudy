package com.example.StudyWithMe;

import com.example.StudyWithMe.config.StompHandler;
import com.example.StudyWithMe.study.*;
import com.example.StudyWithMe.member.*;
import com.example.StudyWithMe.chat.*;
import com.example.StudyWithMe.assignment.*;
import com.example.StudyWithMe.reservation.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.redis.core.RedisTemplate;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegressionTests {
    private final StudyGroupRepository studies = mock(StudyGroupRepository.class);

    private org.springframework.messaging.Message<byte[]> frame(StompCommand command, Long user, String destination) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionAttributes(user == null ? Map.of() : Map.of("userId", user));
        if (destination != null) headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void anonymousConnectionsAreRejected() {
        assertThrows(AccessDeniedException.class, () -> new StompHandler(studies)
                .preSend(frame(StompCommand.CONNECT, null, null), null));
    }

    @Test void outsidersCannotSubscribeToStudyMessages() {
        assertThrows(AccessDeniedException.class, () -> new StompHandler(studies)
                .preSend(frame(StompCommand.SUBSCRIBE, 2L, "/topic/study/1"), null));
    }

    @Test void membersCanSubscribeAndSendButCannotPublishDirectlyToBroker() {
        when(studies.existsMemberInStudy(1L, 2L)).thenReturn(true);
        var handler = new StompHandler(studies);
        assertNotNull(handler.preSend(frame(StompCommand.SUBSCRIBE, 2L, "/topic/study/1"), null));
        assertNotNull(handler.preSend(frame(StompCommand.SEND, 2L, "/app/chat/1"), null));
        assertThrows(AccessDeniedException.class, () -> handler.preSend(frame(StompCommand.SEND, 2L, "/topic/study/1"), null));
        assertThrows(AccessDeniedException.class, () -> handler.preSend(frame(StompCommand.SUBSCRIBE, 2L, "/topic/errors/3"), null));
    }

    @Test void senderComesFromAuthenticatedMember() {
        var messages = mock(ChatMessageRepository.class);
        var members = mock(MemberRepository.class);
        when(studies.findById(1L)).thenReturn(Optional.of(new StudyGroup()));
        when(studies.existsMemberInStudy(1L, 2L)).thenReturn(true);
        when(members.findById(2L)).thenReturn(Optional.of(new Member("actual-name")));
        when(messages.save(any(ChatMessage.class))).thenAnswer(call -> call.getArgument(0));
        var saved = new ChatService(messages, studies, members).saveMessage(1L, "forged-name", "hello", 2L);
        assertEquals("actual-name", saved.getSender());
    }

    @Test void outsidersCannotReadOrSubmitReservations() {
        var reservations = mock(ReservationRepository.class);
        var submissions = mock(ReservationSubmissionRepository.class);
        var members = mock(MemberRepository.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        var group = mock(StudyGroup.class);
        when(group.getId()).thenReturn(1L);
        var task = new Reservation("title", "content", "answer", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), group);
        when(reservations.findById(3L)).thenReturn(Optional.of(task));
        var service = new ReservationService(reservations, submissions, studies, members, redis);
        assertThrows(AccessDeniedException.class, () -> service.getTaskWithValidation(3L, 2L));
        assertThrows(AccessDeniedException.class, () -> service.submitTask(3L, 2L, null));
        verifyNoInteractions(submissions, redis);
    }

    @Test void reservationEndpointRequiresLogin() {
        var service = mock(ReservationService.class);
        var controller = new ReservationController(mock(com.example.StudyWithMe.ai.GeminiService.class), service);
        var response = controller.getLiveTask(3L, new org.springframework.mock.web.MockHttpSession());
        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void membersCanReadAndSubmitOpenReservations() {
        var reservations = mock(ReservationRepository.class);
        var submissions = mock(ReservationSubmissionRepository.class);
        var members = mock(MemberRepository.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, Object> values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var group = mock(StudyGroup.class);
        when(group.getId()).thenReturn(1L);
        when(studies.existsMemberInStudy(1L, 2L)).thenReturn(true);
        var task = new Reservation("title", "content", "answer", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), group);
        when(reservations.findById(3L)).thenReturn(Optional.of(task));
        when(members.findById(2L)).thenReturn(Optional.of(new Member("member")));
        var request = mock(ReservationSubmitRequestDTO.class);
        when(request.getAnswer()).thenReturn("response");
        var service = new ReservationService(reservations, submissions, studies, members, redis);
        assertNotNull(service.getTaskWithValidation(3L, 2L));
        service.submitTask(3L, 2L, request);
        verify(submissions).save(any(ReservationSubmission.class));
    }

    @Test void leaderSeesAssignmentsWithNoSubmissions() {
        var assignments = mock(AssignmentRepository.class);
        var submissions = mock(SubmissionRepository.class);
        var members = mock(MemberRepository.class);
        var group = new StudyGroup();
        group.setTitle("study");
        group.setCreator(new Member("leader"));
        var task = new Assignment("task", "content", "answer", LocalDateTime.now().plusDays(1), group);
        when(assignments.findAllByLeaderId(1L)).thenReturn(List.of(task));
        var result = new AssignmentService(studies, assignments, submissions, members).getAssignmentsByLeader(1L);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).assignmentGroups().size());
        assertEquals("task", result.get(0).assignmentGroups().get(0).assignment().title());
        assertTrue(result.get(0).assignmentGroups().get(0).submissions().isEmpty());
        verify(assignments).findAllByLeaderId(1L);
        verifyNoInteractions(submissions);
    }
}
