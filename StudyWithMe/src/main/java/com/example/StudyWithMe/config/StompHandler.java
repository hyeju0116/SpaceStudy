package com.example.StudyWithMe.config;

import com.example.StudyWithMe.study.StudyGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StompHandler implements ChannelInterceptor {
    private final StudyGroupRepository studyGroupRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command != StompCommand.CONNECT && command != StompCommand.SUBSCRIBE
                && command != StompCommand.SEND) return message;
        var attributes = accessor.getSessionAttributes();
        Long userId = attributes == null ? null : SessionUtil.getLoginUserIdFromSecurityContext(attributes);
        if (userId == null) throw new AccessDeniedException("로그인이 필요합니다.");
        if (command == StompCommand.CONNECT) return message;
        String destination = accessor.getDestination();
        if (command == StompCommand.SUBSCRIBE && ("/topic/errors/" + userId).equals(destination)) return message;
        String prefix = command == StompCommand.SUBSCRIBE ? "/topic/study/" : "/app/chat/";
        if (destination == null || !destination.startsWith(prefix)) {
            throw new AccessDeniedException("허용되지 않은 채널입니다.");
        }
        Long studyId;
        try {
            studyId = Long.valueOf(destination.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("잘못된 스터디 채널입니다.");
        }
        if (!studyGroupRepository.existsMemberInStudy(studyId, userId)) {
            throw new AccessDeniedException("해당 스터디의 멤버가 아닙니다.");
        }
        return message;
    }
}
