package com.tencent.wxcloudrun.model;

import lombok.Data;

@Data
public class NotificationCandidate {
  private String subscriptionId;
  private String bookingId;
  private String openId;
  private String templateId;
  private String roomName;
  private String date;
  private String startTime;
  private String endTime;
  private String title;
  private String organizerName;
}
