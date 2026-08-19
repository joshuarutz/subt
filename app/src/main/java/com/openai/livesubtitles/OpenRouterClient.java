package com.openai.livesubtitles;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OpenRouterClient {
    private static final String STT_URL = "https://openrouter.ai/api/v1/audio/transcriptions";
    private static final String CHAT_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String TRANSLATION_MODEL = "google/gemini-2.5-flash-lite";

    private final String apiKey;
    private final String sttModel;
    private final String outputLanguage;

    OpenRouterClient(String apiKey, String sttModel, String outputLanguage) {
        this.apiKey = apiKey;
        this.sttModel = sttModel;
        this.outputLanguage = outputLanguage;
    }

    String transcribe(byte[] wavBytes) {
        try {
            JSONObject inputAudio = new JSONObject()
                    .put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP))
                    .put("format", "wav");

            JSONObject payload = new JSONObject()
                    .put("model", sttModel)
                    .put("input_audio", inputAudio);

            HttpResult response = postJson(STT_URL, payload, 3000, 10000);
            if (response.code < 200 || response.code >= 300) return "";
            return CoreLogic.cleanText(new JSONObject(response.body).optString("text", ""));
        } catch (Exception e) {
            return "";
        }
    }

    String translate(SubtitleService.SubtitleSource item) {
        String translated = translationRequest(item, true);
        if (translated.isEmpty()) translated = translationRequest(item, false);
        return translated;
    }

    private String translationRequest(SubtitleService.SubtitleSource item, boolean noReasoning) {
        try {
            String systemPrompt =
                    "You are a professional real-time subtitle translator.\n\n" +
                    "Translate ONLY CURRENT SOURCE into " + outputLanguage + ".\n\n" +
                    "The source language can be English, German, or Russian.\n\n" +
                    "PREVIOUS SOURCE CONTEXT and PREVIOUS TRANSLATION are context only.\n\n" +
                    "Use previous context to understand:\n" +
                    "- sentence continuation\n- grammar\n- pronouns\n- subjects and objects\n" +
                    "- verb phrases\n- references\n- intended meaning\n\n" +
                    "Never translate the previous context again.\n\n" +
                    "Output ONLY the translation of CURRENT SOURCE.\n\n" +
                    "CURRENT SOURCE may be a continuation of the previous subtitle.\n" +
                    "Make the translation connect naturally when appropriate.\n\n" +
                    "Rules:\n- No explanation.\n- No analysis.\n- No labels.\n- No quotation marks.\n" +
                    "- Do not repeat previous subtitles.\n- Do not invent missing speech.\n" +
                    "- Preserve names.\n- Preserve numbers.\n- Preserve negation.\n- Preserve meaning.\n" +
                    "- Keep wording concise and natural.\n- Write like professional film subtitles.";

            String previousTranslation = item.previousTranslation.isEmpty()
                    ? "[none]" : item.previousTranslation;
            String previousSource = item.previousSourceContext.isEmpty()
                    ? "[none]" : item.previousSourceContext;

            String userContent =
                    "PREVIOUS SOURCE CONTEXT:\n" + previousSource +
                    "\n\nPREVIOUS TRANSLATION:\n" + previousTranslation +
                    "\n\nCURRENT SOURCE:\n" + item.text;

            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(new JSONObject().put("role", "user").put("content", userContent));

            JSONObject payload = new JSONObject()
                    .put("model", TRANSLATION_MODEL)
                    .put("messages", messages)
                    .put("temperature", 0)
                    .put("max_tokens", 160);

            if (noReasoning) {
                payload.put("reasoning", new JSONObject()
                        .put("effort", "none")
                        .put("exclude", true));
            }

            HttpResult response = postJson(CHAT_URL, payload, 2500, 7000);
            if (response.code < 200 || response.code >= 300) return "";

            JSONObject result = new JSONObject(response.body);
            JSONObject message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            Object content = message.opt("content");

            if (content instanceof String) {
                return CoreLogic.cleanText((String) content);
            }

            if (content instanceof JSONArray) {
                JSONArray arr = (JSONArray) content;
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    Object part = arr.opt(i);
                    if (part instanceof String) {
                        if (builder.length() > 0) builder.append(' ');
                        builder.append(part);
                    } else if (part instanceof JSONObject) {
                        String text = ((JSONObject) part).optString("text", "");
                        if (!text.isEmpty()) {
                            if (builder.length() > 0) builder.append(' ');
                            builder.append(text);
                        }
                    }
                }
                return CoreLogic.cleanText(builder.toString());
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private HttpResult postJson(String endpoint, JSONObject payload, int connectTimeout, int readTimeout)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400
                ? connection.getInputStream() : connection.getErrorStream();

        String body = "";
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder b = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) b.append(line);
                body = b.toString();
            }
        }
        connection.disconnect();
        return new HttpResult(code, body);
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
