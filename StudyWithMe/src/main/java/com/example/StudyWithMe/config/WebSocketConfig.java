package com.example.StudyWithMe.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@lombok.RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompHandler stompHandler;

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(stompHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic: 일대다 채팅 (브로드캐스팅)
        // /queue: 일대일 메시지
        config.enableSimpleBroker("/topic");
        // 클라이언트가 메시지를 보낼 때 붙이는 prefix
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 클라이언트가 소켓에 연결할 엔드포인트
        registry.addEndpoint("/ws-stomp")
                .setAllowedOrigins("http://localhost:5173", "http://localhost:9090",
                        "http://34.22.71.200", "http://34.22.71.200:9090")
                .withSockJS()
                .setInterceptors(httpSessionHandshakeInterceptor());
    }

    @Bean
    public HttpSessionHandshakeInterceptor httpSessionHandshakeInterceptor() {
        return new HttpSessionHandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
                // 부모 인터셉터가 HTTP 세션을 웹소켓 세션 속성으로 복사해줍니다.
                boolean result = super.beforeHandshake(request, response, wsHandler, attributes);

                // 💡 여기서 HTTP 세션에서 로그인 정보를 가져와 웹소켓 속성에 직접 넣어줍니다.
                if (request instanceof ServletServerHttpRequest) {
                    HttpSession session = ((ServletServerHttpRequest) request).getServletRequest().getSession(false);
                    if (session != null) {
                        // 세션 유틸을 활용해 ID 추출
                        Long userId = SessionUtil.getLoginUserId(session);
                        if (userId != null) {
                            attributes.put("userId", userId); // 👈 핵심! 이 키값으로 저장해야 합니다.
                        }
                    }
                }
                return result;
            }
        };
    }
}
