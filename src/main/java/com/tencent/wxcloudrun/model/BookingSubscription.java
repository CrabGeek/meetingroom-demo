package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BookingSubscription {
  private String id;
  private String bookingId;
  private String openId;
  private String templateId;
  private Boolean subscribed;
  private String notifyStatus;
  private LocalDateTime sentAt;
  private String lastError;
  private Integer retryCount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
