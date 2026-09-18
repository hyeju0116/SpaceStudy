package com.example.StudyWithMe.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GeminiService {

    @Value("${google.ai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final String URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    public AiAssignmentResponseDTO generateAssignment(AiParameterDTO dto) {
        String prompt = String.format(
                "너는 과제 출제 전문가야. 주제: %s, 난이도: %s, 요청: %s\n" +
                        "다음 형식의 JSON으로만 응답해줘.\n" +
                        "{\n" +
                        "  \"title\": \"과제 제목\",\n" +
                        "  \"content\": \"스터디원들에게 보여줄 문제 내용\",\n" +
                        "  \"modelAnswer\": \"방장만 참고할 모범 답안 및 핵심 키워드\"\n" +
                        "}",
                dto.topic(), dto.difficulty(), dto.additionalRequest()
        );

        var requestBody = new GeminiRequestDTO(
                List.of(new GeminiRequestDTO.Content(List.of(new GeminiRequestDTO.Part(prompt))))
        );

        // 3. API 호출
        try {
            String response = restTemplate.postForObject(URL + apiKey, requestBody, String.class);
            // 4. JSON 파싱 (Jackson 사용)
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            String aiJson = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            String cleanedJson = aiJson.replace("```json", "")
                    .replace("```", "")
                    .trim();

            return mapper.readValue(cleanedJson, AiAssignmentResponseDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("AI 과제 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}
