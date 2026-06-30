package com.tencent.wxcloudrun.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.dao.MeetingRoomMapper;
import com.tencent.wxcloudrun.model.NotificationCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BookingNotificationScheduler {

  private static final Logger logger = LoggerFactory.getLogger(BookingNotificationScheduler.class);
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  private static final String WECHAT_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
  private static final String SUBSCRIBE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=%s";

  private final MeetingRoomMapper meetingRoomMapper;
  private final ObjectMapper objectMapper;
  private final RestTemplate restTemplate = new RestTemplate();

  private String cachedAccessToken;
  private long tokenExpiresAtMillis;

  @Value("${wx.app-id:}")
  private String wxAppId;

  @Value("${wx.app-secret:}")
  private String wxAppSecret;

  @Value("${notification.enabled:true}")
  private boolean notificationEnabled;

  @Value("${notification.zone-id:Asia/Shanghai}")
  private String notificationZoneId;

  @Value("${notification.scan-limit:50}")
  private Integer scanLimit;

  @Value("${notification.page:pages/my-bookings/index}")
  private String notificationPage;

  public BookingNotificationScheduler(@Autowired MeetingRoomMapper meetingRoomMapper, @Autowired ObjectMapper objectMapper) {
    this.meetingRoomMapper = meetingRoomMapper;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 30000)
  public void sendUpcomingMeetingNotifications() {
    if (!notificationEnabled) {
      return;
    }
    if (isBlank(wxAppId) || isBlank(wxAppSecret)) {
      logger.warn("notifications.scan skipped reason=missing_wx_config");
      return;
    }

    try {
      LocalDateTime now = LocalDateTime.now(ZoneId.of(notificationZoneId)).withSecond(0).withNano(0);
      LocalDateTime targetStart = now;
      LocalDateTime targetEnd = now.plusMinutes(16);
      List<NotificationCandidate> candidates = meetingRoomMapper.listPendingNotificationCandidates(
          targetStart.format(DATE_FORMATTER),
          targetStart.format(TIME_FORMATTER),
          targetEnd.format(DATE_FORMATTER),
          targetEnd.format(TIME_FORMATTER),
          scanLimit == null ? 50 : scanLimit);

      if (candidates.isEmpty()) {
        logger.info("notifications.scan done candidates=0 targetStart={} targetEnd={}", targetStart, targetEnd);
        return;
      }

      logger.info("notifications.scan start candidates={} targetStart={} targetEnd={}", candidates.size(), targetStart, targetEnd);
      for (NotificationCandidate candidate : candidates) {
        if (meetingRoomMapper.markSubscriptionSending(candidate.getSubscriptionId()) != 1) {
          continue;
        }
        try {
          sendSubscribeMessage(candidate);
          meetingRoomMapper.deleteBookingSubscription(candidate.getSubscriptionId());
          logger.info("notifications.send done_deleted subscriptionId={} bookingId={} openId={}", candidate.getSubscriptionId(), candidate.getBookingId(), maskOpenId(candidate.getOpenId()));
        } catch (Exception ex) {
          String message = trimError(ex.getMessage());
          meetingRoomMapper.deleteBookingSubscription(candidate.getSubscriptionId());
          logger.warn("notifications.send failed_deleted subscriptionId={} bookingId={} openId={} error={}", candidate.getSubscriptionId(), candidate.getBookingId(), maskOpenId(candidate.getOpenId()), message);
        }
      }
    } catch (Exception ex) {
      logger.warn("notifications.scan skipped reason=database_or_mapper_error error={}", trimError(ex.getMessage()));
    }
  }

  private void sendSubscribeMessage(NotificationCandidate candidate) throws Exception {
    String accessToken = getAccessToken();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("touser", candidate.getOpenId());
    payload.put("template_id", candidate.getTemplateId());
    payload.put("page", notificationPage);
    payload.put("data", buildTemplateData(candidate));

    String response = restTemplate.postForObject(String.format(SUBSCRIBE_SEND_URL, accessToken), payload, String.class);
    JsonNode json = objectMapper.readTree(response);
    int errorCode = json.has("errcode") ? json.get("errcode").asInt() : -1;
    if (errorCode != 0) {
      String errorMessage = json.has("errmsg") ? json.get("errmsg").asText() : response;
      throw new IllegalStateException("wechat subscribe send failed errcode=" + errorCode + " errmsg=" + errorMessage);
    }
  }

  private Map<String, Object> buildTemplateData(NotificationCandidate candidate) {
    Map<String, Object> data = new HashMap<>();
    String meetingTime = candidate.getDate() + " " + candidate.getStartTime();
    data.put("thing1", valueNode(truncate(candidate.getTitle(), 20)));
    data.put("date2", valueNode(meetingTime));
    data.put("thing5", valueNode(truncate(candidate.getTitle(), 20)));
    data.put("thing10", valueNode(truncate(candidate.getRoomName(), 20)));
    data.put("thing15", valueNode(truncate(candidate.getOrganizerName(), 20)));
    return data;
  }

  private Map<String, String> valueNode(String value) {
    Map<String, String> node = new HashMap<>();
    node.put("value", value == null ? "" : value);
    return node;
  }

  private String getAccessToken() throws Exception {
    long now = System.currentTimeMillis();
    if (!isBlank(cachedAccessToken) && now < tokenExpiresAtMillis) {
      return cachedAccessToken;
    }

    String response = restTemplate.getForObject(String.format(WECHAT_TOKEN_URL, wxAppId, wxAppSecret), String.class);
    JsonNode json = objectMapper.readTree(response);
    if (!json.hasNonNull("access_token")) {
      String errorMessage = json.has("errmsg") ? json.get("errmsg").asText() : response;
      throw new IllegalStateException("wechat access token failed: " + errorMessage);
    }

    cachedAccessToken = json.get("access_token").asText();
    int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 7200;
    tokenExpiresAtMillis = now + Math.max(60, expiresIn - 300) * 1000L;
    return cachedAccessToken;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private String trimError(String value) {
    if (value == null) {
      return "unknown error";
    }
    return value.length() > 500 ? value.substring(0, 500) : value;
  }

  private String maskOpenId(String openId) {
    if (isBlank(openId)) {
      return "-";
    }
    String value = openId.trim();
    if (value.length() <= 8) {
      return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
    return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
